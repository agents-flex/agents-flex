package com.agentsflex.graph.extractor.review;

/**
 * 审核任务的生命周期状态，覆盖待审核、入图执行、完成、失败和归档。
 *
 * <p>该状态属于 Graph SDK 的审核任务，而不是图数据库事务状态。自动接受路径不会创建审核任务，
 * 因此也不会使用这些状态。</p>
 */
public enum GraphReviewTaskStatus {
    /**
     * 等待审核者处理。
     */
    PENDING_REVIEW,
    /**
     * 审核者修改了候选，等待再次确认。
     */
    CHANGES_REQUESTED,
    /**
     * 已进入图写入执行阶段。
     */
    EXECUTING,
    /**
     * 图写入和入图状态提交均已成功。
     */
    COMPLETED,
    /**
     * 审核者明确拒绝，不会执行图写入。
     */
    REJECTED,
    /**
     * 执行失败，可根据任务中的 operationId 恢复。
     */
    FAILED,
    /**
     * 任务已归档；审核列表通过 statuses 显式筛选待审核任务。
     */
    ARCHIVED
}
