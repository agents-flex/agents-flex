package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEvidence;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link InMemoryGraphDocumentStateStore} 的乐观锁、历史版本和来源查询契约测试。
 *
 * <p>这些断言也是持久化 {@link GraphDocumentStateStore} 实现应复用的行为规范：当前状态只能通过
 * revision CAS 推进，历史记录必须幂等，Space 之间不得串读来源。</p>
 */
public class InMemoryGraphDocumentStateStoreTest {
    /**
     * 两个并发调用只能有一个从 revision 0 提交成功。
     */
    @Test
    public void concurrentInitialCompareAndSetShouldHaveSingleWinner() throws Exception {
        InMemoryGraphDocumentStateStore store = new InMemoryGraphDocumentStateStore();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> compareAfterBarrier(store, state("space", "doc", 1,
                "operation-a", GraphDocumentState.Status.ACTIVE, null), ready, start));
            Future<Boolean> second = executor.submit(() -> compareAfterBarrier(store, state("space", "doc", 1,
                "operation-b", GraphDocumentState.Status.ACTIVE, null), ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            int winners = (first.get(2, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(2, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1L, store.get("space", "doc").getRevision());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * CAS 必须同时校验存储键、期望 revision 和新 revision。
     */
    @Test
    public void compareAndSetShouldRejectWrongIdentityAndRevision() {
        InMemoryGraphDocumentStateStore store = new InMemoryGraphDocumentStateStore();
        assertTrue(store.compareAndSet("space", "doc", 0L,
            state("space", "doc", 1, "operation-1", GraphDocumentState.Status.ACTIVE, null)));
        assertFalse(store.compareAndSet("space", "doc", 0L,
            state("space", "doc", 2, "operation-2", GraphDocumentState.Status.ACTIVE, null)));
        assertFalse(store.compareAndSet("space", "doc", 1L,
            state("space", "doc", 3, "operation-3", GraphDocumentState.Status.ACTIVE, null)));
        try {
            store.compareAndSet("space", "doc", 1L,
                state("other", "doc", 2, "operation-2", GraphDocumentState.Status.ACTIVE, null));
            fail("storage key mismatch must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("identity"));
        }
    }

    /**
     * 历史快照按 revision 幂等保存，返回集合不能被调用方篡改。
     */
    @Test
    public void versionHistoryShouldBeIdempotentAndReadOnly() {
        InMemoryGraphDocumentStateStore store = new InMemoryGraphDocumentStateStore();
        GraphDocumentState first = state("space", "doc", 1, "operation-1",
            GraphDocumentState.Status.ACTIVE, edge());
        GraphDocumentState second = state("space", "doc", 2, "operation-2",
            GraphDocumentState.Status.ACTIVE, edge());
        store.recordVersion(first);
        store.recordVersion(first);
        store.recordVersion(second);

        List<GraphDocumentState> versions = store.listVersions("space", "doc");
        assertEquals(2, versions.size());
        assertEquals(1L, versions.get(0).getRevision());
        assertEquals(2L, versions.get(1).getRevision());
        try {
            versions.clear();
            fail("version history must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读集合符合契约。
        }
    }

    /**
     * 当前来源只统计活动文档，历史来源仍应包含已经撤回的旧版本。
     */
    @Test
    public void provenanceShouldSeparateActiveAndHistoricalVersions() {
        InMemoryGraphDocumentStateStore store = new InMemoryGraphDocumentStateStore();
        GraphEdgeKey edge = edge();
        GraphDocumentState active = state("space", "doc", 1, "operation-1",
            GraphDocumentState.Status.ACTIVE, edge);
        GraphDocumentState retracted = state("space", "doc", 2, "operation-2",
            GraphDocumentState.Status.RETRACTED, edge);
        assertTrue(store.compareAndSet("space", "doc", 0L, active));
        store.recordVersion(active);
        assertTrue(store.compareAndSet("space", "doc", 1L, retracted));
        store.recordVersion(retracted);

        assertTrue(store.findProvenance("space", edge).isEmpty());
        assertEquals(2, store.findHistoricalProvenance("space", edge).size());
        assertTrue(store.findProvenance("other", edge).isEmpty());
    }

    /**
     * remove 必须使用 revision 保护，错误版本不能删除较新的状态。
     */
    @Test
    public void removeShouldBeRevisionProtected() {
        InMemoryGraphDocumentStateStore store = new InMemoryGraphDocumentStateStore();
        store.compareAndSet("space", "doc", 0L,
            state("space", "doc", 1, "operation-1", GraphDocumentState.Status.ACTIVE, null));

        assertFalse(store.remove("space", "doc", 2L));
        assertTrue(store.remove("space", "doc", 1L));
        assertNull(store.get("space", "doc"));
        assertFalse(store.remove("space", "doc", 1L));
    }

    /**
     * 在统一起跑线后执行首次 CAS，放大并发竞争窗口。
     */
    private static boolean compareAfterBarrier(InMemoryGraphDocumentStateStore store, GraphDocumentState state,
                                               CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return store.compareAndSet(state.getSpace(), state.getDocumentId(), 0L, state);
    }

    /**
     * 创建包含可选事实来源的最小文档状态。
     */
    private static GraphDocumentState state(String space, String documentId, long revision, String operationId,
                                            GraphDocumentState.Status status, GraphEdgeKey edge) {
        GraphDocumentState.Builder builder = GraphDocumentState.builder(space, documentId, "hash-" + revision)
            .revision(revision).operationId(operationId).status(status).documentVersion("v" + revision)
            .schemaVersion("schema-v1").extractionFingerprint("extractor-v1").committedAtMillis(revision);
        if (edge != null) {
            GraphEvidence evidence = new GraphEvidence(documentId, "chunk-1", "林默加入青云会", -1, -1,
                Collections.<String, Object>emptyMap());
            builder.edgeKeys(Collections.singleton(edge)).factProvenances(Collections.singletonList(
                new GraphFactProvenance("fact-" + revision, operationId, revision, revision, edge, evidence,
                    1D, GraphAssertionType.EXPLICIT, Collections.<String, Object>emptyMap())));
        }
        return builder.build();
    }

    /**
     * 创建测试复用的稳定关系键。
     */
    private static GraphEdgeKey edge() {
        return new GraphEdgeKey("person-1", "MEMBER_OF", "organization-1", 0L);
    }
}
