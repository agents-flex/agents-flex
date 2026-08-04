package com.agentsflex.agent.store.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 创建 JDBC Agent Store 所需的可移植基础表结构。 */
public final class JdbcAgentStoreSchema extends JdbcAgentStoreSupport {
    JdbcAgentStoreSchema(JdbcAgentStoreConfig config) { super(config); }

    /** 幂等创建全部表和索引，适合测试、开发和应用启动期初始化。 */
    public void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String binary = config.getBinaryColumnType();
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("runs") + " ("
                + "run_id VARCHAR(191) PRIMARY KEY, version BIGINT NOT NULL, status VARCHAR(64) NOT NULL, "
                + "next_run_at BIGINT NOT NULL, lease_owner VARCHAR(191), lease_id VARCHAR(191), lease_until BIGINT NOT NULL, "
                + "parent_run_id VARCHAR(191), cancellation_requested BOOLEAN NOT NULL, payload " + binary + " NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS " + table("commands") + " ("
                + "command_id VARCHAR(191) PRIMARY KEY, run_id VARCHAR(191) NOT NULL, status VARCHAR(64) NOT NULL, "
                + "created_at BIGINT NOT NULL, lease_owner VARCHAR(191), lease_until BIGINT NOT NULL, "
                + "attempts INTEGER NOT NULL, error_message VARCHAR(2000), payload " + binary + " NOT NULL)");
            createIndexIfMissing(connection, statement, table("runs"), table("runs_runnable_idx"),
                "status, next_run_at, lease_until");
            createIndexIfMissing(connection, statement, table("commands"), table("commands_claim_idx"),
                "status, lease_until, created_at");
        } catch (SQLException error) {
            throw failure("initialize JDBC Agent Store schema", error);
        }
    }

    private void createIndexIfMissing(Connection connection, Statement statement, String tableName,
                                      String indexName, String columns) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        if (hasIndex(metadata, tableName, indexName) || hasIndex(metadata, tableName.toUpperCase(), indexName)) return;
        statement.execute("CREATE INDEX " + indexName + " ON " + tableName + " (" + columns + ")");
    }

    private boolean hasIndex(DatabaseMetaData metadata, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = metadata.getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                String existing = indexes.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(indexName)) return true;
            }
            return false;
        }
    }
}
