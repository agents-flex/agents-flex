package com.agentsflex.graph.extractor.mapping;

import com.agentsflex.graph.extractor.GraphExtractionException;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.mutation.GraphMutation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将已校验关系和实体解析结果转换为可提交给 GraphWriter 的变更批次。
 *
 * <p>映射器只生成内存中的 GraphMutation，不会访问或写入图数据库。调用方仍可在人工审核、
 * 权限检查或批次审批后决定是否通过 GraphWriter 提交。</p>
 */
public class GraphCandidateMutationMapper {
    /**
     * 映射节点和边，并按 GraphEdgeKey 去除分段重叠产生的重复关系。
     *
     * @param resolution 归一后的节点和候选键映射
     * @param relations  已通过 Schema 校验的关系
     * @return 只包含 upsert 的 GraphMutation
     */
    public GraphMutation map(GraphEntityResolutionResult resolution, List<GraphRelationCandidate> relations) {
        if (resolution == null || relations == null)
            throw new IllegalArgumentException("resolution and relations must not be null");
        Map<GraphEdgeKey, GraphEdge> edges = new LinkedHashMap<>();
        Map<GraphEdgeKey, Double> confidences = new LinkedHashMap<>();
        for (GraphRelationCandidate relation : relations) {
            if (relation == null) throw new IllegalArgumentException("relations must not contain null elements");
            // 关系仍引用候选键，必须通过实体归一结果转换成最终数据库节点 ID。
            String source = resolution.nodeId(relation.getSourceCandidateKey());
            String target = resolution.nodeId(relation.getTargetCandidateKey());
            if (source == null || target == null) {
                throw new GraphExtractionException("Resolved node is missing for relation endpoint: "
                    + relation.getSourceCandidateKey() + " -> " + relation.getTargetCandidateKey());
            }
            GraphEdge edge = GraphEdge.builder(source, relation.getType(), target)
                .rank(relation.getRank()).properties(relation.getProperties()).build();
            Double existingConfidence = confidences.get(edge.getKey());
            if (existingConfidence == null || relation.getConfidence() > existingConfidence) {
                // 重叠分段可能生成同键但属性不同的关系；优先保留置信度更高者，相同置信度保留首次值。
                edges.put(edge.getKey(), edge);
                confidences.put(edge.getKey(), relation.getConfidence());
            }
        }
        return GraphMutation.builder().upsertNodes(resolution.getNodes()).upsertEdges(edges.values()).build();
    }
}
