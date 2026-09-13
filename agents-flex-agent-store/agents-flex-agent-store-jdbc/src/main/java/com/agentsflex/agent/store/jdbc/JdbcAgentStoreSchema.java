package com.agentsflex.agent.store.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
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
            // 旧版本表可能包含 lease_until 且声明为 NOT NULL。新的 Store 不再写租约列，
            // 因此必须把遗留列改为可空，避免升级后的 INSERT 因缺少 lease 值失败。
            makeLegacyLeaseColumnNullable(connection, statement);
        } catch (SQLException error) {
            throw failure("initialize JDBC Agent Store schema", error);
        }
    }

    private void makeLegacyLeaseColumnNullable(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        if (!hasColumn(metadata, table("turns"), "lease_until")) return;
        try {
            statement.execute("ALTER TABLE " + table("turns")
                + " ALTER COLUMN lease_until DROP NOT NULL");
            return;
        } catch (SQLException dropNotNullSyntaxError) {
            try {
                statement.execute("ALTER TABLE " + table("turns")
                    + " ALTER COLUMN lease_until BIGINT NULL");
                return;
            } catch (SQLException standardSyntaxError) {
                // MySQL 使用 MODIFY 语法；其他数据库已在上面的标准语法中完成迁移。
            }
        }
        statement.execute("ALTER TABLE " + table("turns")
            + " MODIFY lease_until BIGINT NULL");
    }

    private boolean hasColumn(DatabaseMetaData metadata, String tableName, String columnName)
        throws SQLException {
        try (ResultSet columns = metadata.getColumns(null, null, tableName, columnName)) {
            if (columns.next()) return true;
        }
        try (ResultSet columns = metadata.getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }

}
