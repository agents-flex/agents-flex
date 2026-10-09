package com.agentsflex.graph.extractor.review;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionResult;

/**
 * 审核任务接受后的任务状态和入图执行结果。
 */
public final class GraphReviewExecutionResult {
    /**
     * 执行完成后的审核任务快照。
     */
    private final GraphReviewTask task;
    /**
     * 入图服务返回的执行结果。
     */
    private final GraphIngestionResult ingestionResult;

    GraphReviewExecutionResult(GraphReviewTask task, GraphIngestionResult ingestionResult) {
        if (task == null || ingestionResult == null) {
            throw new IllegalArgumentException("task and ingestionResult must not be null");
        }
        this.task = task;
        this.ingestionResult = ingestionResult;
    }

    /**
     * @return 执行完成后的审核任务。
     */
    public GraphReviewTask getTask() {
        return task;
    }

    /**
     * @return 入图执行结果。
     */
    public GraphIngestionResult getIngestionResult() {
        return ingestionResult;
    }

    /**
     * @return 图写入和文档状态是否均已成功。
     */
    public boolean isSuccess() {
        return ingestionResult.isSuccess() && task.getStatus() == GraphReviewTaskStatus.COMPLETED;
    }
}
