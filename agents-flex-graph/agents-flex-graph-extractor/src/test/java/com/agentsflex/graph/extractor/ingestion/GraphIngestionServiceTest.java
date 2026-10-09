package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.core.document.Document;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.GraphExtractor;
import com.agentsflex.graph.extractor.GraphMutationMapper;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 长期文档入图的判重、版本替换、来源引用和提交边界测试。
 */
public class GraphIngestionServiceTest {
    /**
     * 相同内容摘要再次导入时不得调用 extractor 或 GraphWriter。
     */
    @Test
    public void shouldSkipUnchangedDocumentBeforeModelCall() {
        Scenario scenario = scenario();
        Document document = Document.of("林默加入青云会");
        GraphIngestionRequest request = request("doc-1").build();

        GraphIngestionResult first = scenario.service.ingest(document, schema(), request, scenario.writer);
        GraphIngestionResult second = scenario.service.ingest(document, schema(), request, scenario.writer);

        assertTrue(first.isSuccess());
        assertEquals(GraphIngestionPlan.Status.UNCHANGED, second.getPlan().getStatus());
        assertEquals(1, scenario.extractor.calls.get());
        assertEquals(1, scenario.writer.calls);
        assertEquals(1L, scenario.states.get("knowledge", "doc-1").getRevision());
        assertEquals(scenario.states.get("knowledge", "doc-1").getOperationId(),
            scenario.writer.lastMutation.getOperationId());
        assertEquals(1, scenario.states.get("knowledge", "doc-1").getFactSources().size());
        assertEquals("doc-1", scenario.states.get("knowledge", "doc-1").getFactSources().get(0)
            .getEvidence().getDocumentId());
        GraphFactSource fact = scenario.states.get("knowledge", "doc-1").getFactSources().get(0);
        assertTrue(fact.getFactId().startsWith("fact-"));
        assertEquals(1L, fact.getDocumentRevision());
        assertEquals(scenario.states.get("knowledge", "doc-1").getOperationId(), fact.getOperationId());
        assertEquals(GraphIngestionOperation.Stage.COMPLETED,
            scenario.operations.get(fact.getOperationId()).getStage());
    }

    /**
     * 关系仍被其他文档支持时，新版本不得误删共享事实。
     */
    @Test
    public void shouldKeepStaleRelationReferencedByAnotherDocumentAndDeleteAfterLastRetraction() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-a").build(), scenario.writer);
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-b").build(), scenario.writer);

        GraphIngestionResult updated = scenario.service.ingest(Document.of("林默离开了山门"), schema(),
            request("doc-a").staleRelationPolicy(
                GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED).build(), scenario.writer);

