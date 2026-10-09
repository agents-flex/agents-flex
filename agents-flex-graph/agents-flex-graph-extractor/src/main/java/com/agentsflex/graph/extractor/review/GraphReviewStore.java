package com.agentsflex.graph.extractor.review;

import java.util.List;

/**
 * 审核任务快照存储扩展点。
 *
 * <p>自动接受不需要此接口。只有应用选择人工审核时，才创建 {@link GraphReviewService} 并注入实现。
 * 生产实现应将 {@link #update(GraphReviewTask, long)} 做成带版本条件的原子更新。</p>
 */
public interface GraphReviewStore {
    /**
     * 创建一条新的审核任务。
     */
    GraphReviewTask create(GraphReviewTask task);

    /**
     * 按 taskId 查询任务，不存在时返回 {@code null}。
     */
    GraphReviewTask get(String taskId);

    /**
     * 按条件查询任务，结果应按更新时间倒序返回。
     */
    List<GraphReviewTask> list(GraphReviewTaskQuery query);

    /**
     * 以乐观锁版本更新任务。
     *
     * @param task                  新任务快照
     * @param expectedReviewVersion 更新前的版本
     * @return 更新成功后的任务；版本冲突时抛出异常
     */
    GraphReviewTask update(GraphReviewTask task, long expectedReviewVersion);
}
