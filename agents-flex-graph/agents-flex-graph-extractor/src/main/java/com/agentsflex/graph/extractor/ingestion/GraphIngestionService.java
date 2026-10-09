package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.core.document.Document;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.GraphExtractionResult;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.registry.GraphEntityRegistry;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.schema.GraphSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 编排内容判重、版本差异、图写入、实体注册和文档状态提交的长期文档入图服务。
 *
 * <p>服务不持有图数据库连接，调用方显式传入 {@link GraphWriter}。先调用 {@code plan} 可以在写入前
 * 审核候选与删除项；{@code ingest} 是计划和执行的便捷组合。默认锁只在当前 JVM 内按文档串行，
 * 不会阻塞不同文档；多实例部署应注入分布式 {@link GraphIngestionLockProvider}，并继续为状态存储实现
 * 原子 compare-and-set。</p>
 */
public final class GraphIngestionService {
    /**
     * 完整文档抽取流水线。
     */
    private final GraphExtractionPipeline pipeline;
    /**
     * 文档版本和关系来源状态存储。
     */
    private final GraphDocumentStateStore stateStore;
    /**
     * 可选实体注册表；为空时只管理文档版本状态。
     */
    private final GraphEntityRegistry entityRegistry;
    /**
     * 可选的跨系统操作状态存储。
     */
    private final GraphIngestionOperationStore operationStore;
    /**
     * 同一 Space 和文档的执行互斥边界。
     */
    private final GraphIngestionLockProvider lockProvider;
    /**
     * 可注入时钟，保证状态时间可测试。
     */
    private final LongSupplier clock;

