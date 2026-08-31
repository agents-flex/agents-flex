package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.store.AgentTurnVersionConflictException;
import com.agentsflex.agent.store.ParentChildTurnSnapshots;
import com.agentsflex.core.message.AiMessage;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
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
    private DataSource dataSource;
    private String tablePrefix;

    @Before
    public void setUp() {
        tablePrefix = "test_agent_" + UUID.randomUUID().toString().replace("-", "") + "_";
        String mysqlUrl = System.getProperty("mysql.test.url");
        if (mysqlUrl == null) {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            dataSource = h2;
        } else {
            MysqlDataSource mysql = new MysqlDataSource();
            mysql.setURL(mysqlUrl);
            mysql.setUser(System.getProperty("mysql.test.user", "root"));
            mysql.setPassword(requiredEnv("MYSQL_TEST_PASSWORD"));
            dataSource = mysql;
        }
        config = JdbcAgentStoreConfig.builder(dataSource).tablePrefix(tablePrefix).build();
        config.schema().initialize();
        config.schema().initialize();
    }

    @After
    public void tearDown() throws Exception {
        if (dataSource == null || tablePrefix == null) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + tablePrefix + "compression_states");
            statement.execute("DROP TABLE IF EXISTS " + tablePrefix + "turns");
        }
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
        store.save(snapshot("parent", AgentTurnStatus.READY), -1);
        AgentTurnSnapshot parent = store.claimRunnable("parent-worker", 100, 1000, 1).get(0);
        AgentTurnSnapshot initialChild = snapshot("child", AgentTurnStatus.READY);
        AgentTurnSnapshot child = initialChild.withState(initialChild.getState().toBuilder()
            .parentTurnId("parent").rootTurnId("parent").build());
        ParentChildTurnSnapshots pair = store.saveParentAndChild(
            parent.withState(parent.getState().toBuilder()
                .status(AgentTurnStatus.WAITING_FOR_CHILD).build()),
            parent.getState().getVersion(), child);
        assertEquals(2, pair.getParent().getState().getVersion());
        assertEquals(0, pair.getChild().getState().getVersion());
        assertEquals("parent", store.load("child").getState().getParentTurnId());
        assertTrue(store.claimRunnable("child-worker", 101, 100, 1).isEmpty());
        store.releaseLease("parent", "parent-worker", parent.getState().getLeaseId());
        assertEquals("child", store.claimRunnable("child-worker", 102, 100, 1).get(0)
            .getState().getTurnId());
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

    /**
     * 取消标记在终态快照中保持为 true 时，也不能让终态 Turn 再次被领取。
     */
    @Test
    public void shouldNeverReclaimCanceledTerminalTurn() {
        JdbcAgentTurnStore store = config.turnStore();
        store.save(snapshot("terminal", AgentTurnStatus.READY), -1);
        assertTrue(store.requestCancellation("terminal"));
        AgentTurnSnapshot claimed = store.claimRunnable("worker", 10, 100, 1).get(0);
        AgentTurnSnapshot terminal = claimed.withState(claimed.getState().toBuilder()
            .status(AgentTurnStatus.CANCELLED).build());
        AgentTurnSnapshot saved = store.save(terminal, claimed.getState().getVersion());
        store.releaseLease("terminal", "worker", saved.getState().getLeaseId());

        assertTrue(store.claimRunnable("other", 11, 100, 1).isEmpty());
    }

    @Test
    public void shouldPersistCompressionStateWithCasAndRestoreSummaryMessages() {
        AgentContextCompressionStateStore store = config.compressionStateStore();
        AgentContextCompressionState first = new AgentContextCompressionState(1,
            Arrays.asList(new AiMessage("summary-1")), "message-100");
        assertTrue(store.save("conversation-1", first, 0));
        AgentContextCompressionState loaded = store.load("conversation-1");
        assertEquals(1, loaded.getVersion());
        assertEquals("message-100", loaded.getCoveredUntilMessageId());
        assertEquals("summary-1", loaded.getSummaryMessages().get(0).getTextContent());

        AgentContextCompressionState second = new AgentContextCompressionState(2,
            Arrays.asList(new AiMessage("summary-2")), "message-200");
        assertFalse(store.save("conversation-1", second, 0));
        assertTrue(store.save("conversation-1", second, 1));
        assertEquals(2, store.load("conversation-1").getVersion());
        assertNull(store.load("missing-conversation"));
    }

    /**
     * 活动会话查询应排除终态，终态子任务应能被等待中的父任务恢复扫描发现。
     */
    @Test
    public void shouldFindActiveTurnAndTerminalChildForRecovery() {
        JdbcAgentTurnStore store = config.turnStore();
        AgentTurnSnapshot activeSource = snapshot("active", AgentTurnStatus.READY);
        AgentTurnSnapshot active = activeSource.withState(activeSource.getState().toBuilder()
            .metadata(Collections.<String, Object>singletonMap(
                "agentsflex.conversationId", "conversation-1"))
            .build());
        AgentTurnSnapshot savedActive = store.save(active, -1);
        assertEquals("active", store.findActiveTurn("conversation-1").getState().getTurnId());
        store.save(savedActive.withState(savedActive.getState().toBuilder()
            .status(AgentTurnStatus.COMPLETED).build()), savedActive.getState().getVersion());
        assertNull(store.findActiveTurn("conversation-1"));

        AgentTurnSnapshot parentSource = snapshot("waiting-parent", AgentTurnStatus.WAITING_FOR_CHILD);
        AgentTurnSnapshot parent = parentSource.withState(parentSource.getState().toBuilder()
            .suspension(AgentSuspension.child("terminal-child")).build());
        store.save(parent, -1);
        AgentTurnSnapshot childSource = snapshot("terminal-child", AgentTurnStatus.COMPLETED);
        AgentTurnSnapshot child = childSource.withState(childSource.getState().toBuilder()
            .parentTurnId("waiting-parent").rootTurnId("waiting-parent").build());
        store.save(child, -1);

        List<AgentTurnSnapshot> completed = store.findTerminalChildrenWithWaitingParent(1);
        assertEquals(1, completed.size());
        assertEquals("terminal-child", completed.get(0).getState().getTurnId());
    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).updatedAt(1).rootTurnId(turnId)
            .metadata(Collections.<String, Object>singletonMap("key", "value")).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
    }

}
