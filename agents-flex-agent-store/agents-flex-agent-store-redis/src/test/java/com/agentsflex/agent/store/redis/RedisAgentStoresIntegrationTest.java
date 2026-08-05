package com.agentsflex.agent.store.redis;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.store.ParentChildTurnSnapshots;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
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
        AgentTurnSnapshot claimedTurn = turns.claimRunnable("worker", 100, 1000, 1).get(0);
        assertEquals("worker", claimedTurn.getState().getLeaseOwner());
        assertEquals(2000, turns.renewLease("turn-1", "worker",
            claimedTurn.getState().getLeaseId(), 200, 2000).getState().getLeaseUntil());
        turns.releaseLease("turn-1", "worker", claimedTurn.getState().getLeaseId());

    }

    @Test
    public void shouldCreateParentAndChildAtomically() {
        RedisAgentTurnStore turns = config.turnStore();
        AgentTurnSnapshot parent = turns.save(snapshot("parent", AgentTurnStatus.RUNNING), -1);
        AgentTurnSnapshot initialChild = snapshot("child", AgentTurnStatus.READY);
        AgentTurnSnapshot child = initialChild.withState(initialChild.getState().toBuilder()
            .parentTurnId("parent").rootTurnId("parent").build());
        ParentChildTurnSnapshots pair = turns.saveParentAndChild(parent.withState(
            parent.getState().toBuilder().status(AgentTurnStatus.WAITING_FOR_CHILD).build()),
            0, child);
        assertEquals(1, pair.getParent().getState().getVersion());
        assertEquals(0, pair.getChild().getState().getVersion());
        assertEquals("parent", turns.load("child").getState().getParentTurnId());
    }

    @Test
    public void shouldAllowOnlyOneWorkerToClaimRun() throws Exception {
        final RedisAgentTurnStore turns = config.turnStore();
        turns.save(snapshot("race", AgentTurnStatus.READY), -1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<AgentTurnSnapshot>>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> { start.await(); return turns.claimRunnable("worker-a", 100, 1000, 1); }));
            futures.add(executor.submit(() -> { start.await(); return turns.claimRunnable("worker-b", 100, 1000, 1); }));
            start.countDown();
            assertEquals(1, futures.get(0).get().size() + futures.get(1).get().size());
        } finally { executor.shutdownNow(); }
    }

    private AgentTurnSnapshot snapshot() {
        return snapshot("turn-1", AgentTurnStatus.READY);
    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).rootTurnId(turnId).updatedAt(1).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

}
