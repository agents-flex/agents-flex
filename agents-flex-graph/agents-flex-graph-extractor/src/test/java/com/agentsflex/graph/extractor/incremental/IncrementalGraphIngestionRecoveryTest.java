package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.core.document.Document;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.GraphCandidateBatch;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.GraphExtractor;
import com.agentsflex.graph.extractor.GraphMutationMapper;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.GraphRegisteredEntity;
import com.agentsflex.graph.extractor.resolution.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 增量导入跨图写入、实体注册、状态提交和操作日志的故障恢复矩阵。
 *
 * <p>测试逐一在边界处注入一次性失败，随后通过同一 operationId 恢复。这样可以证明恢复使用首次
 * 冻结的计划，不会再次调用非确定性模型，也可以明确哪些窗口会触发图写入重放。</p>
 */
public class IncrementalGraphIngestionRecoveryTest {
    /**
     * GraphWriter 抛异常时操作进入 FAILED，恢复后重新写图并完成提交。
     */
    @Test
    public void writerExceptionShouldBeRecoverableWithoutReextracting() {
        Scenario scenario = scenario();
        scenario.writer.throwNext = true;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("writer-failure"),
                scenario.writer);
            fail("writer exception should escape to caller");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("writer"));
        }
        assertStage(scenario, "writer-failure", GraphIngestionOperation.Stage.FAILED);
        assertNull(scenario.states.get("knowledge", "doc-1"));

        IncrementalGraphIngestionResult recovered = scenario.service.resume("writer-failure", scenario.writer);

        assertTrue(recovered.isSuccess());
        assertEquals(2, scenario.writer.calls.get());
        assertEquals(1, scenario.extractor.calls.get());
        assertStage(scenario, "writer-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 图成功后若 GRAPH_APPLIED 日志 CAS 失败，恢复允许至少一次重放并最终收敛。
     */
    @Test
    public void graphAppliedJournalFailureShouldReplayIdempotentMutation() {
        Scenario scenario = scenario();
        scenario.operations.failNextTransitionTo = GraphIngestionOperation.Stage.GRAPH_APPLIED;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("journal-failure"),
                scenario.writer);
            fail("operation journal CAS failure should be visible");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("state changed concurrently"));
        }
        assertEquals(1, scenario.writer.calls.get());
        assertStage(scenario, "journal-failure", GraphIngestionOperation.Stage.PREPARED);

        assertTrue(scenario.service.resume("journal-failure", scenario.writer).isSuccess());
        assertEquals(2, scenario.writer.calls.get());
        assertEquals(1, scenario.extractor.calls.get());
        assertStage(scenario, "journal-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 实体注册失败发生在 GRAPH_APPLIED 后，恢复不得再次调用 GraphWriter。
     */
    @Test
    public void registryFailureShouldResumeAfterGraphApplied() {
        Scenario scenario = scenario();
        scenario.registry.failNextSave = true;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("registry-failure"),
                scenario.writer);
            fail("registry failure should escape to caller");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("registry"));
        }
        assertStage(scenario, "registry-failure", GraphIngestionOperation.Stage.GRAPH_APPLIED);
        assertNull(scenario.states.get("knowledge", "doc-1"));

        assertTrue(scenario.service.resume("registry-failure", scenario.writer).isSuccess());
        assertEquals(1, scenario.writer.calls.get());
        assertEquals(2, scenario.registry.saveCalls.get());
        assertStage(scenario, "registry-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 文档 CAS 的瞬时失败应保留 GRAPH_APPLIED，恢复时跳过图写入并再次提交状态。
     */
    @Test
    public void transientStateCasFailureShouldResumeWithoutGraphReplay() {
        Scenario scenario = scenario();
        scenario.states.rejectNextCompareAndSet = true;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("state-cas-failure"),
                scenario.writer);
            fail("state CAS failure should be visible");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("document state changed concurrently"));
        }
        assertStage(scenario, "state-cas-failure", GraphIngestionOperation.Stage.GRAPH_APPLIED);

        assertTrue(scenario.service.resume("state-cas-failure", scenario.writer).isSuccess());
        assertEquals(1, scenario.writer.calls.get());
        assertNotNull(scenario.states.get("knowledge", "doc-1"));
        assertStage(scenario, "state-cas-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 当前状态已 CAS 成功但历史快照失败时，恢复只补历史和日志。
     */
    @Test
    public void historyFailureShouldCompleteFromAlreadyCommittedState() {
        Scenario scenario = scenario();
        scenario.states.failNextRecordVersion = true;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("history-failure"),
                scenario.writer);
            fail("history failure should escape to caller");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("history"));
        }
        assertNotNull(scenario.states.get("knowledge", "doc-1"));
        assertTrue(scenario.states.listVersions("knowledge", "doc-1").isEmpty());
        assertStage(scenario, "history-failure", GraphIngestionOperation.Stage.GRAPH_APPLIED);

        assertTrue(scenario.service.resume("history-failure", scenario.writer).isSuccess());
        assertEquals(1, scenario.writer.calls.get());
        assertEquals(1, scenario.states.listVersions("knowledge", "doc-1").size());
        assertStage(scenario, "history-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 状态已提交但 STATE_COMMITTED 日志 CAS 失败时，恢复不得重复图写入。
     */
    @Test
    public void stateCommittedJournalFailureShouldOnlyRepairOperationLog() {
        Scenario scenario = scenario();
        scenario.operations.failNextTransitionTo = GraphIngestionOperation.Stage.STATE_COMMITTED;
        try {
            scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("commit-log-failure"),
                scenario.writer);
            fail("operation journal failure should be visible");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("state changed concurrently"));
        }
        assertNotNull(scenario.states.get("knowledge", "doc-1"));
        assertStage(scenario, "commit-log-failure", GraphIngestionOperation.Stage.GRAPH_APPLIED);

        assertTrue(scenario.service.resume("commit-log-failure", scenario.writer).isSuccess());
        assertEquals(1, scenario.writer.calls.get());
        assertStage(scenario, "commit-log-failure", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * PREPARED 操作可在首次调用 GraphWriter 前崩溃，并由新服务实例从冻结计划恢复。
     */
    @Test
    public void preparedPlanShouldResumeAfterServiceRestart() {
        Scenario scenario = scenario();
        IncrementalGraphIngestionPlan plan = scenario.service.plan(Document.of("林默加入青云会"), schema(),
            request("prepared-restart"));
        GraphIngestionOperation prepared = new GraphIngestionOperation("prepared-restart", "knowledge", "doc-1",
            0L, GraphIngestionPlanFingerprint.compute(plan), GraphIngestionOperation.Stage.PREPARED, 10L, "");
        assertTrue(scenario.operations.createIfAbsent(prepared, plan));
        IncrementalGraphIngestionService restarted = scenario.newService();

        assertTrue(restarted.resume("prepared-restart", scenario.writer).isSuccess());
        assertEquals(1, scenario.writer.calls.get());
        assertEquals(1, scenario.extractor.calls.get());
        assertStage(scenario, "prepared-restart", GraphIngestionOperation.Stage.COMPLETED);
    }

    /**
     * 已完成操作重复恢复必须是零图写入的幂等操作。
     */
    @Test
    public void completedOperationResumeShouldBeNoOp() {
        Scenario scenario = scenario();
        assertTrue(scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("completed"),
            scenario.writer).isSuccess());
        int calls = scenario.writer.calls.get();

        assertTrue(scenario.service.resume("completed", scenario.writer).isSuccess());
        assertEquals(calls, scenario.writer.calls.get());
        assertEquals(1, scenario.states.listVersions("knowledge", "doc-1").size());
    }

    /**
     * 旧存储只保存 operation 而没有 plan 时，恢复必须给出可诊断错误。
     */
    @Test
    public void resumeShouldRejectOperationWithoutPersistedPlan() {
        Scenario scenario = scenario();
        scenario.operations.createIfAbsent(new GraphIngestionOperation("legacy", "knowledge", "doc-1", 0L,
            "fingerprint", GraphIngestionOperation.Stage.PREPARED, 1L, ""));
        try {
            scenario.service.resume("legacy", scenario.writer);
            fail("missing persisted plan must be rejected");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("plan was not found"));
        }
    }

    /**
     * 同一 Service 中两个相同导入并发时，文档锁应让后进入者走 UNCHANGED 快速路径。
     */
    @Test
    public void concurrentSameDocumentIngestionShouldWriteExactlyOnce() throws Exception {
        Scenario scenario = scenario();
        assertConcurrentSameDocument(scenario.service, scenario.service, scenario);
    }

    /**
     * 两个 Service 共享锁和状态存储时也必须保持同文档串行。
     */
    @Test
    public void sharedLockShouldSerializeSameDocumentAcrossServiceInstances() throws Exception {
        Scenario scenario = scenario();
        assertConcurrentSameDocument(scenario.service, scenario.newService(), scenario);
    }

    /**
     * 并发启动两个相同请求并验证只有一个写图。
     */
    private static void assertConcurrentSameDocument(IncrementalGraphIngestionService firstService,
                                                     IncrementalGraphIngestionService secondService,
                                                     Scenario scenario) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        scenario.writer.delayMillis = 100L;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IncrementalGraphIngestionResult> first = executor.submit(() -> ingestAfterBarrier(firstService,
                "same-document", scenario.writer, ready, start));
            Future<IncrementalGraphIngestionResult> second = executor.submit(() -> ingestAfterBarrier(secondService,
                "same-document", scenario.writer, ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(second.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, scenario.writer.calls.get());
            assertEquals(1, scenario.extractor.calls.get());
            assertEquals(1L, scenario.states.get("knowledge", "doc-1").getRevision());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 等待统一起跑信号后导入固定文档。
     */
    private static IncrementalGraphIngestionResult ingestAfterBarrier(IncrementalGraphIngestionService service,
                                                                      String operationId, CountingWriter writer,
                                                                      CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return service.ingest(Document.of("林默加入青云会"), schema(), request(operationId), writer);
    }

    /**
     * 创建具有全部可注入边界的恢复测试场景。
     */
    private static Scenario scenario() {
        CountingExtractor extractor = new CountingExtractor();
        FaultingRegistry registry = new FaultingRegistry();
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor,
            (document, idGenerator) -> Collections.singletonList(document), new SchemaGraphCandidateValidator(),
            new RegistryGraphEntityResolver(registry), new GraphMutationMapper());
        FaultingStateStore states = new FaultingStateStore();
        FaultingOperationStore operations = new FaultingOperationStore();
        LocalGraphIngestionLockProvider locks = new LocalGraphIngestionLockProvider();
        AtomicLong clock = new AtomicLong(100L);
        IncrementalGraphIngestionService service = new IncrementalGraphIngestionService(pipeline, states, registry,
            operations, locks, clock::getAndIncrement);
        return new Scenario(service, pipeline, extractor, states, registry, operations, locks, clock,
            new CountingWriter());
    }

    /**
     * 创建当前测试使用的固定 Schema。
     */
    private static GraphSchema schema() {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true);
        return GraphSchema.builder().nodeType(GraphSchema.NodeType.of("Character", name))
            .nodeType(GraphSchema.NodeType.of("Organization", name))
            .edgeType(GraphSchema.EdgeType.of("MEMBER_OF", "Character", "Organization")).build();
    }

    /**
     * 创建具有稳定 operationId 的导入请求。
     */
    private static IncrementalGraphIngestionRequest request(String operationId) {
        return IncrementalGraphIngestionRequest.builder("knowledge", "doc-1").operationId(operationId).build();
    }

    /**
     * 断言操作日志已经推进到预期阶段。
     */
    private static void assertStage(Scenario scenario, String operationId, GraphIngestionOperation.Stage stage) {
        assertEquals(stage, scenario.operations.get(operationId).getStage());
    }

    /**
     * 产生稳定人物、组织和关系候选的计数 Extractor。
     */
    private static final class CountingExtractor implements GraphExtractor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public GraphCandidateBatch extract(GraphExtractionRequest request) {
            calls.incrementAndGet();
            GraphEvidence evidence = new GraphEvidence(request.getDocumentId(), request.getChunkId(), request.getText(),
                -1, -1, Collections.<String, Object>emptyMap());
            GraphEntityCandidate person = new GraphEntityCandidate(request.getChunkId() + "::p", "林默",
                "Character", Collections.<String>emptyList(), Collections.<String, Object>singletonMap("name", "林默"),
                evidence, 1D);
            GraphEntityCandidate organization = new GraphEntityCandidate(request.getChunkId() + "::o", "青云会",
                "Organization", Collections.<String>emptyList(),
                Collections.<String, Object>singletonMap("name", "青云会"), evidence, 1D);
            GraphRelationCandidate relation = new GraphRelationCandidate(person.getCandidateKey(), "MEMBER_OF",
                organization.getCandidateKey(), 0L, Collections.<String, Object>emptyMap(), evidence, 1D,
                GraphAssertionType.EXPLICIT);
            return new GraphCandidateBatch(java.util.Arrays.asList(person, organization),
                Collections.singletonList(relation), Collections.emptyList(), "{}");
        }
    }

    /**
     * 记录图写次数，并支持一次性异常和延迟。
     */
    private static final class CountingWriter implements GraphWriter {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean throwNext;
        private volatile long delayMillis;

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            calls.incrementAndGet();
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("synthetic writer failure");
            }
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("writer interrupted", exception);
                }
            }
            return GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size());
        }

        @Override
        public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 在内存注册表前注入一次性保存失败。
     */
    private static final class FaultingRegistry implements GraphEntityRegistry {
        private final InMemoryGraphEntityRegistry delegate = new InMemoryGraphEntityRegistry();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private volatile boolean failNextSave;

        @Override
        public List<GraphRegisteredEntity> find(String space, String type, Collection<String> names) {
            return delegate.find(space, type, names);
        }

        @Override
        public List<GraphRegisteredEntity> find(String type, Collection<String> names) {
            return delegate.find(type, names);
        }

        @Override
        public void saveAll(String space, Collection<GraphRegisteredEntity> entities) {
            saveCalls.incrementAndGet();
            if (failNextSave) {
                failNextSave = false;
                throw new IllegalStateException("synthetic registry failure");
            }
            delegate.saveAll(space, entities);
        }

        @Override
        public void saveAll(Collection<GraphRegisteredEntity> entities) {
            delegate.saveAll(entities);
        }
    }

    /**
     * 在文档状态 CAS 和历史写入边界注入一次性失败。
     */
    private static final class FaultingStateStore implements GraphDocumentStateStore {
        private final InMemoryGraphDocumentStateStore delegate = new InMemoryGraphDocumentStateStore();
        private volatile boolean rejectNextCompareAndSet;
        private volatile boolean failNextRecordVersion;

        @Override
        public GraphDocumentState get(String space, String documentId) {
            return delegate.get(space, documentId);
        }

        @Override
        public List<GraphDocumentState> list(String space) {
            return delegate.list(space);
        }

        @Override
        public boolean compareAndSet(String space, String documentId, long expectedRevision,
                                     GraphDocumentState newState) {
            if (rejectNextCompareAndSet) {
                rejectNextCompareAndSet = false;
                return false;
            }
            return delegate.compareAndSet(space, documentId, expectedRevision, newState);
        }

        @Override
        public boolean remove(String space, String documentId, long expectedRevision) {
            return delegate.remove(space, documentId, expectedRevision);
        }

        @Override
        public void recordVersion(GraphDocumentState state) {
            if (failNextRecordVersion) {
                failNextRecordVersion = false;
                throw new IllegalStateException("synthetic history failure");
            }
            delegate.recordVersion(state);
        }

        @Override
        public List<GraphDocumentState> listVersions(String space, String documentId) {
            return delegate.listVersions(space, documentId);
        }
    }

    /**
     * 在指定目标阶段的日志 CAS 上注入一次性失败。
     */
    private static final class FaultingOperationStore implements GraphIngestionOperationStore {
        private final InMemoryGraphIngestionOperationStore delegate = new InMemoryGraphIngestionOperationStore();
        private volatile GraphIngestionOperation.Stage failNextTransitionTo;

        @Override
        public GraphIngestionOperation get(String operationId) {
            return delegate.get(operationId);
        }

        @Override
        public boolean createIfAbsent(GraphIngestionOperation operation) {
            return delegate.createIfAbsent(operation);
        }

        @Override
        public boolean createIfAbsent(GraphIngestionOperation operation, IncrementalGraphIngestionPlan plan) {
            return delegate.createIfAbsent(operation, plan);
        }

        @Override
        public boolean compareAndSet(String operationId, GraphIngestionOperation.Stage expected,
                                     GraphIngestionOperation next) {
            if (next.getStage() == failNextTransitionTo) {
                failNextTransitionTo = null;
                return false;
            }
            return delegate.compareAndSet(operationId, expected, next);
        }

        @Override
        public IncrementalGraphIngestionPlan getPlan(String operationId) {
            return delegate.getPlan(operationId);
        }

        @Override
        public List<GraphIngestionOperation> listRecoverable(int limit) {
            return delegate.listRecoverable(limit);
        }
    }

    /**
     * 聚合恢复测试共享组件，并可创建模拟重启后的 Service。
     */
    private static final class Scenario {
        private final IncrementalGraphIngestionService service;
        private final GraphExtractionPipeline pipeline;
        private final CountingExtractor extractor;
        private final FaultingStateStore states;
        private final FaultingRegistry registry;
        private final FaultingOperationStore operations;
        private final LocalGraphIngestionLockProvider locks;
        private final AtomicLong clock;
        private final CountingWriter writer;

        private Scenario(IncrementalGraphIngestionService service, GraphExtractionPipeline pipeline,
                         CountingExtractor extractor, FaultingStateStore states, FaultingRegistry registry,
                         FaultingOperationStore operations, LocalGraphIngestionLockProvider locks, AtomicLong clock,
                         CountingWriter writer) {
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

        private IncrementalGraphIngestionService newService() {
            return new IncrementalGraphIngestionService(pipeline, states, registry, operations, locks,
                clock::getAndIncrement);
        }
    }
}
