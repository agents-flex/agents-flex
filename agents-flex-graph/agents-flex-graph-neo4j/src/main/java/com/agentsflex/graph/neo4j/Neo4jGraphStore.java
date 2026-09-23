package com.agentsflex.graph.neo4j;

import com.agentsflex.graph.importing.AsyncGraphImportService;
import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.importing.GraphImportService;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.neo4j.manager.Neo4jGraphManager;
import com.agentsflex.graph.neo4j.mutation.Neo4jGraphWriter;
import com.agentsflex.graph.neo4j.query.Neo4jGraphQueryExecutor;
import com.agentsflex.graph.neo4j.transaction.Neo4jGraphTransaction;

import org.neo4j.driver.*;


/**
 * 基于 Neo4j 官方 Java Driver 的图存储实现。
 */
public final class Neo4jGraphStore implements GraphStore {
    /**
     * 连接和默认空间配置。
     */
    private final Neo4jGraphStoreConfig config;
    /**
     * 官方驱动实例。
     */
    private final Driver driver;
    /**
     * 复用的写入器，供同步和异步导入使用。
     */
    private final GraphWriter writer;
    /**
     * 异步导入任务服务。
     */
    private final GraphImportService imports;
    /**
     * Neo4j 适配器支持的能力集合。
     */
    private final GraphCapabilities capabilities = GraphCapabilities.of(
            GraphFeature.CREATE_SPACE, GraphFeature.DROP_SPACE, GraphFeature.SCHEMA, GraphFeature.SCHEMA_INTROSPECTION,
            GraphFeature.INDEX, GraphFeature.UNIQUE_CONSTRAINT, GraphFeature.MULTI_LABEL,
            GraphFeature.TRANSACTIONS, GraphFeature.VARIABLE_LENGTH_PATH,
            GraphFeature.BULK_IMPORT, GraphFeature.QUERY_EXPLAIN, GraphFeature.STREAMING_CURSOR,
            GraphFeature.NATIVE_QUERY)
        .withNote(GraphFeature.CREATE_SPACE,
            "Creating databases requires Neo4j Enterprise edition and administration privileges")
        .withNote(GraphFeature.DROP_SPACE,
            "Dropping databases requires Neo4j Enterprise edition and administration privileges")
        .withNote(GraphFeature.VARIABLE_LENGTH_PATH, "Portable traversal limits paths to 16 hops")
        .withLimit(GraphFeature.VARIABLE_LENGTH_PATH, "maxHops", "16")
        .withNote(GraphFeature.SHORTEST_PATH, "Use NativeGraphQuery; no portable shortest-path AST is exposed")
        .withNote(GraphFeature.SCHEMA_INTROSPECTION, "Relationship endpoint labels are returned as unconstrained")
        .withNote(GraphFeature.BULK_IMPORT, "Uses online transactional batches; offline neo4j-admin import is not exposed")
        .withMode(GraphFeature.BULK_IMPORT, "ONLINE_BATCH");

    public Neo4jGraphStore(Neo4jGraphStoreConfig config) {
        this.config = config == null ? new Neo4jGraphStoreConfig() : config;
        this.driver = GraphDatabase.driver(this.config.getUri(),
            AuthTokens.basic(this.config.getUsername(), this.config.getPassword()));
        this.writer = new Neo4jGraphWriter(driver, this.config);
        this.imports = new AsyncGraphImportService(writer);
    }

    /**
     * @return Neo4j 能力声明
     */
    @Override
    public GraphCapabilities capabilities() {
        return capabilities;
    }

    /**
     * @return 空间管理器
     */
    @Override
    public GraphManager manager() {
        return new Neo4jGraphManager(driver, config);
    }

    /**
     * @return 写入器
     */
    @Override
    public GraphWriter writer() {
        return writer;
    }

    /**
     * @return 查询执行器
     */
    @Override
    public GraphQueryExecutor query() {
        return new Neo4jGraphQueryExecutor(driver, config);
    }

    /**
     * @return 异步导入任务服务
     */
    @Override
    public GraphImportService imports() {
        return imports;
    }

    /**
     * 执行 Neo4j 驱动连通性探测。
     */
    @Override
    public GraphHealth health() {
        long started = System.currentTimeMillis();
        try {
            driver.verifyConnectivity();
            return GraphHealth.up("neo4j", System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            return GraphHealth.down("neo4j", e.getMessage(), System.currentTimeMillis() - started);
        }
    }

    /**
     * @return 创建 Neo4j 显式事务的管理器
     */
    @Override
    public GraphTransactionManager transactions() {
        return options -> {
            Session session = open(options);
            org.neo4j.driver.Transaction transaction = session.beginTransaction(transactionConfig(options));
            return new Neo4jGraphTransaction(session, transaction, config);
        };
    }

    /**
     * 按操作选项打开目标数据库会话。
     */
    Session open(GraphOptions options) {
        GraphOptions resolved = options == null ? GraphOptions.DEFAULT : options;
        String database = resolved.getSpaceOrDefault(config.getDefaultSpace());
        return driver.session(SessionConfig.builder().withDatabase(database)
            .withFetchSize(resolved.getFetchSize()).build());
    }

    /**
     * 将统一超时选项转换为 Neo4j 事务配置。
     */
    public static org.neo4j.driver.TransactionConfig transactionConfig(GraphOptions options) {
        long timeout = options == null ? GraphOptions.DEFAULT.getTimeoutMillis() : options.getTimeoutMillis();
        return org.neo4j.driver.TransactionConfig.builder()
            .withTimeout(java.time.Duration.ofMillis(timeout)).build();
    }

    /**
     * 关闭驱动及其连接池。
     */
    @Override
    public void close() {
        imports.close();
        driver.close();
    }
}
