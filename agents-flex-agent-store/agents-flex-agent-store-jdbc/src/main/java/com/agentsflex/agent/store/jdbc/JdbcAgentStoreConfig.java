package com.agentsflex.agent.store.jdbc;

import com.agentsflex.agent.store.AgentStoreSerializer;
import com.agentsflex.agent.store.FastjsonAgentStoreSerializer;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * JDBC Agent Store 的数据源与表名前缀配置。
 */
public final class JdbcAgentStoreConfig {
    private final DataSource dataSource;
    private final String tablePrefix;
    private final String binaryColumnType;
    private final AgentStoreSerializer serializer;

    private JdbcAgentStoreConfig(Builder builder) {
        this.dataSource = builder.dataSource;
        this.tablePrefix = builder.tablePrefix;
        this.binaryColumnType = builder.binaryColumnType;
        this.serializer = builder.serializer;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public String getBinaryColumnType() {
        return binaryColumnType;
    }

    AgentStoreSerializer getSerializer() {
        return serializer;
    }

    public JdbcAgentStoreSchema schema() {
        return new JdbcAgentStoreSchema(this);
    }

    public JdbcAgentRunStore runStore() {
        return new JdbcAgentRunStore(this);
    }

    public static final class Builder {
        private final DataSource dataSource;
        private String tablePrefix = "af_agent_";
        private String binaryColumnType = "BLOB";
        private AgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /**
         * 设置表名前缀，只允许字母、数字和下划线。
         */
        public Builder tablePrefix(String tablePrefix) {
            if (tablePrefix == null || !tablePrefix.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("tablePrefix contains unsupported characters");
            }
            this.tablePrefix = tablePrefix;
            return this;
        }

        /**
         * 设置数据库方言对应的二进制列类型，例如 BLOB、BYTEA 或 VARBINARY(MAX)。
         */
        public Builder binaryColumnType(String binaryColumnType) {
            if (binaryColumnType == null || !binaryColumnType.matches("[A-Za-z0-9_(), ]+")) {
                throw new IllegalArgumentException("binaryColumnType contains unsupported characters");
            }
            this.binaryColumnType = binaryColumnType;
            return this;
        }

        /**
         * 设置 Snapshot 的二进制编码实现。
         */
        public Builder serializer(AgentStoreSerializer serializer) {
            this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
            return this;
        }

        public JdbcAgentStoreConfig build() {
            return new JdbcAgentStoreConfig(this);
        }
    }
}
