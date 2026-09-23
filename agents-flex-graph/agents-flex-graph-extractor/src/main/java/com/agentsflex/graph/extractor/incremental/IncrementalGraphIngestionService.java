package com.agentsflex.graph.extractor.incremental;

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
import com.agentsflex.graph.extractor.resolution.GraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.GraphRegisteredEntity;
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
 * 编排内容判重、版本差异、图写入、实体注册和文档状态提交的长期增量导入服务。
 *
 * <p>服务不持有图数据库连接，调用方显式传入 {@link GraphWriter}。先调用 {@code plan} 可以在写入前
 * 审核候选与删除项；{@code ingest} 是计划和执行的便捷组合。单实例内方法使用同步边界减少同文档竞争，
 * 多实例部署仍应在 documentId 维度加分布式锁，并为状态存储实现原子 compare-and-set。</p>
 */
public final class IncrementalGraphIngestionService {
    /**
     * 完整文档抽取流水线。
     */
    private final GraphExtractionPipeline pipeline;
    /**
     * 文档版本和关系来源状态存储。
     */
    private final GraphDocumentStateStore stateStore;
    /**
     * 可选实体注册表；为空时只管理文档增量状态。
     */
    private final GraphEntityRegistry entityRegistry;
    /**
     * 可注入时钟，保证状态时间可测试。
     */
    private final LongSupplier clock;

