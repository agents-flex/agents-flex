package com.agentsflex.graph.query;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;

/**
 * 面向图探索和路径展示的结构化结果，而不是通用表格记录。
 */
public final class GraphSubgraphResult {
    /**
     * 去重后的节点集合，保持首次出现顺序。
     */
    private final List<GraphNode> nodes;
    /**
     * 去重后的边集合，保持首次出现顺序。
     */
    private final List<GraphEdge> edges;
    /**
     * 与该子图对应的结果元数据。
     */
    private final GraphResultMetadata metadata;

    /**
     * 创建结构化子图结果。
     *
     * <p>节点按业务 ID 去重，边按 {@link com.agentsflex.graph.data.GraphEdgeKey} 去重；输入集合中的
     * {@code null} 元素会被忽略。所有返回集合均为只读快照。</p>
     *
     * @param nodes    节点集合，可以为 {@code null}
     * @param edges    边集合，可以为 {@code null}
     * @param metadata 结果元数据，为 {@code null} 时根据节点和边数量生成默认值
     */
    public GraphSubgraphResult(List<GraphNode> nodes, List<GraphEdge> edges, GraphResultMetadata metadata) {
        LinkedHashMap<String, GraphNode> uniqueNodes = new LinkedHashMap<>();
        if (nodes != null) for (GraphNode node : nodes) if (node != null) uniqueNodes.put(node.getId(), node);
        LinkedHashMap<com.agentsflex.graph.data.GraphEdgeKey, GraphEdge> uniqueEdges = new LinkedHashMap<>();
        if (edges != null) for (GraphEdge edge : edges) if (edge != null) uniqueEdges.put(edge.getKey(), edge);
        this.nodes = Collections.unmodifiableList(new ArrayList<>(uniqueNodes.values()));
        this.edges = Collections.unmodifiableList(new ArrayList<>(uniqueEdges.values()));
        this.metadata = metadata == null ? new GraphResultMetadata(this.nodes.size() + this.edges.size(), false, 0L) : metadata;
    }

    /**
     * @return 去重后的只读节点列表。
     */
    public List<GraphNode> getNodes() {
        return nodes;
    }

    /**
     * @return 去重后的只读边列表。
     */
    public List<GraphEdge> getEdges() {
        return edges;
    }

    /**
     * @return 子图对应的查询元数据。
     */
    public GraphResultMetadata getMetadata() {
        return metadata;
    }
}
