package com.agentsflex.agent.context;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * 验证 Usage 不可变状态转换及进程内 CAS Store 的一致性语义。
 */
public class AgentContextUsageStateTest {

    /**
     * 验证初始、确认、压缩失效和 Usage 缺失失效的字段保留规则。
     */
    @Test
    public void emptyConfirmedInvalidatedAndUnavailableTransitionsMustPreserveAuditData() {
        AgentContextUsageState empty = AgentContextUsageState.empty("conversation", "scope");
        assertEquals(0, empty.getVersion());
        assertEquals(AgentContextUsageStatus.NO_CONFIRMED_USAGE, empty.getStatus());

        AgentContextUsageState confirmed = empty.confirmed(70, 10, 80, 80, true,
            AgentContextCompressionReason.THRESHOLD_REACHED, 100);
        assertEquals(1, confirmed.getVersion());
        assertEquals(AgentContextUsageStatus.CONFIRMED_BASELINE, confirmed.getStatus());
        assertTrue(confirmed.isCompressionRequired());
        assertEquals(AgentContextCompressionReason.THRESHOLD_REACHED, confirmed.getCompressionReason());
        assertEquals(100, confirmed.getConfirmedAt());

        AgentContextUsageState invalidated = confirmed.invalidated(
            AgentContextCompressionReason.MANUAL, 200);
        assertEquals(2, invalidated.getVersion());
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED, invalidated.getStatus());
        assertFalse(invalidated.isCompressionRequired());
        assertEquals(80, invalidated.getNextCallBaselineTokens());
        assertEquals(100, invalidated.getConfirmedAt());
        assertEquals(200, invalidated.getLastCompressionAt());
        assertEquals(AgentContextCompressionReason.MANUAL, invalidated.getCompressionReason());

        AgentContextUsageState unavailable = confirmed.usageUnavailable();
        assertEquals(2, unavailable.getVersion());
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED, unavailable.getStatus());
        assertNull(unavailable.getCompressionReason());
        assertEquals(0, unavailable.getLastCompressionAt());
    }

    /**
     * 验证未要求压缩时不会遗留误导性的压缩原因。
     */
    @Test
    public void confirmedWithoutCompressionMustDiscardReason() {
        AgentContextUsageState state = AgentContextUsageState.empty("c", "s")
            .confirmed(1, 2, 3, 3, false, AgentContextCompressionReason.MANUAL, 4);
        assertFalse(state.isCompressionRequired());
        assertNull(state.getCompressionReason());
    }

    /**
     * 验证状态构造器拒绝非法版本、标识、状态和负数 Token。
     */
    @Test
    public void constructorMustRejectInvalidIdentityCountersAndStatus() {
        assertInvalid(-1, "c", "s", AgentContextUsageStatus.NO_CONFIRMED_USAGE, 0);
        assertInvalid(0, null, "s", AgentContextUsageStatus.NO_CONFIRMED_USAGE, 0);
        assertInvalid(0, "c", null, AgentContextUsageStatus.NO_CONFIRMED_USAGE, 0);
        assertInvalid(0, "c", "s", null, 0);
        assertInvalid(0, "c", "s", AgentContextUsageStatus.NO_CONFIRMED_USAGE, -1);
    }

    /**
     * 验证不同模型作用域隔离，并拒绝旧版本和空参数写入。
     */
    @Test
    public void inMemoryStoreMustIsolateScopesAndRejectInvalidWrites() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        AgentContextUsageState a = AgentContextUsageState.empty("c", "a")
            .confirmed(1, 1, 2, 2, false, null, 1);
        AgentContextUsageState b = AgentContextUsageState.empty("c", "b")
            .confirmed(2, 2, 4, 4, false, null, 1);
        assertTrue(store.save(a, 0));
        assertTrue(store.save(b, 0));
        assertEquals(2, store.load("c", "a").getTotalTokens());
        assertEquals(4, store.load("c", "b").getTotalTokens());
        assertFalse(store.save(a.confirmed(3, 3, 6, 6, false, null, 2), 0));
        try {
            store.save(null, 0);
            fail("Expected null state validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("state"));
        }
        try {
            store.load(null, "a");
            fail("Expected key validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("conversationId"));
        }
    }

    /**
     * 验证两个并发首次写入者中严格只有一个 CAS 成功。
     */
    @Test
    public void concurrentCasMustAllowExactlyOneWinner() throws Exception {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> saveAfterBarrier(store, "a", ready, start));
            Future<Boolean> second = executor.submit(() -> saveAfterBarrier(store, "b", ready, start));
            ready.await();
            start.countDown();
            int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, winners);
            assertEquals(1, store.load("c", "s").getVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 等待并发屏障后尝试保存版本 1 状态。
     */
    private static boolean saveAfterBarrier(InMemoryAgentContextUsageStateStore store, String marker,
                                            CountDownLatch ready, CountDownLatch start) throws Exception {
        AgentContextUsageState next = AgentContextUsageState.empty("c", "s")
            .confirmed(marker.length(), 0, marker.length(), marker.length(), false, null, 1);
        ready.countDown();
        start.await();
        return store.save(next, 0);
    }

    /**
     * 断言一组构造参数会被状态对象拒绝。
     */
    private static void assertInvalid(long version, String conversation, String scope,
                                      AgentContextUsageStatus status, long prompt) {
        try {
            new AgentContextUsageState(version, conversation, scope, status, prompt, 0, 0, 0,
                false, null, 0, 0);
            fail("Expected invalid state");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid context usage state"));
        }
    }
}
