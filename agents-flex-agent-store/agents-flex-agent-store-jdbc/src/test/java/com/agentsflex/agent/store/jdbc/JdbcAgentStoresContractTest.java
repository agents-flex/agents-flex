package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
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
    public void shouldPersistRunWithOptimisticLockAndCancellation() {
        JdbcAgentTurnStore store = config.turnStore();
        AgentTurnSnapshot created = store.save(snapshot("turn-1", AgentTurnStatus.READY), -1);
        assertEquals(0, created.getState().getVersion());
        assertEquals("value", store.load("turn-1").getState().getMetadata().get("key"));

        assertTrue(store.requestCancellation("turn-1"));
        assertFalse(store.requestCancellation("turn-1"));
        AgentTurnSnapshot updated = store.save(created.withState(created.getState().toBuilder()
            .status(AgentTurnStatus.RUNNING).build()), 0);
        assertTrue(updated.getState().isCancellationRequested());

        try {
            store.save(created, 0);
            fail("Expected optimistic lock conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("turn-1"));
        }
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

    /** 活动会话查询应排除终态。 */
    @Test
    public void shouldFindActiveTurnForRecovery() {
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

    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status) {
        AgentTurnState state = AgentTurnState.builder(turnId,
                AgentExecutionPolicy.defaults(), 1)
            .status(status).updatedAt(1)
            .metadata(Collections.<String, Object>singletonMap("key", "value")).build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalStateException(name + " is required");
        return value;
    }

}
