package com.agentsflex.agent.store.jdbc;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;

/** JDBC Store 共享的表名、事务和二进制序列化能力。 */
abstract class JdbcAgentStoreSupport {
    protected final JdbcAgentStoreConfig config;

    JdbcAgentStoreSupport(JdbcAgentStoreConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
    }

    protected String table(String suffix) { return config.getTablePrefix() + suffix; }

    protected Connection connection() throws SQLException {
        return config.getDataSource().getConnection();
    }

    protected RuntimeException failure(String operation, SQLException error) {
        return new IllegalStateException("Failed to " + operation, error);
    }

    protected byte[] serialize(Serializable value) { return config.getSerializer().serialize(value); }

    protected <T> T deserialize(byte[] bytes, Class<T> type) { return config.getSerializer().deserialize(bytes, type); }

    protected static void rollback(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); } catch (SQLException ignored) { }
    }
}
