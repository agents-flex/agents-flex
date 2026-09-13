package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.exception.AgentTurnVersionConflictException;
import com.agentsflex.core.message.AiMessage;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 使用真实 MySQL 验证 Agent Turn Store。
 *
 * <p>默认跳过，避免普通单元测试依赖外部数据库。设置 {@code mysql.test.url} 后，测试会真实连接
 * MySQL；设置 {@code mysql.test.required=true} 后，缺少连接配置会直接失败而不会静默跳过。</p>
 */
public class MysqlAgentStoresIntegrationTest {
    private JdbcAgentStoreConfig config;
    private MysqlDataSource dataSource;
    private String tablePrefix;

    @Before
    public void setUp() throws Exception {
        String url = System.getProperty("mysql.test.url");
        if (url == null || url.trim().isEmpty()) {
            if (Boolean.getBoolean("mysql.test.required")) {
                throw new IllegalStateException("mysql.test.url is required for real MySQL tests");
            }
            Assume.assumeTrue("set -Dmysql.test.url=... to run real MySQL tests", false);
        }
        // MySQL 的表名上限是 64 个字符，前缀必须为固定表名预留空间。
        tablePrefix = "afmi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        dataSource = new MysqlDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getProperty("mysql.test.user", "root"));
        dataSource.setPassword(password());
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue("the configured JDBC URL must connect to MySQL, but was "
                    + metadata.getDatabaseProductName(),
                metadata.getDatabaseProductName().toLowerCase().contains("mysql"));
        }
        config = JdbcAgentStoreConfig.builder(dataSource).tablePrefix(tablePrefix).build();
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
    public void shouldPersistCasCancellationAndActiveConversationOnMysql() {
        JdbcAgentTurnStore store = config.turnStore();
        AgentTurnSnapshot created = store.save(snapshot("mysql-turn", AgentTurnStatus.READY,
            "mysql-conversation"), -1);
        assertEquals(0, created.getState().getVersion());
        assertEquals("value", store.load("mysql-turn").getState().getMetadata().get("key"));
        assertTrue(store.requestCancellation("mysql-turn"));
        AgentTurnSnapshot cancelled = store.save(created.withState(created.getState().toBuilder()
            .status(AgentTurnStatus.RUNNING).build()), 0);
        assertTrue(cancelled.getState().isCancellationRequested());
        try {
            store.save(created, 0);
            fail("expected MySQL optimistic-lock conflict");
        } catch (AgentTurnVersionConflictException expected) {
            assertTrue(expected.getMessage().contains("mysql-turn"));
        }
        assertEquals("mysql-turn", store.findActiveTurn("mysql-conversation")
            .getState().getTurnId());
        store.save(cancelled.withState(cancelled.getState().toBuilder()
            .status(AgentTurnStatus.COMPLETED).build()), cancelled.getState().getVersion());
        assertNull(store.findActiveTurn("mysql-conversation"));
    }

    @Test
    public void shouldPersistCompressionCasOnMysql() {
        AgentContextCompressionStateStore store = config.compressionStateStore();
        AgentContextCompressionState first = new AgentContextCompressionState(1,
            Arrays.asList(new AiMessage("mysql-summary-1")), "mysql-message-1");
        assertTrue(store.save("mysql-conversation", first, 0));
        AgentContextCompressionState second = new AgentContextCompressionState(2,
            Arrays.asList(new AiMessage("mysql-summary-2")), "mysql-message-2");
        assertFalse(store.save("mysql-conversation", second, 0));
        assertTrue(store.save("mysql-conversation", second, 1));
        assertEquals("mysql-summary-2",
            store.load("mysql-conversation").getSummaryMessages().get(0).getTextContent());
    }

    private AgentTurnSnapshot snapshot(String turnId, AgentTurnStatus status, String conversationId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("key", "value");
        metadata.put("agentsflex.conversationId", conversationId);
        AgentTurnState state = AgentTurnState.builder(turnId, AgentExecutionPolicy.defaults(), 1)
            .status(status)
            .updatedAt(1)
            .metadata(metadata)
            .build();
        return AgentTurnSnapshot.of("agent", "1", state);
    }

    private String password() {
        String value = System.getProperty("mysql.test.password");
        if (value == null) value = System.getenv("MYSQL_TEST_PASSWORD");
        if (value == null) throw new IllegalStateException(
            "set -Dmysql.test.password or MYSQL_TEST_PASSWORD for real MySQL tests");
        return value;
    }
}
