package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentContextCompressionState;
import com.agentsflex.agent.AgentContextCompressionStateStore;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.ParentChildTurnSnapshots;
import com.agentsflex.core.message.AiMessage;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * 验证 JDBC 实现与 Agent Store SPI 的状态、并发和恢复语义一致。
 */
public class JdbcAgentStoresContractTest {
    private JdbcAgentStoreConfig config;

    @Before
    public void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config = JdbcAgentStoreConfig.builder(dataSource).tablePrefix("test_agent_").build();
        config.schema().initialize();
    }

    @Test
    public void shouldPersistRunWithOptimisticLockCancellationAndLease() {
        JdbcAgentTurnStore store = config.turnStore();
        AgentTurnSnapshot created = store.save(snapshot("turn-1", AgentTurnStatus.READY), -1);
        assertEquals(0, created.getState().getVersion());
        assertEquals("value", store.load("turn-1").getState().getMetadata().get("key"));

        assertTrue(store.requestCancellation("turn-1"));
        assertFalse(store.requestCancellation("turn-1"));
        AgentTurnSnapshot updated = store.save(created.withState(created.getState().toBuilder()
            .status(AgentTurnStatus.RUNNING).build()), 0);
        assertTrue(updated.getState().isCancellationRequested());

        List<AgentTurnSnapshot> claimed = store.claimRunnable("worker-a", 1000, 5000, 10);
        assertEquals(1, claimed.size());
        assertEquals("worker-a", claimed.get(0).getState().getLeaseOwner());
        long claimedVersion = claimed.get(0).getState().getVersion();
        AgentTurnSnapshot renewed = store.renewLease("turn-1", "worker-a",
            claimed.get(0).getState().getLeaseId(),
            1500, 7000);
        assertEquals(7000, renewed.getState().getLeaseUntil());
        assertEquals(claimedVersion, renewed.getState().getVersion());
        try {
            store.renewLease("turn-1", "worker-a", "stale-token", 1600, 8000);
            fail("stale lease token must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("worker-a"));
        }
        store.releaseLease("turn-1", "worker-a", "stale-token");
        assertEquals(claimed.get(0).getState().getLeaseId(),
            store.load("turn-1").getState().getLeaseId());
        store.releaseLease("turn-1", "worker-a", claimed.get(0).getState().getLeaseId());
        assertNull(store.load("turn-1").getState().getLeaseOwner());
        assertEquals(claimedVersion, store.load("turn-1").getState().getVersion());

        try {
            store.save(created, 0);
            fail("Expected optimistic lock conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("turn-1"));
        }
    }

    @Test
    public void shouldAtomicallyPersistParentAndChildAndDelayChildWhileParentLeased() {
        JdbcAgentTurnStore store = config.turnStore();
        AgentTurnSnapshot parent = store.save(snapshot("parent", AgentTurnStatus.RUNNING), -1);
        AgentTurnSnapshot initialChild = snapshot("child", AgentTurnStatus.READY);
        AgentTurnSnapshot child = initialChild.withState(initialChild.getState().toBuilder()
            .parentTurnId("parent").rootTurnId("parent").build());
        ParentChildTurnSnapshots pair = store.saveParentAndChild(
            parent.withState(parent.getState().toBuilder()
                .status(AgentTurnStatus.WAITING_FOR_CHILD).build()), 0, child);
        assertEquals(1, pair.getParent().getState().getVersion());
        assertEquals(0, pair.getChild().getState().getVersion());
        assertEquals("parent", store.load("child").getState().getParentTurnId());
    }

    @Test
    public void shouldAllowOnlyOneWorkerToClaimTheSameRun() throws Exception {
        final JdbcAgentTurnStore store = config.turnStore();
        store.save(snapshot("race-turn", AgentTurnStatus.READY), -1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<AgentTurnSnapshot>> first = executor.submit(() -> {
                start.await();
                return store.claimRunnable("worker-a", 100, 1000, 1);
            });
            Future<List<AgentTurnSnapshot>> second = executor.submit(() -> {
                start.await();
                return store.claimRunnable("worker-b", 100, 1000, 1);
            });
            start.countDown();
            assertEquals(1, first.get().size() + second.get().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldPersistCompressionStateWithCasAndRestoreSummaryMessages() {
        AgentContextCompressionStateStore store = config.compressionStateStore();
        AgentContextCompressionState first = new AgentContextCompressionState(1,
            Arrays.asList(new AiMessage("summary-1")), "message-100", 1, 1000, 10);
        assertTrue(store.save("conversation-1", first, 0));
        AgentContextCompressionState loaded = store.load("conversation-1");
        assertEquals(1, loaded.getVersion());
        assertEquals("message-100", loaded.getCoveredUntilMessageId());
        assertEquals("summary-1", loaded.getSummaryMessages().get(0).getTextContent());

        AgentContextCompressionState second = new AgentContextCompressionState(2,
            Arrays.asList(new AiMessage("summary-2")), "message-200", 2, 2000, 20);
        assertFalse(store.save("conversation-1", second, 0));
        assertTrue(store.save("conversation-1", second, 1));
        assertEquals(2, store.load("conversation-1").getCompressionVersion());
        assertNull(store.load("missing-conversation"));
    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).updatedAt(1).rootTurnId(turnId)
            .metadata(Collections.<String, Object>singletonMap("key", "value")).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

}
