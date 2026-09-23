package com.agentsflex.graph.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 图查询物化后的结果集。
 */
public final class GraphResult {
    /**
     * 查询记录。
     */
    private final List<GraphRecord> records;
    /**
     * 实际执行的后端查询文本，便于审计和调试。
     */
    private final String queryText;
    /**
     * 查询执行与物化元数据。
     */
    private final GraphResultMetadata metadata;
    /**
     * 可选的节点/边结构化结果。
     */
    private final GraphSubgraphResult subgraph;

    /**
     * 创建结果集并冻结记录列表。
     */
    public GraphResult(List<GraphRecord> records, String queryText) {
        this(records, queryText, null);
    }

    /**
     * 创建包含执行元数据的结果集。
     */
    public GraphResult(List<GraphRecord> records, String queryText, GraphResultMetadata metadata) {
        this(records, queryText, metadata, null);
    }

    /**
     * 创建同时包含表格记录和图结构结果的结果集。
     */
    public GraphResult(List<GraphRecord> records, String queryText, GraphResultMetadata metadata,
                       GraphSubgraphResult subgraph) {
        this.records = Collections.unmodifiableList(new ArrayList<>(records == null
            ? Collections.<GraphRecord>emptyList() : records));
        this.queryText = queryText;
        this.metadata = metadata == null
            ? new GraphResultMetadata(this.records.size(), false, 0L) : metadata;
        this.subgraph = subgraph;
    }

    /**
     * @return 只读记录列表
     */
    public List<GraphRecord> getRecords() {
        return records;
    }

    /**
     * @return 实际查询文本
     */
    public String getQueryText() {
        return queryText;
    }

    /**
     * @return 查询执行与物化元数据
     */
    public GraphResultMetadata getMetadata() {
        return metadata;
    }

    /**
     * @return 可选的图结构结果；普通表格查询时为 {@code null}。
     */
    public GraphSubgraphResult getSubgraph() {
        return subgraph;
    }

    /**
     * 创建带下一页 token 的结果快照，不改变原结果对象。
     */
    public GraphResult withNextCursor(String cursor) {
        return new GraphResult(records, queryText, metadata.withNextCursor(cursor), subgraph);
    }

    /**
     * 截取分页结果并重建结构化子图，避免 look-ahead 记录泄漏到调用方。
     */
    public GraphResult forPage(int maxRecords, boolean truncated, String nextCursor) {
        if (maxRecords <= 0) throw new IllegalArgumentException("maxRecords must be positive");
        int size = Math.min(maxRecords, records.size());
        List<GraphRecord> pageRecords = records.subList(0, size);
        GraphResultMetadata pageMetadata = metadata.forPage(size, truncated, nextCursor);
        GraphSubgraphResult pageSubgraph = subgraph == null ? null : collectSubgraph(pageRecords, pageMetadata);
        return new GraphResult(pageRecords, queryText, pageMetadata, pageSubgraph);
    }

    private static GraphSubgraphResult collectSubgraph(List<GraphRecord> values, GraphResultMetadata metadata) {
        List<com.agentsflex.graph.data.GraphNode> nodes = new ArrayList<>();
        List<com.agentsflex.graph.data.GraphEdge> edges = new ArrayList<>();
        for (GraphRecord record : values) collect(record.getValues(), nodes, edges);
        if (nodes.isEmpty() && edges.isEmpty()) return null;
        return new GraphSubgraphResult(nodes, edges, metadata);
    }

    private static void collect(Object value, List<com.agentsflex.graph.data.GraphNode> nodes,
                                List<com.agentsflex.graph.data.GraphEdge> edges) {
        if (value instanceof com.agentsflex.graph.data.GraphNode)
            nodes.add((com.agentsflex.graph.data.GraphNode) value);
        else if (value instanceof com.agentsflex.graph.data.GraphEdge)
            edges.add((com.agentsflex.graph.data.GraphEdge) value);
        else if (value instanceof GraphSubgraphResult) {
            GraphSubgraphResult graph = (GraphSubgraphResult) value;
            nodes.addAll(graph.getNodes());
            edges.addAll(graph.getEdges());
        } else if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) collect(child, nodes, edges);
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) collect(child, nodes, edges);
        }
    }
}
