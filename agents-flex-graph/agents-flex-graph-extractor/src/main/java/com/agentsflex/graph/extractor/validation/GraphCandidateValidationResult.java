package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.extractor.GraphCandidateBatch;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;

import java.util.List;

/**
 * Schema 校验后的可接收候选批次及完整问题列表。
 *
 * <p>被拒绝候选不会出现在 accepted 的实体或关系集合中，但拒绝原因会追加到该批次的问题
 * 列表；调用方仍可从最终 GraphExtractionResult 的 allEntities/allRelations 查看原始候选。</p>
 */
public final class GraphCandidateValidationResult {
    /**
     * 仅包含通过校验候选项的批次。
     */
    private final GraphCandidateBatch accepted;

    /**
     * @param accepted 只包含合法候选并携带完整问题列表的非空批次
     */
    public GraphCandidateValidationResult(GraphCandidateBatch accepted) {
        if (accepted == null) throw new IllegalArgumentException("accepted batch must not be null");
        this.accepted = accepted;
    }

    /**
     * @return 通过校验的候选批次。
     */
    public GraphCandidateBatch getAccepted() {
        return accepted;
    }

    /**
     * @return 解析和校验阶段的全部问题。
     */
    public List<GraphExtractionIssue> getIssues() {
        return accepted.getIssues();
    }
}
