package com.agentsflex.graph.extractor.review;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentState;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionPlan;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionResult;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionService;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphDocumentStateStore;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphIngestionOperationStore;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionRequest;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.core.document.Document;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 审核任务创建、查询、决策、执行和版本冲突测试。
 */
public class GraphReviewServiceTest {
    @Test
    public void shouldSubmitGetAndFilterPendingTasks() {
        Scenario scenario = scenario();
        GraphReviewTask first = scenario.reviews.submit(plan("operation-1", "doc-a", "node-a"));
        scenario.reviews.submit(plan("operation-2", "doc-b", "node-b"));

        assertNotNull(scenario.reviews.get(first.getTaskId()));
        assertEquals(GraphReviewTaskStatus.PENDING_REVIEW, first.getStatus());
        assertEquals(0L, first.getReviewVersion());
        List<GraphReviewTask> tasks = scenario.reviews.list(GraphReviewTaskQuery.builder()
            .space("knowledge").documentId("doc-a")
            .status(GraphReviewTaskStatus.PENDING_REVIEW).build());
        assertEquals(1, tasks.size());
        assertEquals(first.getTaskId(), tasks.get(0).getTaskId());
    }

    @Test
    public void shouldAcceptTaskAndExecuteFrozenPlan() {
        Scenario scenario = scenario();
        GraphReviewTask task = scenario.reviews.submit(plan("operation-accept", "doc-accept", "node-accept"));

        GraphReviewExecutionResult result = scenario.reviews.accept(
            task.getTaskId(), task.getReviewVersion(), scenario.writer, "reviewer-a");

        assertTrue(result.isSuccess());
        assertEquals(GraphReviewTaskStatus.COMPLETED, result.getTask().getStatus());
        assertEquals(2L, result.getTask().getReviewVersion());
        assertEquals("reviewer-a", result.getTask().getActor());
        assertEquals(1, scenario.writer.calls);
        assertNotNull(scenario.states.get("knowledge", "doc-accept"));

        // 重复提交同一个版本不会再次调用 GraphWriter。
        GraphReviewExecutionResult repeated = scenario.reviews.accept(
            task.getTaskId(), result.getTask().getReviewVersion(), scenario.writer);
        assertTrue(repeated.isSuccess());
        assertEquals(1, scenario.writer.calls);
    }