    /**
     * 创建不自动保存实体注册记录的增量服务。
     */
    public IncrementalGraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore) {
        this(pipeline, stateStore, null, System::currentTimeMillis);
    }

    /**
     * 创建同时维护跨批次实体注册表的增量服务。
     */
    public IncrementalGraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                                            GraphEntityRegistry entityRegistry) {
        this(pipeline, stateStore, entityRegistry, System::currentTimeMillis);
    }

    /**
     * 包内测试可使用确定性时钟。
     */
    IncrementalGraphIngestionService(GraphExtractionPipeline pipeline, GraphDocumentStateStore stateStore,
                                     GraphEntityRegistry entityRegistry, LongSupplier clock) {
        if (pipeline == null || stateStore == null || clock == null) {
            throw new IllegalArgumentException("pipeline, stateStore and clock must not be null");
        }
        this.pipeline = pipeline;
        this.stateStore = stateStore;
        this.entityRegistry = entityRegistry;
        this.clock = clock;
    }

    /**
     * 为完整文档生成增量执行计划；内容未变化时不会调用 extractor。
     */
    public synchronized IncrementalGraphIngestionPlan plan(Document document, GraphSchema schema,
                                                           IncrementalGraphIngestionRequest request) {
        if (document == null || document.getContent() == null || document.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("document content must not be blank");
        }
        require(schema, request);
        String contentHash = resolveHash(request.getContentHash(),
            Collections.singletonList(document.getContent()));
        GraphDocumentState previous = stateStore.get(request.getSpace(), request.getDocumentId());
        verifySourceVersion(previous, request);
        String fingerprint = extractionFingerprint(request, schema);
        IncrementalGraphIngestionPlan unchanged = unchanged(previous, request, schema, contentHash, fingerprint);
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
     * 为调用方已经切分好的文档生成增量计划，哈希按 Chunk 顺序和字符边界计算。
     */
    public synchronized IncrementalGraphIngestionPlan planChunks(List<Document> chunks, GraphSchema schema,
                                                                 IncrementalGraphIngestionRequest request) {
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
        GraphDocumentState previous = stateStore.get(request.getSpace(), request.getDocumentId());
        verifySourceVersion(previous, request);
        String fingerprint = extractionFingerprint(request, schema);
        IncrementalGraphIngestionPlan unchanged = unchanged(previous, request, schema, contentHash, fingerprint);
        if (unchanged != null) return unchanged;
        GraphExtractionResult extraction = pipeline.extractChunks(chunks, request.getDocumentId(), schema,
            request.getExtractionOptions());
        return createPlan(previous, extraction, schema, request, contentHash, fingerprint);
    }

    /**
     * 生成并立即执行完整文档增量计划。
     */
    public synchronized IncrementalGraphIngestionResult ingest(Document document, GraphSchema schema,
                                                               IncrementalGraphIngestionRequest request,
                                                               GraphWriter writer) {
        return execute(plan(document, schema, request), writer);
    }

    /**
     * 生成并立即执行预分段文档增量计划。
     */
    public synchronized IncrementalGraphIngestionResult ingestChunks(List<Document> chunks, GraphSchema schema,
                                                                     IncrementalGraphIngestionRequest request,
                                                                     GraphWriter writer) {
        return execute(planChunks(chunks, schema, request), writer);
    }

    /**
     * 生成文档撤回计划，只删除没有被其他活动文档引用的关系，不自动删除可能共享的实体节点。
     */
    public synchronized IncrementalGraphIngestionPlan planRetraction(String space, String documentId,
                                                                     GraphOptions graphOptions) {
        if (graphOptions == null || graphOptions.getSpace() == null || !space.equals(graphOptions.getSpace())) {
            throw new IllegalArgumentException("graphOptions must explicitly target retraction space");
        }
        GraphDocumentState previous = stateStore.get(space, documentId);
        GraphMutation.Builder mutation = GraphMutation.builder();
        Set<GraphEdgeKey> stale = new LinkedHashSet<>();
        if (previous == null || previous.getStatus() == GraphDocumentState.Status.RETRACTED) {
            return new IncrementalGraphIngestionPlan(IncrementalGraphIngestionPlan.Status.UNCHANGED,
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
        return new IncrementalGraphIngestionPlan(IncrementalGraphIngestionPlan.Status.RETRACTION,
            space, documentId, graphOptions, previous, next, null, mutation.build(), stale,
            Collections.<GraphRegisteredEntity>emptyList());
    }

    /**
     * 执行已经审核的计划，并且只在图写入成功后提交实体和文档状态。
     *
     * <p>写入前会再次检查 revision，尽早拒绝过期计划。图写入与外部状态存储无法组成跨系统事务；
     * 若写图后 CAS 仍因多实例竞争失败，方法会抛出异常，调用方应按相同内容摘要重试幂等 upsert。</p>
     */
    public synchronized IncrementalGraphIngestionResult execute(IncrementalGraphIngestionPlan plan,
                                                                GraphWriter writer) {
        if (plan == null || writer == null) throw new IllegalArgumentException("plan and writer must not be null");
        if (plan.getStatus() == IncrementalGraphIngestionPlan.Status.UNCHANGED) {
            return new IncrementalGraphIngestionResult(plan, GraphWriteResult.success(0L, 0L), true);
        }
        String operationId = plan.getNextState() == null ? plan.getMutation().getOperationId()
            : plan.getNextState().getOperationId();
        GraphDocumentState alreadyCommitted = stateStore.findByOperationId(plan.getSpace(), plan.getDocumentId(), operationId);
        if (alreadyCommitted != null) {
            if (plan.getNextState() != null
                && (!alreadyCommitted.getContentHash().equals(plan.getNextState().getContentHash())
                || !alreadyCommitted.getExtractionFingerprint().equals(plan.getNextState().getExtractionFingerprint())
                || alreadyCommitted.getStatus() != plan.getNextState().getStatus())) {
                throw new GraphExtractionException("Operation id is already used for a different document version: "
                    + operationId);
            }
            return new IncrementalGraphIngestionResult(plan, GraphWriteResult.success(0L, 0L), true);
        }
        long expectedRevision = plan.getPreviousState() == null ? 0L : plan.getPreviousState().getRevision();
        verifyRevision(plan.getSpace(), plan.getDocumentId(), expectedRevision);
        GraphWriteResult write = plan.isWriteRequired()
            ? writer.mutate(plan.getMutation(), plan.getGraphOptions()) : GraphWriteResult.success(0L, 0L);
        if (!write.isSuccess()) return new IncrementalGraphIngestionResult(plan, write, false);

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
        return new IncrementalGraphIngestionResult(plan, write, true);
    }

    /**
     * 校验公共必填参数。
     */
    private static void require(GraphSchema schema, IncrementalGraphIngestionRequest request) {
        if (schema == null || request == null)
            throw new IllegalArgumentException("schema and request must not be null");
    }

    /**
     * 内容未变化且没有强制重抽取时创建零副作用计划。
     */
    private static IncrementalGraphIngestionPlan unchanged(GraphDocumentState previous,
                                                           IncrementalGraphIngestionRequest request,
                                                           GraphSchema schema, String contentHash,
                                                           String extractionFingerprint) {
        String documentVersion = request.getDocumentVersion().isEmpty() ? contentHash : request.getDocumentVersion();
        String schemaVersion = request.getSchemaVersion().isEmpty()
            ? schema.getMetadata().getVersion() : request.getSchemaVersion();
        if (previous == null || previous.getStatus() != GraphDocumentState.Status.ACTIVE || request.isForceReextract()
            || !previous.getContentHash().equals(contentHash)
            || !previous.getDocumentVersion().equals(documentVersion)
            || !previous.getSchemaVersion().equals(schemaVersion)
            || !previous.getExtractionFingerprint().equals(extractionFingerprint)) return null;
        return new IncrementalGraphIngestionPlan(IncrementalGraphIngestionPlan.Status.UNCHANGED,
            request.getSpace(), request.getDocumentId(), request.getGraphOptions(), previous, previous, null,
            GraphMutation.builder().build(), Collections.<GraphEdgeKey>emptySet(),
            Collections.<GraphRegisteredEntity>emptyList());
    }

    /**
     * 根据抽取结果、旧状态和旧关系策略创建新版本计划。
     */
    private IncrementalGraphIngestionPlan createPlan(GraphDocumentState previous, GraphExtractionResult extraction,
                                                     GraphSchema schema, IncrementalGraphIngestionRequest request,
                                                     String contentHash, String extractionFingerprint) {
        if (request.isRejectExtractionErrors() && extraction.hasErrors()) {
            throw new GraphExtractionException("Incremental ingestion rejected extraction result containing errors");
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
        if (partial && !request.isAllowPartialReconcile() && previous != null) {
            // 部分抽取默认只允许追加，不允许把失败 Chunk 的旧事实误判为已删除。
            nextEdges.addAll(previous.getEdgeKeys());
            nextNodes.addAll(previous.getNodeIds());
            stale.clear();
        }
        if (request.getStaleRelationPolicy()
            == IncrementalGraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED) {
            if (!partial || request.isAllowPartialReconcile()) {
                for (GraphEdgeKey edge : stale) {
                    if (!stateStore.isReferencedByOtherDocument(request.getSpace(), request.getDocumentId(), edge)) {
                        mutation.deleteEdge(edge);
                    }
                }
            }
        }
        long nextRevision = previous == null ? 1L : previous.getRevision() + 1L;
        String documentVersion = request.getDocumentVersion().isEmpty() ? contentHash : request.getDocumentVersion();
        String schemaVersion = request.getSchemaVersion().isEmpty()
            ? schema.getMetadata().getVersion() : request.getSchemaVersion();
        List<GraphFactProvenance> nextProvenances = new ArrayList<>();
        if (partial && !request.isAllowPartialReconcile() && previous != null) {
            nextProvenances.addAll(previous.getFactProvenances());
        }
        nextProvenances.addAll(provenances(extraction));
        GraphDocumentState next = GraphDocumentState.builder(request.getSpace(), request.getDocumentId(), contentHash)
            .revision(nextRevision).operationId(operationId).documentVersion(documentVersion).schemaVersion(schemaVersion)
            .extractionFingerprint(extractionFingerprint).sourceUpdatedAtMillis(request.getSourceUpdatedAtMillis())
            .batchId(request.getBatchId()).committedAtMillis(clock.getAsLong())
            .nodeIds(nextNodes).edgeKeys(nextEdges).supersededEdgeKeys(stale)
            .factProvenances(nextProvenances).build();
        return new IncrementalGraphIngestionPlan(IncrementalGraphIngestionPlan.Status.READY,
            request.getSpace(), request.getDocumentId(), request.getGraphOptions(), previous, next, extraction,
            mutation.build(), stale, registrations(extraction));
    }

    /**
     * 复制抽取 mutation，随后由增量逻辑追加安全删除项。
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
     * 把已接受关系转换为可长期查询的来源记录；相同关系的多段证据会分别保留。
     */
    private static List<GraphFactProvenance> provenances(GraphExtractionResult extraction) {
        List<GraphFactProvenance> result = new ArrayList<>();
        for (GraphRelationCandidate relation : extraction.getRelations()) {
            String source = extraction.getResolution().nodeId(relation.getSourceCandidateKey());
            String target = extraction.getResolution().nodeId(relation.getTargetCandidateKey());
            if (source == null || target == null || relation.getEvidence() == null) continue;
            GraphEdgeKey key = new GraphEdgeKey(source, relation.getType(), target, relation.getRank());
            result.add(new GraphFactProvenance(key, relation.getEvidence(), relation.getConfidence(),
                relation.getAssertionType(), relation.getProperties()));
        }
        return result;
    }

    /**
     * 从已接受候选和最终节点映射构造实体注册记录，供图写成功后持久化。
     */
    private static List<GraphRegisteredEntity> registrations(GraphExtractionResult extraction) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        for (GraphNode node : extraction.getResolution().getNodes()) nodes.put(node.getId(), node);
        Map<String, Registration> registrations = new LinkedHashMap<>();
        for (GraphEntityCandidate candidate : extraction.getEntities()) {
            String nodeId = extraction.getResolution().nodeId(candidate.getCandidateKey());
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
        GraphDocumentState current = stateStore.get(space, documentId);
        long actual = current == null ? 0L : current.getRevision();
        if (actual != expectedRevision) {
            throw new GraphExtractionException("Incremental ingestion plan is stale for " + space + "/" + documentId);
        }
    }

    /**
     * 拒绝来源时间早于当前已提交版本的乱序导入。
     */
    private void verifySourceVersion(GraphDocumentState previous, IncrementalGraphIngestionRequest request) {
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
    private static String extractionFingerprint(IncrementalGraphIngestionRequest request, GraphSchema schema) {
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
    private static String effectiveOperationId(IncrementalGraphIngestionRequest request, String contentHash,
                                               String extractionFingerprint) {
        if (!request.getOperationId().isEmpty()) return request.getOperationId();
        return "ingest-" + resolveHash("", Collections.singletonList(request.getSpace() + "\u0000"
            + request.getDocumentId() + "\u0000" + contentHash + "\u0000"
            + request.getDocumentVersion() + "\u0000" + request.getBatchId() + "\u0000"
            + request.isForceReextract() + "\u0000" + extractionFingerprint));
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
