package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.mutation.GraphMutation;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryGraphIngestionOperationStore} 的原子创建、CAS 和恢复扫描契约测试。
 */
public class InMemoryGraphIngestionOperationStoreTest {
    /**
     * operation 与 plan 必须原子首次创建，重复操作号不能覆盖原计划。
     */
    @Test
    public void createWithPlanShouldBeAtomicAndFirstWriteWins() {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        GraphIngestionPlan firstPlan = plan("operation-1", "doc-1");
        GraphIngestionPlan secondPlan = plan("operation-1", "doc-2");

        assertTrue(store.createIfAbsent(operation("operation-1", "doc-1", 10L), firstPlan));
        assertFalse(store.createIfAbsent(operation("operation-1", "doc-2", 20L), secondPlan));
        assertSame(firstPlan, store.getPlan("operation-1"));
        assertEquals("doc-1", store.get("operation-1").getDocumentId());
    }

    /**
     * 操作与计划缺一不可，失败不能留下部分记录。
     */
    @Test
    public void atomicCreationShouldRequirePlan() {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        assertTrue(store.isRecoverySupported());
        try {
            store.createIfAbsent(operation("operation-1", "doc-1", 10L), null);
            fail("plan must be required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("plan"));
        }
        assertNull(store.get("operation-1"));
        assertNull(store.getPlan("operation-1"));
    }

    /**
     * 多线程竞争同一个 operationId 时只能有一个创建者。
     */
    @Test
    public void concurrentCreateShouldHaveSingleWinner() throws Exception {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> createAfterBarrier(store, "doc-a", ready, start));
            Future<Boolean> second = executor.submit(() -> createAfterBarrier(store, "doc-b", ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(2, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(2, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * CAS 不得接受错误阶段或被篡改的操作身份。
     */
    @Test
    public void compareAndSetShouldProtectStageAndIdentity() {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        GraphIngestionOperation prepared = operation("operation-1", "doc-1", 10L);
        store.createIfAbsent(prepared, plan("operation-1", "doc-1"));
        GraphIngestionOperation applied = prepared.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 11L, "");

        assertFalse(store.compareAndSet("operation-1", GraphIngestionOperation.Stage.FAILED, applied));
        GraphIngestionOperation wrongIdentity = new GraphIngestionOperation("operation-1", "space", "doc-2", 0L,
            "fingerprint", GraphIngestionOperation.Stage.GRAPH_APPLIED, 11L, "");
        assertFalse(store.compareAndSet("operation-1", GraphIngestionOperation.Stage.PREPARED, wrongIdentity));
        assertTrue(store.compareAndSet("operation-1", GraphIngestionOperation.Stage.PREPARED, applied));
    }

    /**
     * 恢复扫描必须稳定排序、应用 limit、排除完成项并返回只读集合。
     */
    @Test
    public void recoverableScanShouldBeStableBoundedAndReadOnly() {
        InMemoryGraphIngestionOperationStore store = new InMemoryGraphIngestionOperationStore();
        store.createIfAbsent(operation("b", "doc-b", 10L), plan("b", "doc-b"));
        store.createIfAbsent(operation("a", "doc-a", 10L), plan("a", "doc-a"));
        GraphIngestionOperation completed = operation("completed", "doc-c", 1L)
            .transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 2L, "")
            .transition(GraphIngestionOperation.Stage.STATE_COMMITTED, 3L, "")
            .transition(GraphIngestionOperation.Stage.COMPLETED, 4L, "");
        store.createIfAbsent(completed, plan("completed", "doc-c"));

        List<GraphIngestionOperation> values = store.listRecoverableOperations(1);
        assertEquals(1, values.size());
        assertEquals("a", values.get(0).getOperationId());
        try {
            values.clear();
            fail("recoverable list must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读集合符合契约。
        }
        try {
            store.listRecoverableOperations(0);
            fail("non-positive limit must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive"));
        }
    }

    /**
     * 验证状态机的完整合法路径、失败重试和时间单调约束。
     */
    @Test
    public void operationStateMachineShouldAllowOnlyDocumentedTransitions() {
        GraphIngestionOperation prepared = operation("operation-1", "doc-1", 10L);
        GraphIngestionOperation failed = prepared.transition(GraphIngestionOperation.Stage.FAILED, 11L, "timeout");
        assertEquals("timeout", failed.getFailureMessage());
        GraphIngestionOperation retry = failed.transition(GraphIngestionOperation.Stage.PREPARED, 12L, "");
        GraphIngestionOperation applied = retry.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 13L, "");
        GraphIngestionOperation committed = applied.transition(GraphIngestionOperation.Stage.STATE_COMMITTED, 14L, "");
        GraphIngestionOperation completed = committed.transition(GraphIngestionOperation.Stage.COMPLETED, 15L, "");
        assertEquals(GraphIngestionOperation.Stage.COMPLETED, completed.getStage());
        try {
            completed.transition(GraphIngestionOperation.Stage.PREPARED, 16L, "");
            fail("completed operation must be terminal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("COMPLETED"));
        }
        try {
            prepared.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 9L, "");
            fail("operation time must not move backwards");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("backwards"));
        }
    }

    /**
     * 在统一起跑线后创建相同操作号。
     */
    private static boolean createAfterBarrier(InMemoryGraphIngestionOperationStore store, String documentId,
                                              CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return store.createIfAbsent(operation("shared", documentId, 10L), plan("shared", documentId));
    }

    /**
     * 创建处于 PREPARED 的最小操作记录。
     */
    private static GraphIngestionOperation operation(String operationId, String documentId, long time) {
        return new GraphIngestionOperation(operationId, "space", documentId, 0L, "fingerprint",
            GraphIngestionOperation.Stage.PREPARED, time, "");
    }

    /**
     * 创建可由恢复日志保存的最小 INGESTION 计划。
     */
    private static GraphIngestionPlan plan(String operationId, String documentId) {
        GraphDocumentState next = GraphDocumentState.builder("space", documentId, "hash")
            .revision(1L).operationId(operationId).build();
        return GraphIngestionPlan.restore(GraphIngestionPlan.Type.INGESTION, "space", documentId,
            GraphOptions.ofSpace("space"), null, next, null,
            GraphMutation.builder().operationId(operationId).build(), Collections.emptySet(),
            Collections.emptyList());
    }
}
