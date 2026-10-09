package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;

import java.util.List;

/**
 * Schema 校验后的合法候选结果及完整问题列表。
 *
 * <p>被拒绝候选不会出现在 validatedCandidates 的实体或关系集合中，但拒绝原因会追加到该结果的问题
 * 列表；调用方仍可从最终 GraphExtractionResult 的 allEntities/allRelations 查看原始候选。</p>
 */
public final class GraphCandidateValidationResult {
    /**
     * 仅包含通过校验候选项的结果。
     */
    private final GraphCandidateResult validatedCandidates;

    /**
     * @param validatedCandidates 只包含合法候选并携带完整问题列表的非空结果
     */
    public GraphCandidateValidationResult(GraphCandidateResult validatedCandidates) {
        if (validatedCandidates == null) throw new IllegalArgumentException("validatedCandidates result must not be null");
        this.validatedCandidates = validatedCandidates;
    }

    /**
     * @return 通过校验的候选结果。
     */
    public GraphCandidateResult getValidatedCandidates() {
        return validatedCandidates;
    }

    /**
     * @return 解析和校验阶段的全部问题。
     */
    public List<GraphExtractionIssue> getIssues() {
        return validatedCandidates.getIssues();
    }
}