    /**
     * 创建不自动保存实体注册记录的入图服务。
     */
    public GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore) {
        this(pipeline, stateStore, null, null, new LocalGraphIngestionLockProvider(), System::currentTimeMillis);
    }

    /**
     * 创建同时维护跨批次实体注册表的入图服务。
     */
    public GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                                 GraphEntityRegistry entityRegistry) {
        this(pipeline, stateStore, entityRegistry, null, new LocalGraphIngestionLockProvider(),
            System::currentTimeMillis);
    }

    /**
     * 创建同时维护实体注册表和持久化操作状态机的入图服务。
     */
    public GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                                 GraphEntityRegistry entityRegistry,
                                 GraphIngestionOperationStore operationStore) {
        this(pipeline, stateStore, entityRegistry, operationStore, new LocalGraphIngestionLockProvider(),
            System::currentTimeMillis);
    }

    /**
     * 创建具有持久化恢复日志和自定义文档锁的入图服务。
     *
     * @param lockProvider 单实例使用本地实现，多实例应传入共享的分布式锁实现
     */
    public GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                                 GraphEntityRegistry entityRegistry,
                                 GraphIngestionOperationStore operationStore,
                                 GraphIngestionLockProvider lockProvider) {
        this(pipeline, stateStore, entityRegistry, operationStore, lockProvider, System::currentTimeMillis);
    }

    /**
     * 包内测试可使用确定性时钟。
     */
    GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                          GraphEntityRegistry entityRegistry, LongSupplier clock) {
        this(pipeline, stateStore, entityRegistry, null, new LocalGraphIngestionLockProvider(), clock);
    }

    /**
     * 包内测试可同时注入操作存储和确定性时钟。
     */
    GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                          GraphEntityRegistry entityRegistry,
                          GraphIngestionOperationStore operationStore, LongSupplier clock) {
        this(pipeline, stateStore, entityRegistry, operationStore, new LocalGraphIngestionLockProvider(), clock);
    }

    /**
     * 包内测试可同时注入操作存储、锁和确定性时钟。
     */
    GraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                          GraphEntityRegistry entityRegistry,
                          GraphIngestionOperationStore operationStore,
                          GraphIngestionLockProvider lockProvider, LongSupplier clock) {
        if (pipeline == null || stateStore == null || lockProvider == null || clock == null) {
            throw new IllegalArgumentException("pipeline, stateStore, lockProvider and clock must not be null");
        }
        this.pipeline = pipeline;
        this.stateStore = stateStore;
        this.entityRegistry = entityRegistry;
        this.operationStore = operationStore;
        this.lockProvider = lockProvider;
        this.clock = clock;
    }

    /**
     * 为完整文档生成入图执行计划；内容未变化时不会调用 extractor。
     */
    public GraphIngestionPlan plan(Document document, GraphSchema schema,
                                   GraphIngestionRequest request) {
        if (document == null || document.getContent() == null || document.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("document content must not be blank");
        }
        require(schema, request);
        String contentHash = resolveHash(request.getContentHash(),
            Collections.singletonList(document.getContent()));
        GraphDocumentState previous = stateStore.findCurrent(request.getSpace(), request.getDocumentId());
        verifySourceVersion(previous, request);
        String fingerprint = extractionFingerprint(request, schema);
        GraphIngestionPlan unchanged = unchanged(previous, request, schema, contentHash, fingerprint);
        if (unchanged != null) return unchanged;

        // 使用请求中的稳定 documentId 创建浅副本，避免修改调用方 Document，同时保证 evidence 可长期定位。
        Document source = Document.of(document.getContent());
        source.setId(request.getDocumentId());
        source.setTitle(document.getTitle());
        source.putMetadata(document.getMetadataMap());
        GraphExtractionResult extraction = pipeline.extract(source, schema, request.getExtractionOptions());
        return createPlan(previous, extraction, schema, request, contentHash, fingerprint);
    }

    /**
     * 为调用方已经切分好的文档生成入图计划，哈希按 Chunk 顺序和字符边界计算。
     */
    public GraphIngestionPlan planChunks(List<Document> chunks, GraphSchema schema,
                                         GraphIngestionRequest request) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("chunks must not be empty");
        require(schema, request);
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().trim().isEmpty()) {
                throw new IllegalArgumentException("chunk content must not be blank at index " + i);
            }
            contents.add(chunk.getContent());
        }
        String contentHash = resolveHash(request.getContentHash(), contents);
        GraphDocumentState previous = stateStore.findCurrent(request.getSpace(), request.getDocumentId());
        verifySourceVersion(previous, request);
        String fingerprint = extractionFingerprint(request, schema);
        GraphIngestionPlan unchanged = unchanged(previous, request, schema, contentHash, fingerprint);
        if (unchanged != null) return unchanged;
        GraphExtractionResult extraction = pipeline.extractChunks(chunks, request.getDocumentId(), schema,
            request.getExtractionOptions());
        return createPlan(previous, extraction, schema, request, contentHash, fingerprint);
    }

    /**
     * 生成并立即执行完整文档入图计划。
     */
    public GraphIngestionResult ingest(Document document, GraphSchema schema,
                                       GraphIngestionRequest request,
                                       GraphWriter writer) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        try (GraphIngestionLockProvider.Lease ignored = acquire(request.getSpace(), request.getDocumentId())) {
            return executeLocked(plan(document, schema, request), writer);
        }
    }

    /**
     * 生成并立即执行预分段文档入图计划。
     */
    public GraphIngestionResult ingestChunks(List<Document> chunks, GraphSchema schema,
                                             GraphIngestionRequest request,
                                             GraphWriter writer) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        try (GraphIngestionLockProvider.Lease ignored = acquire(request.getSpace(), request.getDocumentId())) {
            return executeLocked(planChunks(chunks, schema, request), writer);
        }
    }

    /**
     * 生成文档撤回计划，只删除没有被其他活动文档引用的关系，不自动删除可能共享的实体节点。
     */
    public GraphIngestionPlan planRetraction(String space, String documentId,
                                             GraphOptions graphOptions) {
        if (graphOptions == null || graphOptions.getSpace() == null || !space.equals(graphOptions.getSpace())) {
            throw new IllegalArgumentException("graphOptions must explicitly target retraction space");
        }
        GraphDocumentState previous = stateStore.findCurrent(space, documentId);
        GraphMutation.Builder mutation = GraphMutation.builder();
        Set<GraphEdgeKey> stale = new LinkedHashSet<>();
        if (previous == null || previous.getStatus() == GraphDocumentState.Status.RETRACTED) {
            return new GraphIngestionPlan(GraphIngestionPlan.Type.NO_OP,
                space, documentId, graphOptions, null, null, null, mutation.build(), stale,
                Collections.<GraphRegisteredEntity>emptyList());
        }
        stale.addAll(previous.getEdgeKeys());
        stale.addAll(previous.getSupersededEdgeKeys());
        for (GraphEdgeKey edge : stale) {
            if (!stateStore.isReferencedByOtherDocument(space, documentId, edge)) mutation.deleteEdge(edge);
        }
        String operationId = "retract-" + resolveHash("", Collections.singletonList(space + "\u0000" + documentId
            + "\u0000" + previous.getRevision()));
        GraphDocumentState next = GraphDocumentState.builder(space, documentId, previous.getContentHash())
            .status(GraphDocumentState.Status.RETRACTED).revision(previous.getRevision() + 1L)
            .operationId(operationId).documentVersion(previous.getDocumentVersion())
            .schemaVersion(previous.getSchemaVersion()).extractionFingerprint(previous.getExtractionFingerprint())
            .sourceUpdatedAtMillis(previous.getSourceUpdatedAtMillis()).batchId(previous.getBatchId())
            .committedAtMillis(clock.getAsLong()).supersededEdgeKeys(stale).build();
        mutation.operationId(operationId);
        return new GraphIngestionPlan(GraphIngestionPlan.Type.RETRACTION,
            space, documentId, graphOptions, previous, next, null, mutation.build(), stale,
            Collections.<GraphRegisteredEntity>emptyList());
    }

    /**
     * 执行已经审核的计划，并且只在图写入成功后提交实体和文档状态。
     *
     * <p>写入前会再次检查 revision，尽早拒绝过期计划。图写入与外部状态存储无法组成跨系统事务；
     * 若写图后 CAS 仍因多实例竞争失败，方法会抛出异常，调用方应按相同内容摘要重试幂等 upsert。</p>
     */
    public GraphIngestionResult execute(GraphIngestionPlan plan, GraphWriter writer) {
        if (plan == null || writer == null) throw new IllegalArgumentException("plan and writer must not be null");
        try (GraphIngestionLockProvider.Lease ignored = acquire(plan.getSpace(), plan.getDocumentId())) {
            return executeLocked(plan, writer);
        }
    }

    /**
     * 从操作日志中读取首次冻结的计划并继续执行，不会重新调用大模型抽取。
     *
     * @param operationId 要恢复的稳定操作号
     * @param writer      目标图写入器
     * @return 本次恢复执行结果
     */
    public GraphIngestionResult resume(String operationId, GraphWriter writer) {
        if (!isRecoverySupported()) {
            throw new IllegalStateException("a recovery-capable operationStore is required for ingestion recovery");
        }
        if (operationId == null || operationId.trim().isEmpty() || writer == null) {
            throw new IllegalArgumentException("operationId and writer must not be null or blank");
        }
        GraphIngestionPlan plan = operationStore.getPlan(operationId.trim());
        if (plan == null) {
            throw new GraphExtractionException("Persisted ingestion plan was not found: " + operationId.trim());
        }
        return execute(plan, writer);
    }

    /**
     * 返回可由 {@link #resume(String, GraphWriter)} 恢复的未完成操作。
     */
    public List<GraphIngestionOperation> listRecoverableOperations(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (operationStore == null) return Collections.emptyList();
        if (!isRecoverySupported()) {
            throw new IllegalStateException("operationStore does not support ingestion recovery");
        }
        return operationStore.listRecoverableOperations(limit);
    }

    /**
     * @return 是否已配置明确支持计划读取和恢复扫描的操作存储；跨进程恢复需使用持久化实现。
     */
    public boolean isRecoverySupported() {
        return operationStore != null && operationStore.isRecoverySupported();
    }

    /**
     * 在已经持有文档锁的前提下执行计划。
     */
    private GraphIngestionResult executeLocked(GraphIngestionPlan plan, GraphWriter writer) {
        if (plan == null || writer == null) throw new IllegalArgumentException("plan and writer must not be null");
        if (plan.getType() == GraphIngestionPlan.Type.NO_OP) {
            return new GraphIngestionResult(plan, GraphWriteResult.success(0L, 0L), true);
        }
        String operationId = plan.getNextState() == null ? plan.getMutation().getOperationId()
            : plan.getNextState().getOperationId();
        long expectedRevision = plan.getPreviousState() == null ? 0L : plan.getPreviousState().getRevision();
        String planFingerprint = GraphIngestionPlanFingerprint.compute(plan);
        GraphIngestionOperation operation = prepareOperation(plan, operationId, expectedRevision, planFingerprint);
        GraphDocumentState alreadyCommitted = stateStore.findByOperationId(plan.getSpace(), plan.getDocumentId(), operationId);
        if (alreadyCommitted != null) {
            if (plan.getNextState() != null
                && (!alreadyCommitted.getContentHash().equals(plan.getNextState().getContentHash())
                || !alreadyCommitted.getExtractionFingerprint().equals(plan.getNextState().getExtractionFingerprint())
                || alreadyCommitted.getStatus() != plan.getNextState().getStatus())) {
                throw new GraphExtractionException("Operation id is already used for a different document version: "
                    + operationId);
            }
            // 上一次可能在当前状态 CAS 成功后、历史快照或操作状态提交前退出；幂等补齐这些步骤。
            stateStore.recordVersion(alreadyCommitted);
            completeOperation(operation);
            return new GraphIngestionResult(plan, GraphWriteResult.success(0L, 0L), true);
        }
        verifyRevision(plan.getSpace(), plan.getDocumentId(), expectedRevision);
        boolean graphAlreadyApplied = operation != null
            && (operation.getStage() == GraphIngestionOperation.Stage.GRAPH_APPLIED
            || operation.getStage() == GraphIngestionOperation.Stage.STATE_COMMITTED
            || operation.getStage() == GraphIngestionOperation.Stage.COMPLETED);
        GraphWriteResult write;
        try {
            write = graphAlreadyApplied || !plan.isWriteRequired()
                ? GraphWriteResult.success(0L, 0L) : writer.mutate(plan.getMutation(), plan.getGraphOptions());
        } catch (RuntimeException exception) {
            failOperation(operation, exception.getMessage());
            throw exception;
        }
        if (!write.isSuccess()) {
            failOperation(operation, write.getMessage());
            return new GraphIngestionResult(plan, write, false);
        }
        if (!graphAlreadyApplied) operation = advanceOperation(operation, GraphIngestionOperation.Stage.GRAPH_APPLIED);

        if (entityRegistry != null && !plan.getEntityRegistrations().isEmpty()) {
            entityRegistry.saveAll(plan.getSpace(), plan.getEntityRegistrations());
        }
        boolean committed = stateStore.compareAndSet(plan.getSpace(), plan.getDocumentId(), expectedRevision,
            plan.getNextState());
        if (!committed) {
            throw new GraphExtractionException("Graph was written but document state changed concurrently: "
                + plan.getSpace() + "/" + plan.getDocumentId());
        }
        stateStore.recordVersion(plan.getNextState());
        operation = advanceOperation(operation, GraphIngestionOperation.Stage.STATE_COMMITTED);
        completeOperation(operation);
        return new GraphIngestionResult(plan, write, true);
    }

    /**
     * 创建或恢复同一 operationId 的持久化操作状态。
     */
    private GraphIngestionOperation prepareOperation(GraphIngestionPlan plan, String operationId,
                                                     long expectedRevision, String planFingerprint) {
        if (operationStore == null) return null;
        GraphIngestionOperation operation = operationStore.get(operationId);
        if (operation == null) {
            GraphIngestionOperation prepared = new GraphIngestionOperation(operationId, plan.getSpace(),
                plan.getDocumentId(), expectedRevision, planFingerprint, GraphIngestionOperation.Stage.PREPARED,
                clock.getAsLong(), "");
            operationStore.createIfAbsent(prepared, plan);
            operation = operationStore.get(operationId);
        }
        if (operation == null || !operation.getSpace().equals(plan.getSpace())
            || !operation.getDocumentId().equals(plan.getDocumentId())
            || operation.getExpectedRevision() != expectedRevision
            || !operation.getPlanFingerprint().equals(planFingerprint)) {
            throw new GraphExtractionException("Operation id is already bound to another ingestion plan: "
                + operationId);
        }
        if (operation.getStage() == GraphIngestionOperation.Stage.FAILED) {
            GraphIngestionOperation retry = operation.transition(GraphIngestionOperation.Stage.PREPARED,
                clock.getAsLong(), "");
            if (!operationStore.compareAndSet(operationId, GraphIngestionOperation.Stage.FAILED, retry)) {
                throw new GraphExtractionException("Operation state changed concurrently: " + operationId);
            }
            operation = retry;
        }
        return operation;
    }

    /**
     * 获取文档锁并拒绝返回 null 的错误实现。
     */
    private GraphIngestionLockProvider.Lease acquire(String space, String documentId) {
        GraphIngestionLockProvider.Lease lease = lockProvider.acquire(space, documentId);
        if (lease == null) throw new IllegalStateException("lockProvider returned null lease");
        return lease;
    }

    /**
     * 原子推进操作阶段；未配置操作存储时保持无副作用。
     */
    private GraphIngestionOperation advanceOperation(GraphIngestionOperation operation,
                                                     GraphIngestionOperation.Stage next) {
        if (operation == null || operation.getStage() == next
            || operation.getStage() == GraphIngestionOperation.Stage.COMPLETED) return operation;
        GraphIngestionOperation advanced = operation.transition(next, clock.getAsLong(), "");
        if (!operationStore.compareAndSet(operation.getOperationId(), operation.getStage(), advanced)) {
            throw new GraphExtractionException("Operation state changed concurrently: " + operation.getOperationId());
        }
        return advanced;
    }

    /**
     * 标记操作最终完成。
     */
    private void completeOperation(GraphIngestionOperation operation) {
        if (operation == null || operation.getStage() == GraphIngestionOperation.Stage.COMPLETED) return;
        GraphIngestionOperation current = operation;
        // 当前文档状态中的 operationId 已证明业务提交完成，可以把滞后的日志按合法路径逐级补齐。
        if (current.getStage() == GraphIngestionOperation.Stage.FAILED) {
            current = advanceOperation(current, GraphIngestionOperation.Stage.PREPARED);
        }
        if (current.getStage() == GraphIngestionOperation.Stage.PREPARED) {
            current = advanceOperation(current, GraphIngestionOperation.Stage.GRAPH_APPLIED);
        }
        if (current.getStage() == GraphIngestionOperation.Stage.GRAPH_APPLIED) {
            current = advanceOperation(current, GraphIngestionOperation.Stage.STATE_COMMITTED);
        }
        if (current.getStage() == GraphIngestionOperation.Stage.STATE_COMMITTED) {
            advanceOperation(current, GraphIngestionOperation.Stage.COMPLETED);
        }
    }

    /**
     * 保存可重试失败状态。
     */
    private void failOperation(GraphIngestionOperation operation, String message) {
        if (operation == null) return;
        GraphIngestionOperation failed = operation.transition(GraphIngestionOperation.Stage.FAILED,
            clock.getAsLong(), message);
        operationStore.compareAndSet(operation.getOperationId(), operation.getStage(), failed);
    }

    /**
     * 校验公共必填参数。
     */
    private static void require(GraphSchema schema, GraphIngestionRequest request) {
        if (schema == null || request == null)
            throw new IllegalArgumentException("schema and request must not be null");
    }

    /**
     * 内容未变化且没有强制重抽取时创建零副作用计划。
     */
    private static GraphIngestionPlan unchanged(GraphDocumentState previous,
                                                GraphIngestionRequest request,
                                                GraphSchema schema, String contentHash,
                                                String extractionFingerprint) {
        String documentVersion = request.getDocumentVersion().isEmpty() ? contentHash : request.getDocumentVersion();
        String schemaVersion = request.getSchemaVersion().isEmpty()
            ? schema.getMetadata().getVersion() : request.getSchemaVersion();
        if (previous == null || previous.getStatus() != GraphDocumentState.Status.ACTIVE || request.isReextractUnchangedContent()
            || !previous.getContentHash().equals(contentHash)
            || !previous.getDocumentVersion().equals(documentVersion)
            || !previous.getSchemaVersion().equals(schemaVersion)
            || !previous.getExtractionFingerprint().equals(extractionFingerprint)) return null;
        return new GraphIngestionPlan(GraphIngestionPlan.Type.NO_OP,
            request.getSpace(), request.getDocumentId(), request.getGraphOptions(), previous, previous, null,
            GraphMutation.builder().build(), Collections.<GraphEdgeKey>emptySet(),
            Collections.<GraphRegisteredEntity>emptyList());
    }

    /**
     * 根据抽取结果、旧状态和旧关系策略创建新版本计划。
     */
    private GraphIngestionPlan createPlan(GraphDocumentState previous, GraphExtractionResult extraction,
                                          GraphSchema schema, GraphIngestionRequest request,
                                          String contentHash, String extractionFingerprint) {
        if (request.isFailOnExtractionError() && extraction.hasErrors()) {
            throw new GraphExtractionException("Ingestion rejected extraction result containing errors");
        }
        GraphMutation extracted = extraction.getMutation();
        Set<GraphEdgeKey> nextEdges = new LinkedHashSet<>();
        Set<String> nextNodes = new LinkedHashSet<>();
        for (GraphEdge edge : extracted.getEdges()) nextEdges.add(edge.getKey());
        for (GraphNode node : extracted.getNodes()) nextNodes.add(node.getId());

        Set<GraphEdgeKey> stale = new LinkedHashSet<>();
        if (previous != null) stale.addAll(previous.getEdgeKeys());
        stale.removeAll(nextEdges);
        String operationId = effectiveOperationId(request, contentHash, extractionFingerprint);
        GraphMutation.Builder mutation = copy(extracted).operationId(operationId);
        boolean partial = extraction.hasErrors();
        if (partial && !request.isPartialReconciliationAllowed() && previous != null) {
            // 部分抽取默认只允许追加，不允许把失败 Chunk 的旧事实误判为已删除。
            nextEdges.addAll(previous.getEdgeKeys());
            nextNodes.addAll(previous.getNodeIds());
            stale.clear();
        }
        if (request.getStaleRelationPolicy()
            == GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED) {
            if (!partial || request.isPartialReconciliationAllowed()) {
                for (GraphEdgeKey edge : stale) {
                    if (!stateStore.isReferencedByOtherDocument(request.getSpace(), request.getDocumentId(), edge)) {
                        mutation.deleteEdge(edge);
                    }
                }
            }
        }
        long nextRevision = previous == null ? 1L : previous.getRevision() + 1L;
        long committedAtMillis = clock.getAsLong();
        String documentVersion = request.getDocumentVersion().isEmpty() ? contentHash : request.getDocumentVersion();
        String schemaVersion = request.getSchemaVersion().isEmpty()
            ? schema.getMetadata().getVersion() : request.getSchemaVersion();
        List<GraphFactSource> nextFactSources = new ArrayList<>();
        if (partial && !request.isPartialReconciliationAllowed() && previous != null) {
            nextFactSources.addAll(previous.getFactSources());
        }
        nextFactSources.addAll(factSources(request.getSpace(), extraction, operationId, nextRevision,
            committedAtMillis));
        GraphDocumentState next = GraphDocumentState.builder(request.getSpace(), request.getDocumentId(), contentHash)
            .revision(nextRevision).operationId(operationId).documentVersion(documentVersion).schemaVersion(schemaVersion)
            .extractionFingerprint(extractionFingerprint).sourceUpdatedAtMillis(request.getSourceUpdatedAtMillis())
            .batchId(request.getBatchId()).committedAtMillis(committedAtMillis)
            .nodeIds(nextNodes).edgeKeys(nextEdges).supersededEdgeKeys(stale)
            .factSources(nextFactSources).build();
        return new GraphIngestionPlan(GraphIngestionPlan.Type.INGESTION,
            request.getSpace(), request.getDocumentId(), request.getGraphOptions(), previous, next, extraction,
            mutation.build(), stale, registrations(extraction)).withSourceContext(schema, request);
    }

    /**
     * 根据人工审核后的候选重建计划，不调用模型、不写图。
     *
     * <p>沿用原文版本、操作号和旧状态，重新计算 Mutation、过期关系、事实来源及实体注册记录。
     * 原请求的安全删除和部分抽取保护仍然有效。</p>
     */
    public GraphIngestionPlan rebuildPlan(GraphIngestionPlan original, GraphExtractionResult reviewed) {
        if (original == null || reviewed == null || original.getType() != GraphIngestionPlan.Type.INGESTION
            || original.getSourceSchema() == null || original.getSourceRequest() == null) {
            throw new IllegalArgumentException("an INGESTION plan with complete source context is required");
        }
        GraphDocumentState next = original.getNextState();
        return createPlan(original.getPreviousState(), reviewed, original.getSourceSchema(),
            original.getSourceRequest(), next.getContentHash(), next.getExtractionFingerprint());
    }

    /**
     * 复制抽取 mutation，随后由文档版本逻辑追加安全删除项。
     */
    private static GraphMutation.Builder copy(GraphMutation source) {
        GraphMutation.Builder result = GraphMutation.builder().upsertNodes(source.getNodes())
            .upsertEdges(source.getEdges()).detachDeletedNodes(source.isDetachDeletedNodes())
            .operationId(source.getOperationId());
        for (String id : source.getDeleteNodeIds()) result.deleteNode(id);
        for (GraphEdgeKey edge : source.getDeleteEdgeKeys()) result.deleteEdge(edge);
        return result;
    }

    /**
     * 把通过校验的关系转换为可长期查询的来源记录；相同关系的多段证据会分别保留。
     */
    private static List<GraphFactSource> factSources(String space, GraphExtractionResult extraction,
                                                     String operationId, long documentRevision,
                                                     long createdAtMillis) {
        List<GraphFactSource> result = new ArrayList<>();
        for (GraphRelationCandidate relation : extraction.getValidatedRelations()) {
            String source = extraction.getEntityResolution().findNodeId(relation.getSourceCandidateKey());
            String target = extraction.getEntityResolution().findNodeId(relation.getTargetCandidateKey());
            if (source == null || target == null || relation.getEvidence() == null) continue;
            GraphEdgeKey key = new GraphEdgeKey(source, relation.getType(), target, relation.getRank());
            String factId = "fact-" + resolveHash("", Collections.singletonList(space + "\u0000"
                + key.portableId() + "\u0000"
                + relation.getEvidence().getDocumentId() + "\u0000" + relation.getEvidence().getChunkId()
                + "\u0000" + relation.getEvidence().getQuote()));
            result.add(new GraphFactSource(factId, operationId, documentRevision, createdAtMillis,
                key, relation.getEvidence(), relation.getConfidence(), relation.getAssertionType(),
                relation.getProperties()));
        }
        return result;
    }

    /**
     * 从已接受候选和最终节点映射构造实体注册记录，供图写成功后持久化。
     */
    private static List<GraphRegisteredEntity> registrations(GraphExtractionResult extraction) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (GraphNode node : extraction.getEntityResolution().getNodes()) nodes.put(node.getId(), node);
        Map<String, Registration> registrations = new LinkedHashMap<>();
        for (GraphEntityCandidate candidate : extraction.getValidatedEntities()) {
            String nodeId = extraction.getEntityResolution().findNodeId(candidate.getCandidateKey());
            if (nodeId == null) continue;
            Registration value = registrations.get(nodeId);
            if (value == null) {
                value = new Registration(nodeId, candidate.getType(), candidate.getName());
                registrations.put(nodeId, value);
            } else if (!normalize(value.canonicalName).equals(normalize(candidate.getName()))) {
                value.aliases.add(candidate.getName());
            }
            for (String alias : candidate.getAliases()) if (!isWeakAlias(alias)) value.aliases.add(alias);
        }
        List<GraphRegisteredEntity> result = new ArrayList<>();
        for (Registration value : registrations.values()) {
            value.aliases.remove(value.canonicalName);
            GraphNode node = nodes.get(value.nodeId);
            result.add(new GraphRegisteredEntity(value.nodeId, value.type, value.canonicalName,
                new ArrayList<>(value.aliases), node == null ? Collections.<String, Object>emptyMap() : node.getProperties()));
        }
        return result;
    }

    /**
     * 写入前确认计划基于当前最新 revision。
     */
    private void verifyRevision(String space, String documentId, long expectedRevision) {
        GraphDocumentState current = stateStore.findCurrent(space, documentId);
        long actual = current == null ? 0L : current.getRevision();
        if (actual != expectedRevision) {
            throw new GraphExtractionException("Ingestion plan is stale for " + space + "/" + documentId);
        }
    }

    /**
     * 拒绝来源时间早于当前已提交版本的乱序导入。
     */
    private void verifySourceVersion(GraphDocumentState previous, GraphIngestionRequest request) {
        if (previous != null && previous.getStatus() == GraphDocumentState.Status.ACTIVE
            && request.getSourceUpdatedAtMillis() >= 0L
            && previous.getSourceUpdatedAtMillis() >= 0L
            && request.getSourceUpdatedAtMillis() < previous.getSourceUpdatedAtMillis()) {
            throw new GraphExtractionException("Source document version is older than the committed version: "
                + request.getSpace() + "/" + request.getDocumentId());
        }
    }

    /**
     * 组合调用方指纹和 SDK 已知抽取配置，避免模型或质量选项变化被错误判重。
     */
    private static String extractionFingerprint(GraphIngestionRequest request, GraphSchema schema) {
        if (!request.getExtractionFingerprint().isEmpty()) return request.getExtractionFingerprint();
        String schemaVersion = request.getSchemaVersion().isEmpty()
            ? (schema.getMetadata().getVersion() == null ? "" : schema.getMetadata().getVersion())
            : request.getSchemaVersion();
        return resolveHash("", Collections.singletonList("schema=" + schemaVersion
            + "\u0000options=" + request.getExtractionOptions().fingerprint()));
    }

    /**
     * 生成稳定的默认操作号；同一文档版本的重试会得到相同操作号。
     */
    private static String effectiveOperationId(GraphIngestionRequest request, String contentHash,
                                               String extractionFingerprint) {
        if (!request.getOperationId().isEmpty()) return request.getOperationId();
        return "ingest-" + resolveHash("", Collections.singletonList(request.getSpace() + "\u0000"
            + request.getDocumentId() + "\u0000" + contentHash + "\u0000"
            + request.getDocumentVersion() + "\u0000" + request.getBatchId() + "\u0000"
            + request.isReextractUnchangedContent() + "\u0000" + extractionFingerprint));
    }

    /**
     * 使用调用方摘要，或对按顺序带长度边界的文本计算 SHA-256。
     */
    private static String resolveHash(String supplied, Collection<String> contents) {
        if (supplied != null && !supplied.trim().isEmpty()) return supplied.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String content : contents) {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) 0);
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(64);
            for (byte value : hash) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 排除不应进入长期实体注册表的代词别名。
     */
    private static boolean isWeakAlias(String alias) {
        String value = normalize(alias);
        return value.equals("他") || value.equals("她") || value.equals("它") || value.equals("他们")
            || value.equals("她们") || value.equals("它们") || value.equals("其") || value.equals("此人")
            || value.equals("该人") || value.equals("he") || value.equals("she") || value.equals("it")
            || value.equals("they") || value.equals("him") || value.equals("her") || value.equals("them");
    }

    /**
     * 使用与实体解析器一致的名称规范化语义。
     */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 聚合单个最终节点在当前批次出现的名称和别名。
     */
    private static final class Registration {
        /**
         * 稳定节点 ID。
         */
        private final String nodeId;
        /**
         * Schema 节点类型。
         */
        private final String type;
        /**
         * 本批首次出现的名称。
         */
        private final String canonicalName;
        /**
         * 后续名称和强别名。
         */
        private final Set<String> aliases = new LinkedHashSet<>();

        private Registration(String nodeId, String type, String canonicalName) {
            this.nodeId = nodeId;
            this.type = type;
            this.canonicalName = canonicalName;
        }
    }
}
