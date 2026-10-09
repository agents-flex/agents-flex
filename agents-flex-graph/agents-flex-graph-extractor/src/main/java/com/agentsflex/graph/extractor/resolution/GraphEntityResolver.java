package com.agentsflex.graph.extractor.resolution;

import com.agentsflex.graph.extractor.model.GraphEntityCandidate;

import java.util.List;

/**
 * 将不同分段中的实体 mention 归并成稳定 GraphNode 的扩展点。
 *
 * <p>实现需要同时返回最终节点和每个候选键到节点 ID 的映射，后者用于把候选关系端点转换成
 * 数据库节点端点。需要处理同名消歧或增量图谱时，可在此接入已有实体注册表或检索服务。</p>
 */
public interface GraphEntityResolver {
    /**
     * 归一一个文档或预分段批次中的全部合法实体候选。
     *
     * @param candidates 已通过 Schema 校验的候选实体，按 Chunk 处理顺序排列
     * @return 归一节点和完整候选键映射
     */
    GraphEntityResolutionResult resolve(List<GraphEntityCandidate> candidates);
}
