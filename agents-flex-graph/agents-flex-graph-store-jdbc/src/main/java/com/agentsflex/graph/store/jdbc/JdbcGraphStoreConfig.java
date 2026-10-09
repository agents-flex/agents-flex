package com.agentsflex.graph.store.jdbc;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * JDBC Graph Store 的不可变配置和统一工厂入口。
 *
 * <p>一个配置对象绑定一个 {@link DataSource}、一组表名前缀和一种 payload 序列化协议。
 * 由同一配置创建的 Store 会访问同一组表，但 Store 本身不持有普通业务连接，适合被应用作为
 * 单例复用。摄取锁是例外：每个活动租约会独占一条连接直到释放。</p>
 */
public final class JdbcGraphStoreConfig {
    /**
     * 应用负责配置和管理生命周期的连接数据源。
     */
    private final DataSource dataSource;
    /**
     * 所有模块表共享的前缀，用于同库隔离多套逻辑存储。
     */
    private final String tablePrefix;
    /**
     * payload 字段的数据库类型，例如 MySQL/H2 的 BLOB。
     */
    private final String binaryColumnType;
    /**
     * 获取摄取锁时允许等待的最长毫秒数。
     */
    private final long lockWaitMillis;
    /**
     * 完整领域快照的二进制序列化协议。
     */
    private final JdbcGraphStoreSerializer serializer;

    private JdbcGraphStoreConfig(Builder builder) {
        this.dataSource = builder.dataSource;
        this.tablePrefix = builder.tablePrefix;
        this.binaryColumnType = builder.binaryColumnType;
        this.lockWaitMillis = builder.lockWaitMillis;
        this.serializer = builder.serializer;
    }

    /**
     * 创建配置构造器。
     *
     * @param dataSource 应用管理的非空 JDBC 数据源
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /**
     * @return 当前配置使用的数据源。
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * @return 已校验、可安全用于 SQL 标识符拼接的表名前缀。
     */
    public String getTablePrefix() {
        return tablePrefix;
    }

    /**
     * @return Schema 初始化时使用的二进制列类型。
     */
    public String getBinaryColumnType() {
        return binaryColumnType;
    }

    /**
     * @return 摄取锁的等待时间，单位为毫秒。
     */
    public long getLockWaitMillis() {
        return lockWaitMillis;
    }

    /**
     * 包内获取序列化器，避免 Store 绕过统一配置。
     */
    JdbcGraphStoreSerializer serializer() {
        return serializer;
    }

    /**
     * @return 使用当前配置的新 Schema 初始化器。
     */
    public JdbcGraphStoreSchema schema() {
        return new JdbcGraphStoreSchema(this);
    }

    /**
     * @return 使用当前配置的文档状态与版本历史 Store。
     */
    public JdbcGraphDocumentStateStore documentStateStore() {
        return new JdbcGraphDocumentStateStore(this);
    }

    /**
     * @return 使用当前配置的可恢复摄取操作 Store。
     */
    public JdbcGraphIngestionOperationStore ingestionOperationStore() {
        return new JdbcGraphIngestionOperationStore(this);
    }

    /**
     * @return 使用当前配置的人工审核任务 Store。
     */
    public JdbcGraphReviewStore reviewStore() {
        return new JdbcGraphReviewStore(this);
    }

    /**
     * @return 使用当前配置的异步图导入任务 Store。
     */
    public JdbcGraphImportTaskStore importTaskStore() {
        return new JdbcGraphImportTaskStore(this);
    }

    /**
     * @return 使用当前配置的持久化实体注册表。
     */
    public JdbcGraphEntityRegistry entityRegistry() {
        return new JdbcGraphEntityRegistry(this);
    }

    /**
     * @return 使用当前配置的跨进程文档摄取锁。
     */
    public JdbcGraphIngestionLockProvider lockProvider() {
        return new JdbcGraphIngestionLockProvider(this);
    }

    /**
     * {@link JdbcGraphStoreConfig} 构造器。
     *
     * <p>表名前缀和列类型仅允许有限字符集，因为二者必须作为 SQL 标识符或类型直接写入 DDL，
     * 不能使用 PreparedStatement 参数。</p>
     */
    public static final class Builder {
        private final DataSource dataSource;
        private String tablePrefix = "af_graph_";
        private String binaryColumnType = "BLOB";
        private long lockWaitMillis = 30_000L;
        private JdbcGraphStoreSerializer serializer = new FastjsonJdbcGraphStoreSerializer();

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /**
         * 设置表名前缀；只接受 ASCII 字母、数字和下划线。
         */
        public Builder tablePrefix(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("tablePrefix contains unsupported characters");
            tablePrefix = value;
            return this;
        }

        /**
         * 设置 payload 二进制列类型，例如 {@code BLOB} 或 {@code VARBINARY(4096)}。
         */
        public Builder binaryColumnType(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_(), ]+"))
                throw new IllegalArgumentException("binaryColumnType contains unsupported characters");
            binaryColumnType = value;
            return this;
        }

        /**
         * 设置锁等待毫秒数；JDBC 执行时会向上取整为秒并限制在 int 范围内。
         */
        public Builder lockWaitMillis(long value) {
            if (value <= 0L) throw new IllegalArgumentException("lockWaitMillis must be positive");
            lockWaitMillis = value;
            return this;
        }

        /**
         * 设置自定义快照序列化器。
         */
        public Builder serializer(JdbcGraphStoreSerializer value) {
            serializer = Objects.requireNonNull(value, "serializer must not be null");
            return this;
        }

        /**
         * 构造不可变配置；该操作不会访问数据库或初始化表。
         */
        public JdbcGraphStoreConfig build() {
            return new JdbcGraphStoreConfig(this);
        }
    }
}
