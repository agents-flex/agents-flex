package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个文本分段的不可变候选知识抽取结果。
 *
 * <p>批次可以同时携带成功候选和局部解析问题。rawResponse 便于审核和离线评估，但可能包含
 * 原始业务文本，生产系统持久化或记录日志前应执行脱敏和访问控制。</p>
 */
public final class GraphCandidateBatch {
    /**
     * 候选实体。
     */
    private final List<GraphEntityCandidate> entities;
    /**
     * 候选关系。
     */
    private final List<GraphRelationCandidate> relations;
    /**
     * 解析阶段产生的问题。
     */
    private final List<GraphExtractionIssue> issues;
    /**
     * 模型原始响应。
     */
    private final String rawResponse;

    /**
     * 创建不可变候选批次，并对所有集合执行防御性复制。
     *
     * @param entities    当前 Chunk 的合法格式实体候选
     * @param relations   当前 Chunk 的合法格式关系候选
     * @param issues      解析或前置处理阶段产生的问题
     * @param rawResponse 模型原始响应；为 {@code null} 时保存为空字符串
     */
    public GraphCandidateBatch(List<GraphEntityCandidate> entities, List<GraphRelationCandidate> relations,
                               List<GraphExtractionIssue> issues, String rawResponse) {
        this.entities = immutable(entities, "entities");
        this.relations = immutable(relations, "relations");
        this.issues = immutable(issues, "issues");
        this.rawResponse = rawResponse == null ? "" : rawResponse;
    }

    /**
     * @return 候选实体。
     */
    public List<GraphEntityCandidate> getEntities() {
        return entities;
    }

    /**
     * @return 候选关系。
     */
    public List<GraphRelationCandidate> getRelations() {
        return relations;
    }

    /**
     * @return 抽取问题。
     */
    public List<GraphExtractionIssue> getIssues() {
        return issues;
    }

    /**
     * @return 模型原始响应。
     */
    public String getRawResponse() {
        return rawResponse;
    }

    /**
     * 将可选列表转换为不含 null 元素的不可变快照，避免错误延迟到后续流水线阶段。
     */
    private static <T> List<T> immutable(List<T> source, String name) {
        List<T> copy = new ArrayList<>(source == null ? Collections.<T>emptyList() : source);
        if (copy.contains(null)) throw new IllegalArgumentException(name + " must not contain null elements");
        return Collections.unmodifiableList(copy);
    }
}