    @Test
    public void shouldRejectWithoutExecutingPlan() {
        Scenario scenario = scenario();
        GraphReviewTask task = scenario.reviews.submit(plan("operation-reject", "doc-reject", "node-reject"));

        GraphReviewTask rejected = scenario.reviews.reject(
            task.getTaskId(), 0L, "evidence is insufficient", "reviewer-b");

        assertEquals(GraphReviewTaskStatus.REJECTED, rejected.getStatus());
        assertEquals("evidence is insufficient", rejected.getReason());
        assertEquals(0, scenario.writer.calls);
        assertNull(scenario.states.get("knowledge", "doc-reject"));
        try {
            scenario.reviews.accept(task.getTaskId(), rejected.getReviewVersion(), scenario.writer);
            fail("rejected task must not be executed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not actionable"));
        }
    }

    @Test
    public void shouldUpdatePlanAndRejectStaleReviewVersion() {
        Scenario scenario = scenario();
        GraphReviewTask task = scenario.reviews.submit(plan("operation-update", "doc-update", "node-old"));
        GraphReviewTask updated = scenario.reviews.updatePlan(task.getTaskId(), 0L,
            plan("operation-update", "doc-update", "node-new"), "resolved entity", "reviewer-c");

        assertEquals(GraphReviewTaskStatus.CHANGES_REQUESTED, updated.getStatus());
        assertEquals("node-new", updated.getPlan().getMutation().getNodes().get(0).getId());
        try {
            scenario.reviews.reject(task.getTaskId(), 0L, "stale decision");
            fail("stale reviewVersion must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("version conflict"));
        }
    }

    @Test
    public void shouldRecordFailedExecutionAndRecoverBeforeArchive() {
        Scenario scenario = scenario();
        GraphReviewTask task = scenario.reviews.submit(plan("operation-failed", "doc-failed", "node-failed"));
        RecordingWriter failing = new RecordingWriter(false);

        GraphReviewExecutionResult failed = scenario.reviews.accept(task.getTaskId(), 0L, failing);
        assertFalse(failed.isSuccess());
        assertEquals(GraphReviewTaskStatus.FAILED, failed.getTask().getStatus());

        try {
            scenario.reviews.archive(task.getTaskId(), failed.getTask().getReviewVersion());
            fail("potentially partial write must be recovered before archive");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("archived")); }
        GraphReviewExecutionResult recovered = scenario.reviews.resume(task.getTaskId(),
            failed.getTask().getReviewVersion(), scenario.writer);
        assertTrue(recovered.isSuccess());
        GraphReviewTask archived = scenario.reviews.archive(task.getTaskId(), recovered.getTask().getReviewVersion());
        assertEquals(GraphReviewTaskStatus.ARCHIVED, archived.getStatus());
    }

    @Test
    public void shouldApplyPatchWithoutCallerRebuildingPlan() {
        Scenario scenario = scenario();
        GraphIngestionPlan plan = extractedPlan(scenario, "doc-patch");
        GraphReviewTask task = scenario.reviews.submit(plan);
        String key = plan.getExtractionResult().getValidatedEntities().get(0).getCandidateKey();
        GraphReviewPatch patch = GraphReviewPatch.builder()
            .entityProperties(key, Collections.singletonMap("name", "corrected"))
            .relationProperties(0, Collections.singletonMap("since", 2024L))
            .build();

        GraphReviewTask updated = scenario.reviews.applyPatch(task.getTaskId(), 0L, patch, "corrected property");

        assertEquals(GraphReviewTaskStatus.CHANGES_REQUESTED, updated.getStatus());
        assertEquals("corrected", updated.getPlan().getMutation().getNodes().get(0).getProperties().get("name"));
        assertEquals(2, updated.getPlan().getNextState().getNodeIds().size());
        assertEquals("corrected", updated.getPlan().getEntityRegistrations().get(0).getProperties().get("name"));
        assertEquals(2024L, updated.getPlan().getNextState().getFactSources().get(0).getProperties().get("since"));
        assertEquals("Alice", updated.getPlan().getExtractionResult().getAllEntities().get(0).getProperties().get("name"));
    }

    @Test
    public void shouldRestoreTaskWithoutResettingVersion() {
        GraphReviewTask original = GraphReviewTask.pending("persisted-task", plan("operation-restore", "doc-restore", "node"), 10L);
        GraphReviewTask restored = GraphReviewTask.restore(original.getTaskId(), original.getPlan(),
            GraphReviewTaskStatus.CHANGES_REQUESTED, 7L, "needs review", "operator", 10L, 20L);
        assertEquals("persisted-task", restored.getTaskId());
        assertEquals(7L, restored.getReviewVersion());
        assertEquals(GraphReviewTaskStatus.CHANGES_REQUESTED, restored.getStatus());
        assertEquals("operator", restored.getActor());
    }

    @Test
    public void automaticAcceptanceShouldNotRequireReviewStore() {
        // 整个测试不创建 GraphReviewStore 或 GraphReviewService。
        AtomicLong extractionCalls = new AtomicLong();
        InMemoryGraphDocumentStateStore states = new InMemoryGraphDocumentStateStore();
        GraphIngestionService ingestion = new GraphIngestionService(new GraphExtractionPipeline(request -> {
            extractionCalls.incrementAndGet();
            GraphEvidence evidence = new GraphEvidence(request.getDocumentId(), request.getChunkId(),
                request.getText(), 0, request.getText().length(), Collections.emptyMap());
            GraphEntityCandidate candidate = new GraphEntityCandidate(request.getChunkId() + "::alice",
                "Alice", "Person", Collections.emptyList(), Collections.singletonMap("name", "Alice"),
                evidence, 0.99);
            return new GraphCandidateResult(Collections.singletonList(candidate), Collections.emptyList(),
                Collections.emptyList(), "original");
        }), states);
        GraphSchema schema = GraphSchema.builder().nodeType(GraphSchema.NodeType.of("Person",
            new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true))).build();
        GraphIngestionRequest request = GraphIngestionRequest.builder("knowledge", "doc-auto").build();
        RecordingWriter writer = new RecordingWriter(true);
        GraphIngestionResult result = ingestion.ingest(Document.of("Alice"), schema, request, writer);

        assertTrue(result.isSuccess());
        assertEquals(1, writer.calls);
        assertEquals(1, result.getPlan().getMutation().getNodes().size());
        assertEquals(1L, states.get("knowledge", "doc-auto").getRevision());
        // 同一文档再次导入由入图服务判重，无需引入审核任务机制。
        GraphIngestionResult repeated = ingestion.ingest(Document.of("Alice"), schema, request, writer);
        assertTrue(repeated.isSuccess());
        assertEquals(GraphIngestionPlan.Status.UNCHANGED, repeated.getPlan().getStatus());
        assertEquals(1L, extractionCalls.get());
        assertEquals(1, writer.calls);
    }

    @Test
    public void shouldRejectUnchangedPlanSubmission() {
        Scenario scenario = scenario();
        GraphIngestionPlan unchanged = GraphIngestionPlan.restore(GraphIngestionPlan.Status.UNCHANGED,
            "knowledge", "doc-unchanged", GraphOptions.ofSpace("knowledge"), null, null, null,
            GraphMutation.builder().build(), Collections.<GraphEdgeKey>emptySet(), Collections.emptyList());
        try {
            scenario.reviews.submit(unchanged);
            fail("unchanged plan must not produce a review task");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("does not require review"));
        }
    }

    private static Scenario scenario() {
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(request -> {
            GraphEvidence evidence = new GraphEvidence(request.getDocumentId(), request.getChunkId(), request.getText(),
                0, request.getText().length(), Collections.emptyMap());
            String a = request.getChunkId() + "::a", b = request.getChunkId() + "::b";
            return new GraphCandidateResult(Arrays.asList(
                new GraphEntityCandidate(a, "Alice", "Person", Collections.emptyList(),
                    Collections.singletonMap("name", "Alice"), evidence, 0.99),
                new GraphEntityCandidate(b, "Bob", "Person", Collections.emptyList(),
                    Collections.singletonMap("name", "Bob"), evidence, 0.99)), Collections.singletonList(
                new GraphRelationCandidate(a, "KNOWS", b, 0L, Collections.singletonMap("since", 2020L),
                    evidence, 0.99, GraphAssertionType.EXPLICIT)), Collections.emptyList(), "original");
        });
        InMemoryGraphDocumentStateStore states = new InMemoryGraphDocumentStateStore();
        GraphIngestionService ingestion = new GraphIngestionService(pipeline, states, null,
            new InMemoryGraphIngestionOperationStore());
        AtomicLong clock = new AtomicLong(100L);
        InMemoryGraphReviewStore store = new InMemoryGraphReviewStore();
        GraphReviewService reviews = new GraphReviewService(store, ingestion, clock::incrementAndGet);
        return new Scenario(ingestion, reviews, states, new RecordingWriter(true));
    }

    private static GraphIngestionPlan extractedPlan(Scenario scenario, String documentId) {
        GraphSchema schema = GraphSchema.builder().nodeType(GraphSchema.NodeType.of("Person",
            new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true)))
            .edgeType(GraphSchema.EdgeType.of("KNOWS", "Person", "Person",
                new GraphSchema.Property("since", GraphSchema.PropertyType.INT64, false))).build();
        return scenario.ingestion.plan(Document.of("Alice knows Bob"), schema,
            GraphIngestionRequest.builder("knowledge", documentId).build());
    }

    @Test
    public void shouldRejectRelationAndKeepAllLayersConsistent() {
        Scenario s = scenario();
        GraphReviewTask task = s.reviews.submit(extractedPlan(s, "reject-edge"));
        GraphReviewTask patched = s.reviews.applyPatch(task.getTaskId(), 0L,
            GraphReviewPatch.builder().rejectRelation(0).build(), "unsupported");
        assertTrue(patched.getPlan().getMutation().getEdges().isEmpty());
        assertTrue(patched.getPlan().getMutation().getDeleteEdgeKeys().isEmpty());
        assertTrue(patched.getPlan().getNextState().getEdgeKeys().isEmpty());
        assertTrue(patched.getPlan().getNextState().getFactSources().isEmpty());
        assertEquals(1, patched.getPlan().getExtractionResult().getAllRelations().size());
        assertTrue(s.reviews.accept(task.getTaskId(), 1L, s.writer).isSuccess());
    }

    @Test
    public void shouldResolveEntityAndRebuildFactSourcesAndRegistry() {
        Scenario s = scenario();
        GraphIngestionPlan plan = extractedPlan(s, "resolve");
        GraphReviewTask task = s.reviews.submit(plan);
        String key = plan.getExtractionResult().getValidatedEntities().get(0).getCandidateKey();
        GraphReviewTask patched = s.reviews.applyPatch(task.getTaskId(), 0L, GraphReviewPatch.builder()
            .resolveEntity(key, GraphNode.builder("existing-alice", "Person").property("name", "Alice").build())
            .build(), "matched identity");
        assertEquals("existing-alice", patched.getPlan().getMutation().getEdges().get(0).getSourceId());
        assertEquals("existing-alice", patched.getPlan().getNextState().getFactSources().get(0).getEdgeKey().getSourceId());
        assertEquals("existing-alice", patched.getPlan().getEntityRegistrations().get(0).getNodeId());
        assertEquals(2, patched.getPlan().getMutation().getNodes().size());
    }

    @Test
    public void shouldRejectEntityAndRemoveItsRelationsWithoutDeletingSharedNode() {
        Scenario s = scenario();
        GraphIngestionPlan plan = extractedPlan(s, "reject-entity");
        GraphReviewTask task = s.reviews.submit(plan);
        GraphReviewTask patched = s.reviews.applyPatch(task.getTaskId(), 0L, GraphReviewPatch.builder()
            .rejectEntity(plan.getExtractionResult().getValidatedEntities().get(0).getCandidateKey()).build(), "wrong entity");
        assertEquals(1, patched.getPlan().getMutation().getNodes().size());
        assertTrue(patched.getPlan().getMutation().getEdges().isEmpty());
        assertTrue(patched.getPlan().getMutation().getDeleteNodeIds().isEmpty());
        assertEquals(1, patched.getPlan().getEntityRegistrations().size());
    }

    @Test
    public void shouldRejectInvalidPatchWithoutChangingTask() {
        Scenario s = scenario();
        GraphIngestionPlan plan = extractedPlan(s, "bad-patch");
        GraphReviewTask task = s.reviews.submit(plan);
        String key = plan.getExtractionResult().getValidatedEntities().get(0).getCandidateKey();
        List<GraphReviewPatch> invalid = Arrays.asList(
            GraphReviewPatch.builder().rejectRelation(99).build(),
            GraphReviewPatch.builder().rejectEntity("missing").build(),
            GraphReviewPatch.builder().entityProperties(key, Collections.singletonMap("name", 1)).build(),
            GraphReviewPatch.builder().entityProperties(key, Collections.emptyMap()).build(),
            GraphReviewPatch.builder().resolveEntity(key, GraphNode.builder("x", "Company").build()).build(),
            GraphReviewPatch.builder().rejectEntity(key).entityProperties(key, Collections.singletonMap("name", "A")).build(),
            GraphReviewPatch.builder().rejectRelation(0).relationProperties(0, Collections.emptyMap()).build());
        for (GraphReviewPatch patch : invalid) {
            try { s.reviews.applyPatch(task.getTaskId(), 0L, patch, "bad"); fail("invalid patch must fail"); }
            catch (IllegalArgumentException expected) { assertEquals(0L, s.reviews.get(task.getTaskId()).getReviewVersion()); }
        }
    }

    @Test
    public void shouldClaimConcurrentAcceptanceBeforeCallingWriter() throws Exception {
        Scenario s = scenario();
        GraphReviewTask task = s.reviews.submit(plan("race", "race", "node"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RecordingWriter writer = new RecordingWriter(true) {
            @Override public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return super.mutate(mutation, options);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GraphReviewExecutionResult> first = pool.submit(() -> s.reviews.accept(task.getTaskId(), 0L, writer));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { s.reviews.accept(task.getTaskId(), 0L, writer); fail("concurrent decision must fail"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("version conflict")); }
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, writer.calls);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    public void shouldRecoverWhenGraphCommittedButReviewStoreFailed() {
        Scenario s = scenario();
        InMemoryGraphReviewStore delegate = new InMemoryGraphReviewStore();
        GraphReviewStore failing = new GraphReviewStore() {
            private boolean failOnce = true;
            public GraphReviewTask create(GraphReviewTask task) { return delegate.create(task); }
            public GraphReviewTask get(String id) { return delegate.get(id); }
            public List<GraphReviewTask> list(GraphReviewTaskQuery query) { return delegate.list(query); }
            public GraphReviewTask update(GraphReviewTask task, long version) {
                if (task.getStatus() == GraphReviewTaskStatus.COMPLETED && failOnce) {
                    failOnce = false;
                    throw new IllegalStateException("review database unavailable");
                }
                return delegate.update(task, version);
            }
        };
        GraphReviewService reviews = new GraphReviewService(failing, s.ingestion);
        GraphReviewTask task = reviews.submit(plan("review-save-failure", "review-save-failure", "node"));
        try { reviews.accept(task.getTaskId(), 0L, s.writer); fail("persistence must fail once"); }
        catch (IllegalStateException expected) { assertEquals("review database unavailable", expected.getMessage()); }
        assertNotNull(s.states.get("knowledge", "review-save-failure"));
        assertEquals(GraphReviewTaskStatus.EXECUTING, reviews.get(task.getTaskId()).getStatus());
        // 新建审核服务模拟重启，恢复时不能重复写图。
        GraphReviewExecutionResult resumed = new GraphReviewService(failing, s.ingestion)
            .resume(task.getTaskId(), 1L, s.writer);
        assertTrue(resumed.isSuccess());
        assertEquals(1, s.writer.calls);
        assertEquals(GraphReviewTaskStatus.COMPLETED, resumed.getTask().getStatus());
    }

    @Test
    public void shouldValidateWriterBeforeClaimingTask() {
        Scenario s = scenario();
        GraphReviewTask task = s.reviews.submit(plan("null-writer", "null-writer", "node"));
        try { s.reviews.accept(task.getTaskId(), 0L, null); fail("writer is mandatory"); }
        catch (IllegalArgumentException expected) { assertEquals(0L, s.reviews.get(task.getTaskId()).getReviewVersion()); }
    }

    @Test
    public void shouldReturnStablePagesAndRejectStoreVersionConflicts() {
        InMemoryGraphReviewStore store = new InMemoryGraphReviewStore();
        GraphReviewTask b = GraphReviewTask.pending("b", plan("b", "b", "node"), 10L);
        GraphReviewTask a = GraphReviewTask.pending("a", plan("a", "a", "node"), 10L);
        store.create(b); store.create(a);
        assertEquals("a", store.list(GraphReviewTaskQuery.builder().limit(1).build()).get(0).getTaskId());
        assertEquals("b", store.list(GraphReviewTaskQuery.builder().offset(1).limit(Integer.MAX_VALUE).build()).get(0).getTaskId());
        GraphReviewTask next = a.transition(GraphReviewTaskStatus.CHANGES_REQUESTED, null, "change", "user", 20L);
        store.update(next, 0L);
        try { store.update(next, 0L); fail("stale update must fail"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("version conflict")); }
        try { store.create(a); fail("duplicate ID must fail"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("already exists")); }
        assertTrue(store.list(GraphReviewTaskQuery.builder().offset(Integer.MAX_VALUE).build()).isEmpty());
    }

    private static GraphIngestionPlan plan(String operationId, String documentId, String nodeId) {
        GraphMutation mutation = GraphMutation.builder().operationId(operationId)
            .upsertNode(GraphNode.builder(nodeId, "Person").property("name", nodeId).build()).build();
        GraphDocumentState next = GraphDocumentState.builder("knowledge", documentId, "hash-" + nodeId)
            .revision(1L).operationId(operationId).documentVersion("v1").schemaVersion("1.0")
            .extractionFingerprint("extractor-v1").committedAtMillis(10L).nodeId(nodeId).build();
        return GraphIngestionPlan.restore(GraphIngestionPlan.Status.READY, "knowledge", documentId,
            GraphOptions.ofSpace("knowledge"), null, next, null, mutation,
            Collections.<GraphEdgeKey>emptySet(), Collections.emptyList());
    }

    private static class RecordingWriter implements GraphWriter {
        private final boolean success;
        private int calls;

        private RecordingWriter(boolean success) {
            this.success = success;
        }

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            calls++;
            return success ? GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size())
                : GraphWriteResult.failure("write rejected", null);
        }

        @Override
        public com.agentsflex.graph.importing.GraphImportReport importData(
            com.agentsflex.graph.importing.GraphImportRequest request, GraphOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Scenario {
        private final GraphIngestionService ingestion;
        private final GraphReviewService reviews;
        private final InMemoryGraphDocumentStateStore states;
        private final RecordingWriter writer;

        private Scenario(GraphIngestionService ingestion, GraphReviewService reviews,
                         InMemoryGraphDocumentStateStore states, RecordingWriter writer) {
            this.ingestion = ingestion;
            this.reviews = reviews;
            this.states = states;
            this.writer = writer;
        }
    }
}
