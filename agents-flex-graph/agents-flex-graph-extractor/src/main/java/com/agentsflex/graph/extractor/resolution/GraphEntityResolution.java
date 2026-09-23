package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.data.GraphNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨分段实体归一后的不可变节点集合，以及候选键到节点 ID 的映射。
 *
 * <p>候选关系仍使用 candidateKey 表示端点，GraphMutationMapper 通过该映射把端点替换为最终
 * GraphNode ID。所有被接收的候选实体都应出现在映射中。</p>
 */
public final class GraphEntityResolution {
    /**
     * 归一和去重后的节点。
     */
    private final List<GraphNode> nodes;
    /**
     * 每个候选实体最终对应的节点 ID。
     */
    private final Map<String, String> candidateToNodeId;

    /**
     * 创建不可变实体解析结果，并复制节点列表和候选映射。
     *
     * @param nodes             归一和去重后的节点
     * @param candidateToNodeId 每个候选实体键对应的最终节点 ID
     */
    public GraphEntityResolution(List<GraphNode> nodes, Map<String, String> candidateToNodeId) {
        if (nodes == null || candidateToNodeId == null) {
            throw new IllegalArgumentException("nodes and candidateToNodeId must not be null");
        }
        if (nodes.contains(null) || candidateToNodeId.containsKey(null) || candidateToNodeId.containsValue(null)) {
            throw new IllegalArgumentException("entity resolution must not contain null elements");
        }
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.candidateToNodeId = Collections.unmodifiableMap(new LinkedHashMap<>(candidateToNodeId));
    }

    /**
     * @return 去重后的只读 GraphNode 列表，顺序按首次聚合出现顺序。
     */
    public List<GraphNode> getNodes() {
        return nodes;
    }

    /**
     * @return 每个已接收候选键到最终 GraphNode ID 的只读映射。
     */
    public Map<String, String> getCandidateToNodeId() {
        return candidateToNodeId;
    }

    /**
     * @return 指定候选实体对应的节点 ID；不存在时为 {@code null}。
     */
    public String nodeId(String candidateKey) {
        return candidateToNodeId.get(candidateKey);
    }
}
