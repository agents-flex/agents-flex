package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.neo4j.Neo4jGraphStore;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import com.agentsflex.graph.GraphOptions;

import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 将统一查询编译为 Cypher，并物化 Neo4j 驱动结果。
 */
public final class Neo4jGraphQueryExecutor implements GraphQueryExecutor {
    /**
     * 默认执行时使用的配置。
     */
    private final Neo4jGraphStoreConfig config;
    /**
     * 非事务查询使用的驱动。
     */
    private final org.neo4j.driver.Driver driver;
    private final org.neo4j.driver.QueryRunner runner;

    public Neo4jGraphQueryExecutor(org.neo4j.driver.Driver driver, Neo4jGraphStoreConfig config) {
        this.config = config;
        this.driver = driver;
        this.runner = null;
    }

    public Neo4jGraphQueryExecutor(org.neo4j.driver.QueryRunner runner, Neo4jGraphStoreConfig config) {
        this.config = config;
        this.driver = null;
        this.runner = runner;
    }

    /**
     * 编译并执行可移植遍历查询。
     */
    @Override
    public GraphResult execute(GraphQuery query, GraphOptions options) {
        if (!(query instanceof TraversalQuery)) {
            throw new IllegalArgumentException("Unsupported portable Neo4j query: " + query.getClass().getName());
        }
        Neo4jCypherCompiler.Compiled compiled = new Neo4jCypherCompiler().compile((TraversalQuery) query);
        return executeCompiled(compiled, options);
    }

    /**
     * 执行原生 Cypher。
     */
    @Override
    public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
        return executeCompiled(new Neo4jCypherCompiler().compile(query), options);
    }

    /**
     * 选择事务运行器或独立会话执行编译结果。
     */
    private GraphResult executeCompiled(Neo4jCypherCompiler.Compiled compiled, GraphOptions options) {
        long started = System.currentTimeMillis();
        GraphOptions resolved = options == null ? GraphOptions.DEFAULT : options;
        if (runner != null) return read(runner.run(compiled.statement, compiled.parameters), compiled.statement,
            resolved.getMaxRecords(), started);
        if (driver == null) throw new IllegalStateException("Neo4j executor is not bound to a driver");
        String database = resolved.getSpaceOrDefault(config.getDefaultSpace());
        org.neo4j.driver.SessionConfig sessionConfig = org.neo4j.driver.SessionConfig.builder()
            .withDatabase(database).withFetchSize(resolved.getFetchSize()).build();
        try (Session session = driver.session(sessionConfig)) {
            return read(session.run(compiled.statement, compiled.parameters,
                Neo4jGraphStore.transactionConfig(resolved)), compiled.statement, resolved.getMaxRecords(), started);
        }
    }

    /**
     * 在给定运行器上执行编译结果，供测试和事务实现复用。
     */
    static GraphResult run(org.neo4j.driver.QueryRunner runner, Neo4jCypherCompiler.Compiled compiled) {
        return read(runner.run(compiled.statement, compiled.parameters), compiled.statement,
            GraphOptions.DEFAULT.getMaxRecords(), System.currentTimeMillis());
    }

    /**
     * 将驱动 Record 转换为统一 GraphRecord 列表。
     */
    private static GraphResult read(Result result, String statement, int maxRecords, long started) {
        List<GraphRecord> records = new ArrayList<>();
        while (records.size() < maxRecords && result.hasNext()) {
            Record record = result.next();
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (String key : record.keys()) {
                Value value = record.get(key);
                values.put(key, value == null || value.isNull() ? null : value.asObject());
            }
            records.add(new GraphRecord(values));
        }
        boolean truncated = result.hasNext();
        return new GraphResult(records, statement, new GraphResultMetadata(records.size(), truncated,
            Math.max(0L, System.currentTimeMillis() - started)));
    }
}
/**
 * 事务或测试注入的查询运行器。
 */
/**
 * 创建绑定驱动的执行器。
 */
/** 创建绑定 QueryRunner 的执行器，供事务复用。 */