        assertEquals(1, updated.getPlan().getStaleEdgeKeys().size());
        assertTrue(updated.getPlan().getMutation().getDeleteEdgeKeys().isEmpty());
        GraphIngestionPlan retraction = scenario.service.planRetraction("knowledge", "doc-b",
            GraphOptions.ofSpace("knowledge"));
        assertEquals(1, retraction.getMutation().getDeleteEdgeKeys().size());
        GraphIngestionResult retracted = scenario.service.execute(retraction, scenario.writer);
        assertTrue(retracted.isSuccess());
        assertNotNull(scenario.states.get("knowledge", "doc-b"));
        assertEquals(GraphDocumentState.Status.RETRACTED,
            scenario.states.get("knowledge", "doc-b").getStatus());
        assertNotNull(scenario.states.get("knowledge", "doc-a"));
    }

    /**
     * 相同正文但抽取选项变化时，配置指纹变化应触发重新抽取。
     */
    @Test
    public void shouldReextractWhenExtractionFingerprintChanges() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1")
            .extractionOptions(com.agentsflex.graph.extractor.GraphExtractionOptions.builder()
                .contextCharacters(100).build()).build(), scenario.writer);
        GraphIngestionResult second = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").extractionOptions(com.agentsflex.graph.extractor.GraphExtractionOptions.builder()
                .contextCharacters(200).build()).build(), scenario.writer);

        assertEquals(GraphIngestionPlan.Status.READY, second.getPlan().getStatus());
        assertEquals(2, scenario.extractor.calls.get());
        assertEquals(2, scenario.states.listVersions("knowledge", "doc-1").size());
        GraphEdgeKey edge = scenario.states.get("knowledge", "doc-1").getEdgeKeys().iterator().next();
        assertEquals(2, scenario.states.findHistoricalFactSources("knowledge", edge).size());
    }

    /**
     * 来源更新时间倒退时必须拒绝导入，避免旧文件覆盖新版本。
     */
    @Test
    public void shouldRejectOutOfOrderSourceVersion() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").sourceUpdatedAtMillis(200L).build(), scenario.writer);
        try {
            scenario.service.plan(Document.of("林默返回青云会"), schema(),
                request("doc-1").sourceUpdatedAtMillis(100L).build());
            fail("older source version should be rejected");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("older"));
        }
    }

    /**
     * 部分 Chunk 失败时即使调用方允许提交，也不得默认删除旧关系。
     */
    @Test
    public void shouldPreserveOldRelationsForPartialExtraction() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);
        GraphIngestionRequest partial = request("doc-1")
            .staleRelationPolicy(GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED)
            .rejectExtractionErrors(false)
            .extractionOptions(com.agentsflex.graph.extractor.GraphExtractionOptions.builder()
                .failOnChunkError(false).build())
            .build();

        GraphIngestionResult result = scenario.service.ingest(Document.of("失败"), schema(), partial,
            scenario.writer);

        assertTrue(result.isSuccess());
        assertTrue(result.getPlan().getExtractionResult().hasErrors());
        assertTrue(result.getPlan().getStaleEdgeKeys().isEmpty());
        assertEquals(1, scenario.states.get("knowledge", "doc-1").getEdgeKeys().size());
        assertTrue(result.getPlan().getMutation().getDeleteEdgeKeys().isEmpty());
    }

    /**
     * 强制重抽取必须生成新的默认操作号，不能被上一版本的幂等记录短路。
     */
    @Test
    public void shouldAllowForceReextractWithNewBatch() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);
        GraphIngestionResult result = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").forceReextract(true).batchId("rerun-1").build(), scenario.writer);

        assertEquals(GraphIngestionPlan.Status.READY, result.getPlan().getStatus());
        assertEquals(2, scenario.extractor.calls.get());
        assertEquals(2, scenario.writer.calls);
    }

    /**
     * 同一个显式操作号不能被复用于不同文档版本。
     */
    @Test
    public void shouldRejectOperationIdReuseAcrossVersions() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").operationId("operation-1").build(), scenario.writer);
        GraphIngestionPlan plan = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").operationId("operation-1").build());
        try {
            scenario.service.execute(plan, scenario.writer);
            fail("operation id reuse should be rejected");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("Operation id"));
        }
    }

    /**
     * 操作已记录 GRAPH_APPLIED 时，恢复执行不得再次调用 GraphWriter。
     */
    @Test
    public void shouldResumeAfterGraphAppliedWithoutDuplicateWrite() {
        Scenario scenario = scenario();
        GraphIngestionPlan plan = scenario.service.plan(Document.of("林默加入青云会"), schema(),
            request("doc-1").operationId("resume-operation").build());
        scenario.writer.fail = true;
        scenario.service.execute(plan, scenario.writer);
        GraphIngestionOperation failed = scenario.operations.get("resume-operation");
        GraphIngestionOperation prepared = failed.transition(GraphIngestionOperation.Stage.PREPARED,
            failed.getUpdatedAtMillis(), "");
        assertTrue(scenario.operations.compareAndSet("resume-operation", GraphIngestionOperation.Stage.FAILED,
            prepared));
        GraphIngestionOperation applied = prepared.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED,
            prepared.getUpdatedAtMillis(), "");
        assertTrue(scenario.operations.compareAndSet("resume-operation", GraphIngestionOperation.Stage.PREPARED,
            applied));
        scenario.writer.fail = false;
        scenario.writer.calls = 0;

        GraphIngestionResult result = scenario.service.execute(plan, scenario.writer);

        assertTrue(result.isSuccess());
        assertEquals(0, scenario.writer.calls);
        assertEquals(GraphIngestionOperation.Stage.COMPLETED,
            scenario.operations.get("resume-operation").getStage());
        assertEquals(1L, scenario.states.get("knowledge", "doc-1").getRevision());
    }

    /**
     * 相同 operationId 和基线 revision 也不能承载另一份抽取计划，否则恢复时可能提交错配状态。
     */
    @Test
    public void shouldRejectDifferentPlanWithSameOperationAndRevision() {
        Scenario scenario = scenario();
        GraphIngestionPlan first = scenario.service.plan(Document.of("林默加入青云会"), schema(),
            request("doc-1").operationId("shared-operation").build());
        scenario.writer.fail = true;
        scenario.service.execute(first, scenario.writer);

        GraphIngestionPlan different = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").operationId("shared-operation").build());
        try {
            scenario.service.execute(different, scenario.writer);
            fail("different plan should not reuse operation id");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("another ingestion plan"));
        }
        assertNull(scenario.states.get("knowledge", "doc-1"));
    }

    /**
     * 状态机不得跳过 GRAPH_APPLIED 和 STATE_COMMITTED 直接宣告完成。
     */
    @Test
    public void shouldRejectIllegalOperationStageTransition() {
        GraphIngestionOperation operation = new GraphIngestionOperation("operation-1", "knowledge", "doc-1",
            0L, "fingerprint", GraphIngestionOperation.Stage.PREPARED, 1_700_000_000_000L, "");
        try {
            operation.transition(GraphIngestionOperation.Stage.COMPLETED, 1_700_000_000_001L, "");
            fail("illegal state transition should be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("PREPARED -> COMPLETED"));
        }
    }

    /**
     * 相同文档、关系和证据位于不同 Space 时必须获得不同事实 ID，避免跨知识库审计冲突。
     */
    @Test
    public void shouldScopeFactIdBySpace() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);
        scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            GraphIngestionRequest.builder("archive", "doc-1").build(), scenario.writer);

        String knowledgeFact = scenario.states.get("knowledge", "doc-1").getFactSources().get(0).getFactId();
        String archiveFact = scenario.states.get("archive", "doc-1").getFactSources().get(0).getFactId();
        assertFalse(knowledgeFact.equals(archiveFact));
    }

    /**
     * 新服务实例应直接读取日志中的原计划恢复，不得再次调用大模型生成可能不同的抽取结果。
     */
    @Test
    public void shouldResumePersistedPlanWithoutReextracting() {
        Scenario scenario = scenario();
        scenario.writer.fail = true;
        GraphIngestionResult failed = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").operationId("recover-operation").build(), scenario.writer);
        assertFalse(failed.isSuccess());
        assertEquals(1, scenario.service.listRecoverableOperations(10).size());
        assertNotNull(scenario.operations.getPlan("recover-operation"));

        GraphIngestionService restarted = new GraphIngestionService(
            scenario.pipeline, scenario.states, scenario.registry, scenario.operations, scenario.locks,
            scenario.clock::getAndIncrement);
        scenario.writer.fail = false;
        int extractionCalls = scenario.extractor.calls.get();
        GraphIngestionResult recovered = restarted.resume("recover-operation", scenario.writer);

        assertTrue(recovered.isSuccess());
        assertEquals(extractionCalls, scenario.extractor.calls.get());
        assertTrue(restarted.listRecoverableOperations(10).isEmpty());
        assertEquals(GraphIngestionOperation.Stage.COMPLETED,
            scenario.operations.get("recover-operation").getStage());
    }

    /**
     * 文档级锁不能退化为服务级全局锁，不同文档应能同时进入 GraphWriter。
     */
    @Test
    public void shouldExecuteDifferentDocumentsConcurrently() throws Exception {
        Scenario scenario = scenario();
        GraphIngestionPlan first = scenario.service.plan(Document.of("林默加入青云会"), schema(),
            request("doc-a").build());
        GraphIngestionPlan second = scenario.service.plan(Document.of("林默加入青云会"), schema(),
            request("doc-b").build());
        ConcurrentWriter writer = new ConcurrentWriter(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<GraphIngestionResult> firstResult = executor.submit(
                () -> scenario.service.execute(first, writer));
            Future<GraphIngestionResult> secondResult = executor.submit(
                () -> scenario.service.execute(second, writer));

            assertTrue("different documents should reach writer concurrently", writer.awaitAll());
            assertTrue(firstResult.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(secondResult.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(2, writer.calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 恢复扫描必须排除完成项，并按照最旧更新时间稳定分页。
     */
    @Test
    public void shouldListOldestRecoverableOperationsFirst() {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        store.createIfAbsent(operation("later", GraphIngestionOperation.Stage.PREPARED, 20L));
        store.createIfAbsent(operation("oldest", GraphIngestionOperation.Stage.FAILED, 10L));
        store.createIfAbsent(operation("completed", GraphIngestionOperation.Stage.COMPLETED, 1L));

        java.util.List<GraphIngestionOperation> operations = store.listRecoverable(1);

        assertEquals(1, operations.size());
        assertEquals("oldest", operations.get(0).getOperationId());
    }

    /**
     * 同一条关系被多个文档支持时，状态存储应聚合全部来源证据。
     */
    @Test
    public void shouldAggregateFactSourcesAcrossDocuments() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-a").build(), scenario.writer);
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-b").build(), scenario.writer);

        GraphEdgeKey sharedEdge = scenario.states.get("knowledge", "doc-a").getEdgeKeys().iterator().next();
        java.util.List<GraphFactSource> factSources = scenario.states.findFactSources("knowledge", sharedEdge);

        assertEquals(2, factSources.size());
        assertEquals("doc-a", factSources.get(0).getEvidence().getDocumentId());
        assertEquals("doc-b", factSources.get(1).getEvidence().getDocumentId());
        assertEquals(sharedEdge, factSources.get(0).getEdgeKey());
        assertEquals(sharedEdge, factSources.get(1).getEdgeKey());
    }

    /**
     * 图写入失败时不得推进文档 revision 或提前注册模型识别的实体。
     */
    @Test
    public void shouldCommitStateAndEntityRegistryOnlyAfterSuccessfulGraphWrite() {
        Scenario scenario = scenario();
        scenario.writer.fail = true;

        GraphIngestionResult failed = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").batchId("batch-1").schemaVersion("v1").build(), scenario.writer);

        assertFalse(failed.isSuccess());
        assertNull(scenario.states.get("knowledge", "doc-1"));
        assertEquals(0, scenario.registry.size());
        String operationId = failed.getPlan().getNextState().getOperationId();
        assertEquals(GraphIngestionOperation.Stage.FAILED,
            scenario.operations.get(operationId).getStage());
        scenario.writer.fail = false;
        GraphIngestionResult retried = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").batchId("batch-1").schemaVersion("v1").build(), scenario.writer);
        assertTrue(retried.isSuccess());
        assertEquals(2, scenario.registry.size());
        assertEquals("batch-1", scenario.states.get("knowledge", "doc-1").getBatchId());
        assertEquals("v1", scenario.states.get("knowledge", "doc-1").getSchemaVersion());
        assertEquals(GraphIngestionOperation.Stage.COMPLETED,
            scenario.operations.get(operationId).getStage());
    }

    /**
     * 两个基于同一 revision 的计划中，后执行者必须在再次写库前被拒绝。
     */
    @Test
    public void shouldRejectStalePlanBeforeSecondGraphWrite() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);
        GraphIngestionPlan first = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").build());
        GraphIngestionPlan stale = scenario.service.plan(Document.of("林默返回青云会"), schema(),
            request("doc-1").build());
        scenario.service.execute(first, scenario.writer);
        int callsBeforeStaleExecution = scenario.writer.calls;

        try {
            scenario.service.execute(stale, scenario.writer);
            fail("stale plan should be rejected");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("plan is stale"));
        }
        assertEquals(callsBeforeStaleExecution, scenario.writer.calls);
    }

    /**
     * 默认 KEEP 策略应报告过期关系，但不能生成破坏性删除。
     */
    @Test
    public void shouldKeepStaleRelationsByDefault() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);

        GraphIngestionPlan plan = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").build());

        assertEquals(1, plan.getStaleEdgeKeys().size());
        assertTrue(plan.getMutation().getDeleteEdgeKeys().isEmpty());
    }

    /**
     * 相同正文但 Schema 版本变化时必须重新抽取，不能被内容哈希短路。
     */
    @Test
    public void shouldReextractWhenSchemaVersionChanges() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").schemaVersion("v1").build(), scenario.writer);
        GraphIngestionResult second = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").schemaVersion("v2").build(), scenario.writer);

        assertEquals(GraphIngestionPlan.Status.READY, second.getPlan().getStatus());
        assertEquals(2, scenario.extractor.calls.get());
        assertEquals(2L, scenario.states.get("knowledge", "doc-1").getRevision());
    }

    /**
     * 创建包含注册表解析器、内存状态和记录写入器的测试场景。
     */
    private static Scenario scenario() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        RecordingExtractor extractor = new RecordingExtractor();
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor,
            (document, idGenerator) -> Collections.singletonList(document),
            new SchemaGraphCandidateValidator(), new RegistryGraphEntityResolver(registry),
            new GraphMutationMapper());
        InMemoryGraphDocumentStateStore states = new InMemoryGraphDocumentStateStore();
        InMemoryGraphIngestionOperationStore operations = new InMemoryGraphIngestionOperationStore();
        LocalGraphIngestionLockProvider locks = new LocalGraphIngestionLockProvider();
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        GraphIngestionService service = new GraphIngestionService(
            pipeline, states, registry, operations, locks, clock::getAndIncrement);
        return new Scenario(service, pipeline, extractor, states, registry, operations, locks, clock,
            new RecordingWriter());
    }

    /**
     * 创建目标 Space 固定为 knowledge 的请求构造器。
     */
    private static GraphIngestionRequest.Builder request(String documentId) {
        return GraphIngestionRequest.builder("knowledge", documentId);
    }

    /** 创建用于操作存储排序测试的最小记录。 */
    private static GraphIngestionOperation operation(String id, GraphIngestionOperation.Stage stage, long time) {
        return new GraphIngestionOperation(id, "knowledge", "doc-" + id, 0L, "fingerprint-" + id,
            stage, time, "");
    }

    /**
     * 创建人物、组织和隶属关系 Schema。
     */
    private static GraphSchema schema() {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true);
        return GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Character", name))
            .nodeType(GraphSchema.NodeType.of("Organization", name))
            .edgeType(GraphSchema.EdgeType.of("MEMBER_OF", "Character", "Organization"))
            .build();
    }

    /**
     * 根据输入文本生成确定候选，模拟模型而不掩盖流水线行为。
     */
    private static final class RecordingExtractor implements GraphExtractor {
        /**
         * 模型调用次数。
         */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public GraphCandidateResult extract(GraphExtractionRequest request) {
            calls.incrementAndGet();
            if (request.getText().contains("失败")) throw new GraphExtractionException("synthetic chunk failure");
            GraphEntityCandidate person = entity(request, "p", "林默", "Character");
            if (!request.getText().contains("青云会")) {
                return new GraphCandidateResult(Collections.singletonList(person), Collections.emptyList(),
                    Collections.emptyList(), "{}");
            }
            GraphEntityCandidate organization = entity(request, "o", "青云会", "Organization");
            GraphRelationCandidate relation = new GraphRelationCandidate(person.getCandidateKey(), "MEMBER_OF",
                organization.getCandidateKey(), 0L, Collections.<String, Object>emptyMap(),
                evidence(request, request.getText()), 1D, GraphAssertionType.EXPLICIT);
            return new GraphCandidateResult(Arrays.asList(person, organization), Collections.singletonList(relation),
                Collections.emptyList(), "{}");
        }

        /**
         * 创建当前 Chunk 作用域的实体候选。
         */
        private static GraphEntityCandidate entity(GraphExtractionRequest request, String id, String name, String type) {
            return new GraphEntityCandidate(request.getChunkId() + "::" + id, name, type,
                Collections.<String>emptyList(), props("name", name), evidence(request, name), 1D);
        }

        /**
         * 创建与请求父文档和 Chunk 一致的证据。
         */
        private static GraphEvidence evidence(GraphExtractionRequest request, String quote) {
            return new GraphEvidence(request.getDocumentId(), request.getChunkId(), quote, -1, -1,
                request.getMetadata());
        }
    }

    /**
     * 记录调用和最近 mutation，并可返回确定失败的 GraphWriter。
     */
    private static final class RecordingWriter implements GraphWriter {
        /**
         * 写入调用次数。
         */
        private int calls;
        /**
         * 是否模拟写入失败。
         */
        private boolean fail;
        /**
         * 最近一次收到的 mutation。
         */
        private GraphMutation lastMutation;

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            calls++;
            lastMutation = mutation;
            if (fail) return GraphWriteResult.failure("write failed", new IllegalStateException("write failed"));
            return GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size()
                + mutation.getDeleteEdgeKeys().size());
        }

        @Override
        public com.agentsflex.graph.importing.GraphImportReport importData(
            com.agentsflex.graph.importing.GraphImportRequest request, GraphOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 要求指定数量调用同时到达的写入器，用于识别不必要的服务级串行化。
     */
    private static final class ConcurrentWriter implements GraphWriter {
        /** 预期同时进入写入器的调用。 */
        private final CountDownLatch entered;
        /** 实际调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        private ConcurrentWriter(int parties) {
            entered = new CountDownLatch(parties);
        }

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!entered.await(3, TimeUnit.SECONDS)) {
                    return GraphWriteResult.failure("concurrent writer timeout", null);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return GraphWriteResult.failure("concurrent writer interrupted", exception);
            }
            return GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size());
        }

        /** @return 两个文档是否都在超时前进入写入器。 */
        private boolean awaitAll() throws InterruptedException {
            return entered.await(3, TimeUnit.SECONDS);
        }

        @Override
        public com.agentsflex.graph.importing.GraphImportReport importData(
            com.agentsflex.graph.importing.GraphImportRequest request, GraphOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 聚合测试依赖。
     */
    private static final class Scenario {
        /**
         * 被测服务。
         */
        private final GraphIngestionService service;
        /** 可复用于模拟进程重启的抽取流水线。 */
        private final GraphExtractionPipeline pipeline;
        /**
         * 记录模型调用的 extractor。
         */
        private final RecordingExtractor extractor;
        /**
         * 内存文档状态。
         */
        private final InMemoryGraphDocumentStateStore states;
        /**
         * 内存实体注册表。
         */
        private final InMemoryGraphEntityRegistry registry;
        /**
         * 内存导入操作状态。
         */
        private final InMemoryGraphIngestionOperationStore operations;
        /** 多个服务实例共享的文档锁。 */
        private final LocalGraphIngestionLockProvider locks;
        /** 跨服务实例递增的测试时钟。 */
        private final AtomicLong clock;
        /**
         * 记录图写入的 writer。
         */
        private final RecordingWriter writer;

        private Scenario(GraphIngestionService service, GraphExtractionPipeline pipeline,
                         RecordingExtractor extractor,
                         InMemoryGraphDocumentStateStore states, InMemoryGraphEntityRegistry registry,
                         InMemoryGraphIngestionOperationStore operations,
                         LocalGraphIngestionLockProvider locks, AtomicLong clock, RecordingWriter writer) {
            this.service = service;
            this.pipeline = pipeline;
            this.extractor = extractor;
            this.states = states;
            this.registry = registry;
            this.operations = operations;
            this.locks = locks;
            this.clock = clock;
            this.writer = writer;
        }
    }

    /**
     * 创建单属性有序映射。
     */
    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
