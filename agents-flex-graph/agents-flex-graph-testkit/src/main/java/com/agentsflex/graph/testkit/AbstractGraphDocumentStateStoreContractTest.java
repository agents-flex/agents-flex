package com.agentsflex.graph.testkit;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentState;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentStateStore;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link GraphDocumentStateStore} 生产实现可继承的公共契约测试。
 *
 * <p>子类只需返回一个相互隔离的空存储实例。关系数据库实现应在每个测试前清空测试表，Redis
 * 实现应使用唯一 key 前缀，从而确保 CAS、Space 隔离和历史幂等断言可以重复运行。</p>
 */
public abstract class AbstractGraphDocumentStateStoreContractTest {
    /**
     * 当前测试使用的全新存储实例。
     */
    protected GraphDocumentStateStore store;

    /**
     * 创建一个不含历史数据的被测实现。
     */
    protected abstract GraphDocumentStateStore createStore();

    /**
     * 每个测试都使用新的逻辑存储。
     */
    @Before
    public void setUpGraphDocumentStateStoreContract() {
        store = createStore();
        if (store == null) throw new IllegalStateException("createStore must not return null");
    }

    /**
     * 首次提交和后续 revision 必须严格通过 CAS 单步推进。
     */
    @Test
    public void compareAndSetMustAdvanceExactlyOneRevision() {
        GraphDocumentState first = state("space_a", "doc-1", 1L, "operation-1",
            GraphDocumentState.Status.ACTIVE);
        GraphDocumentState second = state("space_a", "doc-1", 2L, "operation-2",
            GraphDocumentState.Status.ACTIVE);
        assertTrue(store.compareAndSet("space_a", "doc-1", 0L, first));
        assertFalse(store.compareAndSet("space_a", "doc-1", 0L, second));
        assertTrue(store.compareAndSet("space_a", "doc-1", 1L, second));
        assertEquals(2L, store.findCurrent("space_a", "doc-1").getRevision());
    }

    /**
     * 两个并发首次提交只能有一个 CAS 获胜。
     */
    @Test
    public void concurrentInitialCompareAndSetMustHaveSingleWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> compareAfterBarrier(
                state("space_a", "doc-concurrent", 1L, "operation-a", GraphDocumentState.Status.ACTIVE),
                ready, start));
            Future<Boolean> second = executor.submit(() -> compareAfterBarrier(
                state("space_a", "doc-concurrent", 1L, "operation-b", GraphDocumentState.Status.ACTIVE),
                ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 相同 documentId 在不同 Space 中必须拥有独立状态。
     */
    @Test
    public void stateMustBeIsolatedBySpace() {
        store.compareAndSet("space_a", "doc-1", 0L,
            state("space_a", "doc-1", 1L, "operation-a", GraphDocumentState.Status.ACTIVE));
        store.compareAndSet("space_b", "doc-1", 0L,
            state("space_b", "doc-1", 1L, "operation-b", GraphDocumentState.Status.ACTIVE));
        assertEquals("operation-a", store.findCurrent("space_a", "doc-1").getOperationId());
        assertEquals("operation-b", store.findCurrent("space_b", "doc-1").getOperationId());
        assertEquals(1, store.list("space_a").size());
    }

    /**
     * 历史写入必须按 revision 幂等，返回集合不得允许调用方修改。
     */
    @Test
    public void versionHistoryMustBeIdempotentAndReadOnly() {
        GraphDocumentState first = state("space_a", "doc-1", 1L, "operation-1",
            GraphDocumentState.Status.ACTIVE);
        store.recordVersion(first);
        store.recordVersion(first);
        List<GraphDocumentState> versions = store.listVersions("space_a", "doc-1");
        assertEquals(1, versions.size());
        try {
            versions.clear();
            fail("version history must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读集合满足公共契约。
        }
    }

    /**
     * remove 必须校验 revision，错误版本不得删除当前状态。
     */
    @Test
    public void removeMustBeRevisionProtected() {
        store.compareAndSet("space_a", "doc-1", 0L,
            state("space_a", "doc-1", 1L, "operation-1", GraphDocumentState.Status.ACTIVE));
        assertFalse(store.remove("space_a", "doc-1", 2L));
        assertTrue(store.remove("space_a", "doc-1", 1L));
        assertNull(store.findCurrent("space_a", "doc-1"));
    }

    /**
     * 共享边引用判断只统计同一 Space 中其他活动文档。
     */
    @Test
    public void edgeReferenceLookupMustIgnoreRetractedAndOtherSpaceDocuments() {
        GraphEdgeKey edge = new GraphEdgeKey("a", "REL", "b", 0L);
        store.compareAndSet("space_a", "active", 0L,
            state("space_a", "active", 1L, "operation-a", GraphDocumentState.Status.ACTIVE, edge));
        store.compareAndSet("space_a", "retracted", 0L,
            state("space_a", "retracted", 1L, "operation-r", GraphDocumentState.Status.RETRACTED, edge));
        store.compareAndSet("space_b", "other", 0L,
            state("space_b", "other", 1L, "operation-b", GraphDocumentState.Status.ACTIVE, edge));

        assertTrue(store.isReferencedByOtherDocument("space_a", "missing", edge));
        assertFalse(store.isReferencedByOtherDocument("space_a", "active", edge));
    }

    /**
     * 创建不含关系的最小合法状态。
     */
    protected static GraphDocumentState state(String space, String documentId, long revision, String operationId,
                                              GraphDocumentState.Status status) {
        return state(space, documentId, revision, operationId, status, null);
    }

    /**
     * 创建可选包含关系键的最小合法状态。
     */
    protected static GraphDocumentState state(String space, String documentId, long revision, String operationId,
                                              GraphDocumentState.Status status, GraphEdgeKey edge) {
        GraphDocumentState.Builder builder = GraphDocumentState.builder(space, documentId, "hash-" + revision)
            .revision(revision).operationId(operationId).status(status).committedAtMillis(revision);
        if (edge != null) builder.edgeKey(edge);
        return builder.build();
    }

    /**
     * 等待统一起跑线后提交状态，用于放大实现中的 CAS 竞争窗口。
     */
    private boolean compareAfterBarrier(GraphDocumentState state, CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return store.compareAndSet(state.getSpace(), state.getDocumentId(), 0L, state);
    }
}
