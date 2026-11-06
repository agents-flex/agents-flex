package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.context.AgentContextCompressionReason;
import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStateStore;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.core.message.AiMessage;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * 在真实 Redis 上验证 Turn Store 的 Lua 原子操作与恢复行为。
 */
public class RedisAgentStoresIntegrationTest {
    private RedisAgentStoreConfig config;
    private String prefix;

    @Before
    public void setUp() {
        prefix = "agents-flex-agent-it:" + UUID.randomUUID() + ":";
        config = RedisAgentStoreConfig.builder(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"))
            .keyPrefix(prefix).build();
        try {
            config.jedis().ping();
        } catch (RuntimeException error) {
            if (Boolean.getBoolean("redis.test.required")) {
                throw new IllegalStateException("Redis is required for real Agent Store tests", error);
            }
            config.close();
            config = null;
            Assume.assumeNoException("Redis is required for Agent Store integration tests", error);
        }
    }

    @After
    public void tearDown() {
        if (config == null) return;
        String cursor = "0";
        do {
            ScanResult<String> scan = config.jedis().scan(cursor, new ScanParams().match(prefix + "*").count(100));
            cursor = scan.getCursor();
            if (!scan.getResult().isEmpty()) config.jedis().del(scan.getResult().toArray(new String[0]));
        } while (!"0".equals(cursor));
        config.close();
    }

    @Test
    public void shouldPersistAndCoordinateAllAgentStateTypes() {
        RedisAgentTurnStore turns = config.turnStore();
        AgentTurnSnapshot turn = turns.save(snapshot(), -1);
        assertEquals(0, turn.getState().getVersion());
        assertTrue(turns.requestCancellation("turn-1"));
        assertTrue(turns.load("turn-1").getState().isCancellationRequested());
    }

    @Test
    public void shouldPersistCompressionStateWithRedisLuaCas() {
        AgentContextCompressionStateStore store = config.compressionStateStore();
        AgentContextCompressionState first = new AgentContextCompressionState(1,
            Arrays.asList(new AiMessage("summary-1")), "message-100");
        assertTrue(store.save("conversation-1", first, 0));
        assertEquals("summary-1", store.load("conversation-1").getSummaryMessages().get(0).getTextContent());

        AgentContextCompressionState second = new AgentContextCompressionState(2,
            Arrays.asList(new AiMessage("summary-2")), "message-200");
        assertFalse(store.save("conversation-1", second, 0));
        assertTrue(store.save("conversation-1", second, 1));
        assertEquals(2, store.load("conversation-1").getVersion());
        assertNull(store.load("missing-conversation"));
    }

    /**
     * 验证 Redis Lua CAS 持久化 Usage，并隔离不同模型作用域。
     */
    @Test
    public void shouldPersistContextUsageStateWithRedisLuaCas() {
        AgentContextUsageStateStore store = config.usageStateStore();
        AgentContextUsageState first = AgentContextUsageState.empty("conversation-1", "agent:1:model-a")
            .confirmed(80, 10, 90, 90, true,
                AgentContextCompressionReason.THRESHOLD_REACHED, 1);
        assertTrue(store.save(first, 0));
        assertFalse(store.save(first, 0));
        assertEquals(90, store.load("conversation-1", "agent:1:model-a")
            .getNextCallBaselineTokens());
        assertNull(store.load("conversation-1", "agent:1:model-b"));
    }

    /**
     * 验证真实 Redis 中全部 Usage 字段往返和并发 Lua CAS 单胜者语义。
     */
    @Test
    public void shouldRoundTripAllUsageFieldsAndAtomicallyRejectConcurrentWriters() throws Exception {
        final AgentContextUsageStateStore store = config.usageStateStore();
        AgentContextUsageState first = AgentContextUsageState.empty("conversation-fields", "agent:2:model")
            .confirmed(81, 9, 90, 90, true,
                AgentContextCompressionReason.THRESHOLD_REACHED, 1234);
        assertTrue(store.save(first, 0));
        final AgentContextUsageState loaded = store.load("conversation-fields", "agent:2:model");
        assertEquals(81, loaded.getPromptTokens());
        assertEquals(9, loaded.getCompletionTokens());
        assertEquals(90, loaded.getTotalTokens());
        assertEquals(90, loaded.getNextCallBaselineTokens());
        assertTrue(loaded.isCompressionRequired());
        assertEquals(AgentContextCompressionReason.THRESHOLD_REACHED, loaded.getCompressionReason());
        assertEquals(1234, loaded.getConfirmedAt());

        try {
            store.save(new AgentContextUsageState(3, "conversation-fields", "agent:2:model",
                loaded.getStatus(), 1, 1, 2, 2, false, null, 1, 0), 1);
            fail("Expected version jump validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid usage state save"));
        }
        try {
            store.load("conversation", " ");
            fail("Expected blank key validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be blank"));
        }

        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = executor.submit(() -> saveUsageAfterBarrier(store, loaded, 91, ready, start));
            Future<Boolean> b = executor.submit(() -> saveUsageAfterBarrier(store, loaded, 92, ready, start));
            ready.await();
            start.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
            assertEquals(2, store.load("conversation-fields", "agent:2:model").getVersion());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Redis Turn Store 必须拒绝旧版本写入。
     */
    @Test
    public void shouldEnforceVersionBoundaries() {
        RedisAgentTurnStore turns = config.turnStore();
        AgentTurnSnapshot created = turns.save(snapshot("boundaries", AgentTurnStatus.READY), -1);
        try {
            turns.save(created, -1);
            fail("Expected version conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("boundaries"));
        }

    }

    /**
     * 活动会话查询必须基于 Redis 中的最新投影，而不是序列化时的旧状态。
     */
    @Test
    public void shouldFindActiveTurnForRecovery() {
        RedisAgentTurnStore turns = config.turnStore();
        AgentTurnSnapshot activeSource = snapshot("active", AgentTurnStatus.READY);
        AgentTurnSnapshot active = activeSource.withState(activeSource.getState().toBuilder()
            .metadata(Collections.<String, Object>singletonMap(
                "agentsflex.conversationId", "conversation-1"))
            .build());
        AgentTurnSnapshot savedActive = turns.save(active, -1);
        assertEquals("active", turns.findActiveTurn("conversation-1").getState().getTurnId());
        turns.save(savedActive.withState(savedActive.getState().toBuilder()
            .status(AgentTurnStatus.COMPLETED).build()), savedActive.getState().getVersion());
        assertNull(turns.findActiveTurn("conversation-1"));

    }

    private AgentTurnSnapshot snapshot() {
        return snapshot("turn-1", AgentTurnStatus.READY);
    }

    private static boolean saveUsageAfterBarrier(AgentContextUsageStateStore store,
                                                 AgentContextUsageState current, long total,
                                                 CountDownLatch ready, CountDownLatch start) throws Exception {
        AgentContextUsageState next = current.confirmed(total, 0, total, total, false, null, total);
        ready.countDown();
        start.await();
        return store.save(next, current.getVersion());
    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).updatedAt(1).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

}
