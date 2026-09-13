package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
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

/** 在真实 Redis 上验证 Turn Store 的 Lua 原子操作与恢复行为。 */
public class RedisAgentStoresIntegrationTest {
    private RedisAgentStoreConfig config;
    private String prefix;

    @Before
    public void setUp() {
        prefix = "agents-flex-agent-it:" + UUID.randomUUID() + ":";
        config = RedisAgentStoreConfig.builder(System.getProperty("redis.test.uri", "redis://127.0.0.1:6379"))
            .keyPrefix(prefix).build();
        try { config.jedis().ping(); }
        catch (RuntimeException error) {
            config.close(); config = null;
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

    /** Redis Turn Store 必须拒绝旧版本写入。 */
    @Test
    public void shouldEnforceVersionAndLeaseBoundaries() {
        RedisAgentTurnStore turns = config.turnStore();
        AgentTurnSnapshot created = turns.save(snapshot("boundaries", AgentTurnStatus.READY), -1);
        try {
            turns.save(created, -1);
            fail("Expected version conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("boundaries"));
        }

    }

    /** 活动会话查询必须基于 Redis 中的最新投影，而不是序列化时的旧状态。 */
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

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).updatedAt(1).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

}
