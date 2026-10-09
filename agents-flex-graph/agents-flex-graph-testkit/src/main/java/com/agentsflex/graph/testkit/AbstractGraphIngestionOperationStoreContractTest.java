package com.agentsflex.graph.testkit;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentState;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionOperation;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionOperationStore;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionPlan;
import com.agentsflex.graph.mutation.GraphMutation;
import org.junit.Before;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 支持跨进程恢复的 {@link GraphIngestionOperationStore} 公共契约测试。
 *
 * <p>该契约要求实现显式声明支持恢复，并覆盖计划的原子保存、读取及待恢复操作扫描。
 * 内存实现可以验证相同 API 契约，跨进程恢复仍要求可靠的持久化存储。</p>
 */
public abstract class AbstractGraphIngestionOperationStoreContractTest {
    /**
     * 当前测试使用的空操作存储。
     */
    protected GraphIngestionOperationStore store;

    /**
     * 创建无历史记录的恢复型操作存储。
     */
    protected abstract GraphIngestionOperationStore createStore();

    /**
     * 每个契约测试重新创建存储。
     */
    @Before
    public void setUpGraphIngestionOperationStoreContract() {
        store = createStore();
        if (store == null) throw new IllegalStateException("createStore must not return null");
        assertTrue("recovery contract requires explicit capability", store.isRecoverySupported());
    }

    /**
     * operation 与原始 plan 必须原子首次创建，重复 ID 不得覆盖。
     */
    @Test
    public void operationAndPlanMustBeCreatedAtomically() {
        GraphIngestionOperation first = operation("operation-1", "doc-1", 10L);
        GraphIngestionPlan firstPlan = plan("operation-1", "doc-1");
        assertTrue(store.createIfAbsent(first, firstPlan));
        assertFalse(store.createIfAbsent(operation("operation-1", "doc-2", 20L),
            plan("operation-1", "doc-2")));
        assertEquals("doc-1", store.get("operation-1").getDocumentId());
        assertNotNull(store.getPlan("operation-1"));
        assertEquals("doc-1", store.getPlan("operation-1").getDocumentId());
    }

    /**
     * 并发创建相同 operationId 时只能有一个首次创建者。
     */
    @Test
    public void concurrentOperationCreationMustHaveSingleWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> createAfterBarrier("doc-a", ready, start));
            Future<Boolean> second = executor.submit(() -> createAfterBarrier("doc-b", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
            assertNotNull(store.getPlan("operation-concurrent"));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 阶段 CAS 必须同时校验期望阶段和不可变操作身份。
     */
    @Test
    public void stageCompareAndSetMustProtectExpectedStageAndIdentity() {
        GraphIngestionOperation prepared = operation("operation-1", "doc-1", 10L);
        store.createIfAbsent(prepared, plan("operation-1", "doc-1"));
        GraphIngestionOperation applied = prepared.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 11L, "");
        assertFalse(store.compareAndSet("operation-1", GraphIngestionOperation.Stage.FAILED, applied));
        assertTrue(store.compareAndSet("operation-1", GraphIngestionOperation.Stage.PREPARED, applied));
        assertEquals(GraphIngestionOperation.Stage.GRAPH_APPLIED, store.get("operation-1").getStage());
    }

    /**
     * 恢复扫描必须排除完成项，并按更新时间及 operationId 稳定排序。
     */
    @Test
    public void recoverableScanMustBeStableBoundedAndReadOnly() {
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
            fail("recoverable scan must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读集合满足公共契约。
        }
    }

    /**
     * 创建处于 PREPARED 阶段的操作记录。
     */
    protected static GraphIngestionOperation operation(String operationId, String documentId, long time) {
        return new GraphIngestionOperation(operationId, "space_a", documentId, 0L, "fingerprint",
            GraphIngestionOperation.Stage.PREPARED, time, "");
    }

    /**
     * 创建与操作号和文档绑定的最小恢复计划。
     */
    protected static GraphIngestionPlan plan(String operationId, String documentId) {
        GraphDocumentState next = GraphDocumentState.builder("space_a", documentId, "hash")
            .revision(1L).operationId(operationId).build();
        return GraphIngestionPlan.restore(GraphIngestionPlan.Type.INGESTION, "space_a",
            documentId, GraphOptions.ofSpace("space_a"), null, next, null,
            GraphMutation.builder().operationId(operationId).build(), Collections.emptySet(),
            Collections.emptyList());
    }

    /**
     * 等待统一起跑线后原子创建操作及计划。
     */
    private boolean createAfterBarrier(String documentId, CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return store.createIfAbsent(operation("operation-concurrent", documentId, 10L),
            plan("operation-concurrent", documentId));
    }
}
