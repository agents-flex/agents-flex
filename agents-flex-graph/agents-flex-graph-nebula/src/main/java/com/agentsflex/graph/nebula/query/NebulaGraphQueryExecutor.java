package com.agentsflex.graph.nebula.query;

import com.agentsflex.graph.query.GraphQuery;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.query.GraphExplainResult;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphSubgraphResult;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将统一查询编译为 nGQL，并转换 Nebula ResultSet。
 */
public final class NebulaGraphQueryExecutor implements GraphQueryExecutor {
    /**
     * 所属存储。
     */
    private final NebulaGraphStore store;
    /**
     * 默认空间配置。
     */
    private final NebulaGraphStoreConfig config;

    /**
     * 创建查询执行器。
     */
    public NebulaGraphQueryExecutor(NebulaGraphStore store, NebulaGraphStoreConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 编译并执行可移植遍历查询。
     */
    @Override
    public GraphResult execute(GraphQuery query, com.agentsflex.graph.GraphOptions options) {
        if (!(query instanceof TraversalQuery) && !(query instanceof GraphUnionQuery))
            throw new IllegalArgumentException("Unsupported portable Nebula query: " + query.getClass().getName());
        NebulaNqlCompiler compiler = new NebulaNqlCompiler();
        return run(query instanceof TraversalQuery ? compiler.compile((TraversalQuery) query)
            : compiler.compile((GraphUnionQuery) query), options);
    }

    /**
     * 执行原生 nGQL。
     */
    @Override
    public GraphResult execute(NativeGraphQuery query, com.agentsflex.graph.GraphOptions options) {
        if (options != null && options.isReadOnly() && !query.isReadOnly()) {
            throw new com.agentsflex.graph.GraphException(com.agentsflex.graph.error.GraphErrorCode.INVALID_ARGUMENT,
                "Read-only graph options reject native " + query.getKind() + " query");
        }
        return run(new NebulaNqlCompiler().compile(query), options);
    }

    /**
     * 执行 Nebula EXPLAIN 并返回结构化的计划行。
     */
    @Override
    public GraphExplainResult explain(GraphQuery query, com.agentsflex.graph.GraphOptions options) {
        if (!(query instanceof TraversalQuery) && !(query instanceof GraphUnionQuery))
            throw new IllegalArgumentException("Unsupported portable Nebula query: " + query.getClass().getName());
        NebulaNqlCompiler compiler = new NebulaNqlCompiler();
        return explainCompiled(query instanceof TraversalQuery ? compiler.compile((TraversalQuery) query)
            : compiler.compile((GraphUnionQuery) query), options);
    }

    /**
     * 执行原生 nGQL 的 EXPLAIN。
     */
    @Override
    public GraphExplainResult explain(NativeGraphQuery query, com.agentsflex.graph.GraphOptions options) {
        return explainCompiled(new NebulaNqlCompiler().compile(query), options);
    }

    /**
     * 在目标空间会话池执行并物化结果。
     */
    private GraphResult run(NebulaNqlCompiler.Compiled compiled, com.agentsflex.graph.GraphOptions options) {
        long started = System.currentTimeMillis();
        try {
            com.agentsflex.graph.GraphOptions resolved = options == null
                ? com.agentsflex.graph.GraphOptions.DEFAULT : options;
            ResultSet result = store.pool(resolved.getSpaceOrDefault(config.getDefaultSpace()))
                .execute(compiled.statement, compiled.parameters);
            if (!result.isSucceeded())
                throw new IllegalStateException("Nebula query failed: " + result.getErrorMessage());
            List<GraphRecord> records = new ArrayList<>();
            List<GraphNode> nodes = new ArrayList<>();
            List<GraphEdge> edges = new ArrayList<>();
            List<String> columns = result.getColumnNames();
            int count = Math.min(result.rowsSize(), resolved.getMaxRecords());
            for (int i = 0; i < count; i++) {
                ResultSet.Record row = result.rowValues(i);
                LinkedHashMap<String, Object> values = new LinkedHashMap<>();
                for (String column : columns) values.put(column, convert(row.get(column), nodes, edges));
                records.add(new GraphRecord(values));
            }
            GraphResultMetadata metadata = new GraphResultMetadata(records.size(), result.rowsSize() > count,
                Math.max(0L, System.currentTimeMillis() - started));
            GraphSubgraphResult subgraph = nodes.isEmpty() && edges.isEmpty() ? null
                : new GraphSubgraphResult(nodes, edges, metadata);
            return new GraphResult(records, compiled.statement, metadata, subgraph);
        } catch (Exception e) {
            com.agentsflex.graph.error.GraphErrorCode code = isTimeout(e)
                ? com.agentsflex.graph.error.GraphErrorCode.QUERY_TIMEOUT
                : com.agentsflex.graph.error.GraphErrorCode.QUERY_FAILED;
            throw new com.agentsflex.graph.GraphException(code,
                "Nebula query failed: " + e.getMessage(), e);
        }
    }

    /**
     * Nebula 的 ResultSet 本身是物化的，EXPLAIN 结果以行结构返回。
     */
    private GraphExplainResult explainCompiled(NebulaNqlCompiler.Compiled compiled,
                                               com.agentsflex.graph.GraphOptions options) {
        try {
            com.agentsflex.graph.GraphOptions resolved = options == null
                ? com.agentsflex.graph.GraphOptions.DEFAULT : options;
            String statement = "EXPLAIN " + compiled.statement;
            ResultSet result = store.pool(resolved.getSpaceOrDefault(config.getDefaultSpace()))
                .execute(statement, compiled.parameters);
            if (!result.isSucceeded())
                throw new IllegalStateException(result.getErrorMessage());
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> columns = result.getColumnNames();
            int count = Math.min(result.rowsSize(), resolved.getMaxRecords());
            for (int i = 0; i < count; i++) {
                ResultSet.Record row = result.rowValues(i);
                Map<String, Object> values = new LinkedHashMap<>();
                for (String column : columns) values.put(column, convert(row.get(column)));
                rows.add(values);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rows", rows);
            details.put("truncated", result.rowsSize() > count);
            return new GraphExplainResult("nebula", rows.toString(), details);
        } catch (Exception error) {
            com.agentsflex.graph.error.GraphErrorCode code = isTimeout(error)
                ? com.agentsflex.graph.error.GraphErrorCode.QUERY_TIMEOUT
                : com.agentsflex.graph.error.GraphErrorCode.QUERY_FAILED;
            throw new com.agentsflex.graph.GraphException(code,
                "Nebula explain failed: " + error.getMessage(), error);
        }
    }

    /**
     * 统一识别客户端或服务端返回的超时文本。
     */
    private boolean isTimeout(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).toLowerCase();
        return message.contains("timeout") || message.contains("timed out")
            || message.contains("deadline");
    }

    /**
     * 将 Nebula ValueWrapper 转换为 Java 基础值或列表。
     */
    private Object convert(ValueWrapper value) throws UnsupportedEncodingException {
        return convert(value, new ArrayList<GraphNode>(), new ArrayList<GraphEdge>());
    }

    /**
     * 归一化 Nebula 基础值和图实体，并同步收集子图。
     */
    private Object convert(ValueWrapper value, List<GraphNode> nodes, List<GraphEdge> edges)
        throws UnsupportedEncodingException {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isLong()) return value.asLong();
        if (value.isDouble()) return value.asDouble();
        if (value.isString()) return value.asString();
        if (value.isList()) {
            List<Object> result = new ArrayList<>();
            for (ValueWrapper item : value.asList()) result.add(convert(item, nodes, edges));
            return result;
        }
        if (value.isMap()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, ValueWrapper> entry : value.asMap().entrySet()) {
                result.put(entry.getKey(), convert(entry.getValue(), nodes, edges));
            }
            return result;
        }
        if (value.isVertex()) {
            GraphNode node = toNode(value.asNode());
            nodes.add(node);
            return node;
        }
        if (value.isEdge()) {
            GraphEdge edge = toEdge(value.asRelationship());
            edges.add(edge);
            return edge;
        }
        if (value.isPath()) {
            List<GraphNode> pathNodes = new ArrayList<>();
            List<GraphEdge> pathEdges = new ArrayList<>();
            for (com.vesoft.nebula.client.graph.data.Node item : value.asPath().getNodes()) {
                GraphNode node = toNode(item);
                pathNodes.add(node);
                nodes.add(node);
            }
            for (com.vesoft.nebula.client.graph.data.Relationship item : value.asPath().getRelationships()) {
                GraphEdge edge = toEdge(item);
                pathEdges.add(edge);
                edges.add(edge);
            }
            return new GraphSubgraphResult(pathNodes, pathEdges, null);
        }
        return value.toString();
    }

    /**
     * 将 Nebula Vertex 转换为 SDK 节点。
     */
    private GraphNode toNode(com.vesoft.nebula.client.graph.data.Node node) throws UnsupportedEncodingException {
        List<String> labels = node.labels();
        String first = labels.isEmpty() ? "_Node" : labels.get(0);
        Object id = convert(node.getId());
        GraphNode.Builder builder = GraphNode.builder(String.valueOf(id), first);
        for (int i = 1; i < labels.size(); i++) builder.label(labels.get(i));
        for (String label : labels) {
            for (Map.Entry<String, ValueWrapper> property : node.properties(label).entrySet()) {
                builder.property(property.getKey(), convert(property.getValue()));
            }
        }
        return builder.build();
    }

    /**
     * 将 Nebula Edge 转换为 SDK 边。
     */
    private GraphEdge toEdge(com.vesoft.nebula.client.graph.data.Relationship edge)
        throws UnsupportedEncodingException {
        GraphEdge.Builder builder = GraphEdge.builder(String.valueOf(convert(edge.srcId())), edge.edgeName(),
            String.valueOf(convert(edge.dstId()))).rank(edge.ranking());
        for (Map.Entry<String, ValueWrapper> property : edge.properties().entrySet()) {
            builder.property(property.getKey(), convert(property.getValue()));
        }
        return builder.build();
    }
}
