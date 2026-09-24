package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphExplainResult;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultCursor;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphSubgraphResult;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.query.GraphOptionalQuery;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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
        if (!(query instanceof TraversalQuery) && !(query instanceof GraphUnionQuery)
            && !(query instanceof GraphOptionalQuery)) {
            throw new IllegalArgumentException("Unsupported portable Neo4j query: " + query.getClass().getName());
        }
        Neo4jCypherCompiler compiler = new Neo4jCypherCompiler();
        Neo4jCypherCompiler.Compiled compiled = query instanceof TraversalQuery
            ? compiler.compile((TraversalQuery) query)
            : query instanceof GraphUnionQuery ? compiler.compile((GraphUnionQuery) query)
            : compiler.compile((GraphOptionalQuery) query);
        try {
            return executeCompiled(compiled, options);
        } catch (com.agentsflex.graph.GraphException error) {
            throw error;
        } catch (RuntimeException error) {
            // 保留 timeout/deadline 等稳定错误分类，避免普通执行路径吞掉 QUERY_TIMEOUT。
            throw queryFailure("Neo4j query failed", error);
        }
    }

    /**
     * 执行原生 Cypher。
     */
    @Override
    public GraphResult execute(NativeGraphQuery query, GraphOptions options) {
        if (options != null && options.isReadOnly() && !query.isReadOnly()) {
            throw new com.agentsflex.graph.GraphException(com.agentsflex.graph.error.GraphErrorCode.INVALID_ARGUMENT,
                "Read-only graph options reject native " + query.getKind() + " query");
        }
        try {
            return executeCompiled(new Neo4jCypherCompiler().compile(query), options);
        } catch (com.agentsflex.graph.GraphException error) {
            throw error;
        } catch (RuntimeException error) {
            // 原生查询与可移植查询使用相同的错误归一化规则。
            throw queryFailure("Neo4j query failed", error);
        }
    }

    /**
     * 使用 Neo4j 驱动的惰性 {@link Result} 创建真正的后端流式游标。
     * 调用方必须关闭游标，以便及时归还其独占的 Session。
     */
    @Override
    public GraphResultCursor executeCursor(GraphQuery query, GraphOptions options) {
        if (!(query instanceof TraversalQuery) && !(query instanceof GraphUnionQuery)
            && !(query instanceof GraphOptionalQuery)) {
            throw new IllegalArgumentException("Unsupported portable Neo4j query: " + query.getClass().getName());
        }
        Neo4jCypherCompiler compiler = new Neo4jCypherCompiler();
        return openCursor(query instanceof TraversalQuery ? compiler.compile((TraversalQuery) query)
            : query instanceof GraphUnionQuery ? compiler.compile((GraphUnionQuery) query)
            : compiler.compile((GraphOptionalQuery) query), options);
    }

    /**
     * 使用 Neo4j 驱动流式执行原生 Cypher。
     */
    @Override
    public GraphResultCursor executeCursor(NativeGraphQuery query, GraphOptions options) {
        if (options != null && options.isReadOnly() && !query.isReadOnly()) {
            throw new com.agentsflex.graph.GraphException(com.agentsflex.graph.error.GraphErrorCode.INVALID_ARGUMENT,
                "Read-only graph options reject native " + query.getKind() + " query");
        }
        return openCursor(new Neo4jCypherCompiler().compile(query), options);
    }

    /**
     * 返回可移植查询的 Neo4j 执行计划。
     */
    @Override
    public GraphExplainResult explain(GraphQuery query, GraphOptions options) {
        if (!(query instanceof TraversalQuery) && !(query instanceof GraphUnionQuery)
            && !(query instanceof GraphOptionalQuery)) {
            throw new IllegalArgumentException("Unsupported portable Neo4j query: " + query.getClass().getName());
        }
        Neo4jCypherCompiler compiler = new Neo4jCypherCompiler();
        return explainCompiled(query instanceof TraversalQuery ? compiler.compile((TraversalQuery) query)
            : query instanceof GraphUnionQuery ? compiler.compile((GraphUnionQuery) query)
            : compiler.compile((GraphOptionalQuery) query), options);
    }

    /**
     * 返回原生 Cypher 的 Neo4j 执行计划。
     */
    @Override
    public GraphExplainResult explain(NativeGraphQuery query, GraphOptions options) {
        return explainCompiled(new Neo4jCypherCompiler().compile(query), options);
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
     * 打开驱动结果流，并把 Session 生命周期转交给返回的游标。
     */
    private GraphResultCursor openCursor(Neo4jCypherCompiler.Compiled compiled, GraphOptions options) {
        long started = System.currentTimeMillis();
        GraphOptions resolved = options == null ? GraphOptions.DEFAULT : options;
        try {
            if (runner != null) {
                return new Neo4jResultCursor(runner.run(compiled.statement, compiled.parameters), null,
                    resolved.getMaxRecords(), started);
            }
            if (driver == null) throw new IllegalStateException("Neo4j executor is not bound to a driver");
            Session session = driver.session(sessionConfig(resolved));
            try {
                Result result = session.run(compiled.statement, compiled.parameters,
                    Neo4jGraphStore.transactionConfig(resolved));
                return new Neo4jResultCursor(result, session, resolved.getMaxRecords(), started);
            } catch (RuntimeException error) {
                session.close();
                throw error;
            }
        } catch (RuntimeException error) {
            throw queryFailure("Neo4j cursor query failed", error);
        }
    }

    /**
     * 通过 EXPLAIN 执行查询编译，不产生写入副作用。
     */
    private GraphExplainResult explainCompiled(Neo4jCypherCompiler.Compiled compiled, GraphOptions options) {
        GraphOptions resolved = options == null ? GraphOptions.DEFAULT : options;
        String statement = "EXPLAIN " + compiled.statement;
        try {
            org.neo4j.driver.summary.ResultSummary summary;
            if (runner != null) {
                summary = runner.run(statement, compiled.parameters).consume();
            } else {
                if (driver == null) throw new IllegalStateException("Neo4j executor is not bound to a driver");
                try (Session session = driver.session(sessionConfig(resolved))) {
                    summary = session.run(statement, compiled.parameters,
                        Neo4jGraphStore.transactionConfig(resolved)).consume();
                }
            }
            org.neo4j.driver.summary.Plan plan = summary.hasPlan() ? summary.plan() : null;
            Map<String, Object> details = plan == null
                ? Collections.<String, Object>emptyMap() : planDetails(plan);
            return new GraphExplainResult("neo4j", plan == null ? "" : planText(plan, 0), details);
        } catch (RuntimeException error) {
            throw queryFailure("Neo4j explain failed", error);
        }
    }

    /**
     * 按本次选项创建 Neo4j 会话配置。
     */
    private org.neo4j.driver.SessionConfig sessionConfig(GraphOptions options) {
        String database = options.getSpaceOrDefault(config.getDefaultSpace());
        return org.neo4j.driver.SessionConfig.builder().withDatabase(database)
            .withFetchSize(options.getFetchSize()).build();
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
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        while (records.size() < maxRecords && result.hasNext()) {
            records.add(convert(result.next(), nodes, edges));
        }
        boolean truncated = result.hasNext();
        GraphResultMetadata metadata = new GraphResultMetadata(records.size(), truncated,
            Math.max(0L, System.currentTimeMillis() - started));
        GraphSubgraphResult subgraph = nodes.isEmpty() && edges.isEmpty() ? null
            : new GraphSubgraphResult(nodes, edges, metadata);
        return new GraphResult(records, statement, metadata, subgraph);
    }

    /**
     * 将 Neo4j 驱动记录转换为稳定的 SDK 记录。
     */
    private static GraphRecord convert(Record record) {
        return convert(record, new ArrayList<GraphNode>(), new ArrayList<GraphEdge>());
    }

    /**
     * 转换记录并收集其中的 Neo4j 节点、边，供图结构结果使用。
     */
    private static GraphRecord convert(Record record, List<GraphNode> nodes, List<GraphEdge> edges) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (String key : record.keys()) {
            Value value = record.get(key);
            values.put(key, convertValue(value, nodes, edges));
        }
        return new GraphRecord(values);
    }

    /**
     * 归一化 Neo4j 实体，避免上层直接依赖驱动类型。
     */
    private static Object convertValue(Value value, List<GraphNode> nodes, List<GraphEdge> edges) {
        if (value == null || value.isNull()) return null;
        String type = value.type().name();
        if ("NODE".equalsIgnoreCase(type)) {
            GraphNode result = toNode(value.asNode());
            nodes.add(result);
            return result;
        }
        if ("RELATIONSHIP".equalsIgnoreCase(type)) {
            GraphEdge result = toEdge(value.asRelationship());
            edges.add(result);
            return result;
        }
        if ("PATH".equalsIgnoreCase(type)) {
            List<GraphNode> pathNodes = new ArrayList<>();
            List<GraphEdge> pathEdges = new ArrayList<>();
            for (org.neo4j.driver.types.Node node : value.asPath().nodes()) {
                GraphNode converted = toNode(node);
                pathNodes.add(converted);
                nodes.add(converted);
            }
            for (org.neo4j.driver.types.Relationship edge : value.asPath().relationships()) {
                GraphEdge converted = toEdge(edge);
                pathEdges.add(converted);
                edges.add(converted);
            }
            return new GraphSubgraphResult(pathNodes, pathEdges, null);
        }
        if ("LIST".equalsIgnoreCase(type)) {
            return value.asList(item -> convertValue(item, nodes, edges));
        }
        return value.asObject();
    }

    /**
     * 将 Neo4j 节点映射为 SDK 节点。
     */
    private static GraphNode toNode(org.neo4j.driver.types.Node node) {
        String id = node.containsKey("__agentsflex_id") && !node.get("__agentsflex_id").isNull()
            ? node.get("__agentsflex_id").asString() : String.valueOf(node.id());
        GraphNode.Builder builder = GraphNode.builder(id, firstLabel(node));
        boolean first = true;
        for (String label : node.labels()) {
            if (first) {
                first = false;
                continue;
            }
            builder.label(label);
        }
        builder.properties(node.asMap());
        return builder.build();
    }

    /**
     * 将 Neo4j 关系映射为 SDK 边；端点使用驱动稳定 elementId。
     */
    private static GraphEdge toEdge(org.neo4j.driver.types.Relationship relationship) {
        return GraphEdge.builder(String.valueOf(relationship.startNodeId()), relationship.type(),
            String.valueOf(relationship.endNodeId())).properties(relationship.asMap()).build();
    }

    /**
     * 节点没有标签时使用可移植的占位标签，保证 GraphNode 模型始终有效。
     */
    private static String firstLabel(org.neo4j.driver.types.Node node) {
        for (String label : node.labels()) return label;
        return "_Node";
    }

    /**
     * 把驱动异常归一化为稳定的查询错误分类。
     */
    private static com.agentsflex.graph.GraphException queryFailure(String message, RuntimeException error) {
        if (error instanceof com.agentsflex.graph.GraphException) {
            return (com.agentsflex.graph.GraphException) error;
        }
        com.agentsflex.graph.error.GraphErrorCode code = isTimeout(error)
            ? com.agentsflex.graph.error.GraphErrorCode.QUERY_TIMEOUT
            : com.agentsflex.graph.error.GraphErrorCode.QUERY_FAILED;
        return new com.agentsflex.graph.GraphException(code,
            message + ": " + error.getMessage(), error);
    }

    /**
     * Neo4j 4.x 不同驱动异常类型不统一，兼容按消息识别超时。
     */
    private static boolean isTimeout(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase();
        return message.contains("timeout") || message.contains("timed out")
            || message.contains("deadline");
    }

    /**
     * 将 Neo4j 计划树转换为可序列化详情。
     */
    private static Map<String, Object> planDetails(org.neo4j.driver.summary.Plan plan) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operator", plan.operatorType());
        details.put("identifiers", new ArrayList<>(plan.identifiers()));
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : plan.arguments().entrySet()) {
            Value value = entry.getValue();
            arguments.put(entry.getKey(), value == null || value.isNull() ? null : value.asObject());
        }
        details.put("arguments", arguments);
        List<Map<String, Object>> children = new ArrayList<>();
        for (org.neo4j.driver.summary.Plan child : plan.children()) children.add(planDetails(child));
        details.put("children", children);
        return details;
    }

    /**
     * 生成人类可读的缩进计划文本，便于日志和简单查询工作台展示。
     */
    private static String planText(org.neo4j.driver.summary.Plan plan, int depth) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < depth; i++) text.append("  ");
        text.append(plan.operatorType());
        for (org.neo4j.driver.summary.Plan child : plan.children()) {
            text.append('\n').append(planText(child, depth + 1));
        }
        return text.toString();
    }

    /**
     * Neo4j 流式结果游标，动态返回已消费条数和截断状态。
     */
    private static final class Neo4jResultCursor implements GraphResultCursor {
        private final Result result;
        private final Session session;
        private final int maxRecords;
        private final long started;
        private int count;
        private boolean truncated;
        private boolean closed;

        private Neo4jResultCursor(Result result, Session session, int maxRecords, long started) {
            this.result = result;
            this.session = session;
            this.maxRecords = maxRecords;
            this.started = started;
        }

        @Override
        public boolean hasNext() {
            if (closed) return false;
            try {
                if (count >= maxRecords) {
                    truncated = result.hasNext();
                    return false;
                }
                return result.hasNext();
            } catch (RuntimeException error) {
                close();
                throw queryFailure("Neo4j cursor iteration failed", error);
            }
        }

        @Override
        public GraphRecord next() {
            if (!hasNext()) throw new NoSuchElementException("graph result cursor is exhausted or closed");
            try {
                count++;
                return convert(result.next());
            } catch (RuntimeException error) {
                close();
                throw queryFailure("Neo4j cursor iteration failed", error);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("cursor is read-only");
        }

        @Override
        public GraphResultMetadata getMetadata() {
            if (!closed && count >= maxRecords) truncated = result.hasNext();
            return new GraphResultMetadata(count, truncated,
                Math.max(0L, System.currentTimeMillis() - started));
        }

        @Override
        public void close() {
            if (closed) return;
            if (count >= maxRecords) {
                try {
                    truncated = result.hasNext();
                } catch (RuntimeException ignored) {
                }
            }
            closed = true;
            if (session != null) session.close();
        }
    }
}
