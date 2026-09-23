package com.agentsflex.graph.nebula;

import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.capability.GraphCapabilities;
import com.agentsflex.graph.capability.GraphFeature;
import com.agentsflex.graph.connection.GraphHealth;
import com.agentsflex.graph.importing.AsyncGraphImportService;
import com.agentsflex.graph.importing.GraphImportService;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.nebula.manager.NebulaGraphManager;
import com.agentsflex.graph.nebula.mutation.NebulaGraphWriter;
import com.agentsflex.graph.nebula.query.NebulaGraphQueryExecutor;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.transaction.GraphTransactionManager;
import com.vesoft.nebula.client.graph.SessionPool;
import com.vesoft.nebula.client.graph.SessionPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Nebula 官方 SessionPool 客户端的图存储实现。
 */
public final class NebulaGraphStore implements GraphStore {
    /**
     * 是否已经释放连接池和异步任务资源；关闭后禁止隐式重建会话池。
     */
    private volatile boolean closed;
    /**
     * 连接和默认空间配置。
     */
    private final NebulaGraphStoreConfig config;
    /**
     * 按空间缓存的会话池。
     */
    private final Map<String, SessionPool> pools = new ConcurrentHashMap<>();
    /**
     * 复用的写入器，供同步和异步导入使用。
     */
    private final GraphWriter writer;
    /**
     * 异步导入任务服务。
     */
    private final GraphImportService imports;
    /**
     * Nebula 适配器支持的能力集合。
     */
    private final GraphCapabilities capabilities = GraphCapabilities.of(
            GraphFeature.CREATE_SPACE, GraphFeature.DROP_SPACE, GraphFeature.SCHEMA, GraphFeature.SCHEMA_INTROSPECTION,
            GraphFeature.INDEX, GraphFeature.VARIABLE_LENGTH_PATH,
            GraphFeature.BULK_IMPORT, GraphFeature.QUERY_EXPLAIN, GraphFeature.NATIVE_QUERY)
        .withNote(GraphFeature.MULTI_LABEL, "Portable writer supports one tag per vertex")
        .withNote(GraphFeature.SHORTEST_PATH, "Use NativeGraphQuery; no portable shortest-path AST is exposed")
        .withNote(GraphFeature.TRANSACTIONS, "SessionPool does not expose portable explicit transactions")
        .withNote(GraphFeature.UNIQUE_CONSTRAINT, "Nebula does not expose portable unique indexes")
        .withNote(GraphFeature.SCHEMA_INTROSPECTION, "Returns tags and edges; endpoint constraints and indexes are incomplete")
        .withNote(GraphFeature.BULK_IMPORT, "Uses online UPSERT batches; offline importer is not exposed")
        .withMode(GraphFeature.BULK_IMPORT, "ONLINE_BATCH")
        .withNote(GraphFeature.STREAMING_CURSOR,
            "SessionPool returns a materialized ResultSet; executeCursor uses the portable in-memory fallback")
        .withLimit(GraphFeature.VARIABLE_LENGTH_PATH, "maxHops", "backend-defined");

    public NebulaGraphStore(NebulaGraphStoreConfig config) {
        this.config = config == null ? new NebulaGraphStoreConfig() : config;
        this.writer = new NebulaGraphWriter(this, this.config);
        this.imports = new AsyncGraphImportService(writer);
    }

    /**
     * 获取或创建指定空间的会话池。
     */
    public SessionPool pool(String space) {
        if (closed) throw new IllegalStateException("Nebula graph store is closed");
        // 管理操作使用空字符串表示 meta space；空白空间名也必须回退到配置的默认空间。
        final String resolved = space == null || space.trim().isEmpty() ? config.getDefaultSpace() : space;
        return pools.computeIfAbsent(resolved, key -> {
            SessionPoolConfig poolConfig = new SessionPoolConfig(
                Collections.singletonList(new HostAddress(config.getHost(), config.getPort())),
                key, config.getUsername(), config.getPassword())
                .setMinSessionSize(config.getMinSessions())
                .setMaxSessionSize(config.getMaxSessions());
            SessionPool pool = new SessionPool(poolConfig);
            if (!pool.init()) throw new IllegalStateException("Unable to initialize Nebula session pool for " + key);
            return pool;
        });
    }

    /**
     * @return Nebula 能力声明
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
        return new NebulaGraphManager(this, config);
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
        return new NebulaGraphQueryExecutor(this, config);
    }

    /**
     * @return 异步导入任务服务
     */
    @Override
    public GraphImportService imports() {
        return imports;
    }

    /**
     * 使用 SHOW SPACES 执行 Nebula Graph 连通性探测。
     */
    @Override
    public GraphHealth health() {
        long started = System.currentTimeMillis();
        try {
            com.vesoft.nebula.client.graph.data.ResultSet result = pool("").execute("SHOW SPACES");
            if (!result.isSucceeded()) {
                return GraphHealth.down("nebula", result.getErrorMessage(), System.currentTimeMillis() - started);
            }
            return GraphHealth.up("nebula", System.currentTimeMillis() - started);
        } catch (Exception e) {
            return GraphHealth.down("nebula", e.getMessage(), System.currentTimeMillis() - started);
        }
    }

    /**
     * Nebula SessionPool 不提供统一显式事务，因此明确报告不支持。
     */
    @Override
    public GraphTransactionManager transactions() {
        throw new com.agentsflex.graph.UnsupportedGraphFeatureException("Nebula Graph transactions are not exposed by SessionPool");
    }

    /**
     * 关闭所有空间的会话池。
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        imports.close();
        for (SessionPool pool : pools.values()) pool.close();
        pools.clear();
    }
}
