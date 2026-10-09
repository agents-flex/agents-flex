package com.agentsflex.graph.extractor.ingestion;

import com.agentsflex.graph.mutation.GraphWriteResult;

/**
 * 入图计划执行后的不可变结果。
 */
public final class GraphIngestionResult {
    /**
     * 已执行或跳过的计划。
     */
    private final GraphIngestionPlan plan;
    /**
     * 图写入结果；无变更时为零影响成功结果。
     */
    private final GraphWriteResult writeResult;
    /**
     * 文档状态是否已经提交或确认无需变化。
     */
    private final boolean stateCommitted;

    /**
     * 创建执行结果。
     */
    public GraphIngestionResult(GraphIngestionPlan plan, GraphWriteResult writeResult,
                                           boolean stateCommitted) {
        if (plan == null || writeResult == null)
            throw new IllegalArgumentException("plan and writeResult must not be null");
        this.plan = plan;
        this.writeResult = writeResult;
        this.stateCommitted = stateCommitted;
    }

    /**
     * @return 对应执行计划。
     */
    public GraphIngestionPlan getPlan() {
        return plan;
    }

    /**
     * @return 图写入结果。
     */
    public GraphWriteResult getWriteResult() {
        return writeResult;
    }

    /**
     * @return 状态是否完成提交。
     */
    public boolean isStateCommitted() {
        return stateCommitted;
    }

    /**
     * @return 图写入成功且状态已经提交。
     */
    public boolean isSuccess() {
        return writeResult.isSuccess() && stateCommitted;
    }
}
