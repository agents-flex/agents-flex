package com.agentsflex.agent.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 创建 JDBC Agent Store 所需的可移植基础表结构。
 */
public final class JdbcAgentStoreSchema extends JdbcAgentStoreSupport {
    JdbcAgentStoreSchema(JdbcAgentStoreConfig config) {
        super(config);
    }

    /**
     * 幂等创建全部表和索引，适合测试、开发和应用启动期初始化。
     */
    public void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String binary = config.getBinaryColumnType();
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("turns") + " ("
                + "turn_id VARCHAR(191) PRIMARY KEY, version BIGINT NOT NULL, status VARCHAR(64) NOT NULL, "
                + "next_runnable_at BIGINT NOT NULL, "
                + "cancellation_requested BOOLEAN NOT NULL, payload " + binary + " NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("compression_states") + " ("
                + "conversation_id VARCHAR(191) PRIMARY KEY, version BIGINT NOT NULL, payload " + binary + " NOT NULL)");
            // Usage 必须按会话和模型作用域隔离；独立 version 列用于数据库 CAS 条件更新。
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("context_usage_states") + " ("
                + "conversation_id VARCHAR(191) NOT NULL, usage_scope_key VARCHAR(512) NOT NULL, "
                + "version BIGINT NOT NULL, payload " + binary + " NOT NULL, "
                + "PRIMARY KEY (conversation_id, usage_scope_key))");
        } catch (SQLException error) {
            throw failure("initialize JDBC Agent Store schema", error);
        }
    }

}
