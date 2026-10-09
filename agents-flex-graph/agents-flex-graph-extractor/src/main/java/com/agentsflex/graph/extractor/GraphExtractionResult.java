package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.mutation.GraphMutation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 完整文档抽取后的候选事实、实体归一结果和待写入变更。
 *
 * <p>结果本身不会修改数据库。调用方可以审核 issues、候选证据和 GraphMutation，确认后再通过
 * GraphWriter.mutate 或 GraphImportService 持久化。validatedEntities 和 validatedRelations
 * 仅表示通过校验的候选，不代表人工审核已接受，也不代表已经入图。</p>
 */
public final class GraphExtractionResult {
    /**
     * 模型返回的全部候选实体，包括未通过校验的候选。
     */
    private final List<GraphEntityCandidate> allEntities;
    /**
     * 模型返回的全部候选关系，包括未通过校验的候选。
     */
    private final List<GraphRelationCandidate> allRelations;
    /**
     * 通过校验的候选实体；人工修改后保存重新校验的当前候选子集。
     */
    private final List<GraphEntityCandidate> validatedEntities;
    /**
     * 通过校验的候选关系；人工拒绝或修改后保存当前有效子集。
     */
    private final List<GraphRelationCandidate> validatedRelations;
    /**
     * 跨分段实体归一结果。
     */
    private final GraphEntityResolutionResult entityResolution;
    /**
     * 可直接提交给 GraphWriter 的变更批次。
     */
    private final GraphMutation mutation;
    /**
     * 解析和校验阶段的完整问题列表。
     */
    private final List<GraphExtractionIssue> issues;
    /**
     * 各分段的模型原始响应。
     */
    private final List<String> rawResponses;

    /**
     * 创建不可变完整抽取结果。
     *
     * <p>all* 集合保留模型或解析器返回的完整候选，validatedEntities/validatedRelations 集合只保留通过 Schema
     * 和质量校验的子集。entityResolution 与 mutation 即使结果包含 ERROR 也会生成，调用方应先检查
     * issues 再按审核策略提交，而不是把 GraphMutation 当作已写入数据库的凭证。</p>
     */
    public GraphExtractionResult(List<GraphEntityCandidate> allEntities, List<GraphRelationCandidate> allRelations,
                                 List<GraphEntityCandidate> validatedEntities, List<GraphRelationCandidate> validatedRelations,
                                 GraphEntityResolutionResult entityResolution, GraphMutation mutation,
                                 List<GraphExtractionIssue> issues, List<String> rawResponses) {
        this.allEntities = immutable(allEntities);
        this.allRelations = immutable(allRelations);
        this.validatedEntities = immutable(validatedEntities);
        this.validatedRelations = immutable(validatedRelations);
        if (entityResolution == null || mutation == null)
            throw new IllegalArgumentException("entityResolution and mutation must not be null");
        this.entityResolution = entityResolution;
        this.mutation = mutation;
        this.issues = immutable(issues);
        this.rawResponses = immutable(rawResponses);
    }

    /**
     * @return 模型返回的全部候选实体，适合人工审核、回放和离线评估。
     */
    public List<GraphEntityCandidate> getAllEntities() {
        return allEntities;
    }

    /**
     * @return 模型返回的全部候选关系，包含可能引用坏实体的关系。
     */
    public List<GraphRelationCandidate> getAllRelations() {
        return allRelations;
    }

    /**
     * @return 通过 Schema、证据、置信度和属性校验的只读候选实体，不代表审核已接受。
     */
    public List<GraphEntityCandidate> getValidatedEntities() {
        return validatedEntities;
    }

    /**
     * @return 通过 Schema、端点、断言和质量校验的只读候选关系，不代表已经入图。
     */
    public List<GraphRelationCandidate> getValidatedRelations() {
        return validatedRelations;
    }

    /**
     * @return 跨 Chunk 名称归一和候选键映射结果。
     */
    public GraphEntityResolutionResult getEntityResolution() {
        return entityResolution;
    }

    /**
     * @return 待审核、可交给 GraphWriter 的内存变更批次，不代表已持久化。
     */
    public GraphMutation getMutation() {
        return mutation;
    }

    /**
     * @return 解析、校验和 Chunk 容错阶段产生的全部结构化问题。
     */
    public List<GraphExtractionIssue> getIssues() {
        return issues;
    }

    /**
     * @return 按 Chunk 顺序保存的模型原始响应，可能含敏感原文。
     */
    public List<String> getRawResponses() {
        return rawResponses;
    }

    /**
     * @return 至少包含一个 ERROR 级别问题时为 {@code true}。
     */
    public boolean hasErrors() {
        for (GraphExtractionIssue issue : issues)
            if (issue.getSeverity() == GraphExtractionIssue.Severity.ERROR) return true;
        return false;
    }

    /**
     * 创建只读列表快照，确保结果不会被外部集合后续修改。
     */
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.<T>emptyList() : values));
    }
}
