package com.agentsflex.graph.extractor.review;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionPlan;

import java.util.UUID;

/**
 * 一个可持久化的审核任务快照。
 *
 * <p>任务保存完整的 {@link GraphIngestionPlan}，因此审核者确认的内容可以在稍后原样执行，
 * 不需要重新调用模型。外部数据库实现可以将计划序列化后保存；SDK 的内存实现直接保存不可变对象引用。</p>
 */
public final class GraphReviewTask {
    /**
     * 审核任务稳定标识。
     */
    private final String taskId;
    /**
     * 当前冻结或人工修改后的入图计划。
     */
    private final GraphIngestionPlan plan;
    /**
     * 当前审核状态。
     */
    private final GraphReviewStatus status;
    /**
     * 审核任务乐观锁版本。
     */
    private final long reviewVersion;
    /**
     * 最近一次决策、修改或失败原因。
     */
    private final String reason;
    /**
     * 最近一次操作人或系统标识。
     */
    private final String actor;
    /**
     * 任务创建时间。
     */
    private final long createdAtMillis;
    /**
     * 任务最后更新时间。
     */
    private final long updatedAtMillis;

    private GraphReviewTask(String taskId, GraphIngestionPlan plan, GraphReviewStatus status,
                            long reviewVersion, String reason, String actor,
                            long createdAtMillis, long updatedAtMillis) {
        if (taskId == null || taskId.trim().isEmpty()) throw new IllegalArgumentException("taskId must not be blank");
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (reviewVersion < 0L) throw new IllegalArgumentException("reviewVersion must not be negative");
        if (createdAtMillis < 0L || updatedAtMillis < createdAtMillis) {
            throw new IllegalArgumentException("invalid task timestamps");
        }
        this.taskId = taskId.trim();
        this.plan = plan;
        this.status = status;
        this.reviewVersion = reviewVersion;
        this.reason = reason == null ? "" : reason;
        this.actor = actor == null ? "" : actor;
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    /**
     * 创建一条等待人工审核的新任务。
     */
    public static GraphReviewTask pending(GraphIngestionPlan plan, long nowMillis) {
        return pending("review-" + UUID.randomUUID(), plan, nowMillis);
    }

    /**
     * 使用调用方生成的稳定 taskId 创建等待审核任务。
     */
    public static GraphReviewTask pending(String taskId, GraphIngestionPlan plan, long nowMillis) {
        return new GraphReviewTask(taskId, plan, GraphReviewStatus.PENDING_REVIEW, 0L,
            "", "", nowMillis, nowMillis);
    }

    /**
     * 从持久化字段恢复审核任务。
     *
     * <p>数据库实现反序列化后应使用此方法，而不是通过反射调用私有构造器。恢复不会重置版本号，
     * 因此可以继续使用乐观锁保护审核者之间的并发操作。</p>
     */
    public static GraphReviewTask restore(String taskId, GraphIngestionPlan plan, GraphReviewStatus status,
                                          long reviewVersion, String reason, String actor,
                                          long createdAtMillis, long updatedAtMillis) {
        return new GraphReviewTask(taskId, plan, status, reviewVersion, reason, actor,
            createdAtMillis, updatedAtMillis);
    }

    GraphReviewTask transition(GraphReviewStatus next, GraphIngestionPlan nextPlan,
                               String nextReason, String nextActor, long nowMillis) {
        return new GraphReviewTask(taskId, nextPlan == null ? plan : nextPlan, next,
            reviewVersion + 1L, nextReason, nextActor, createdAtMillis, nowMillis);
    }

    /**
     * @return 应用层审核任务 ID。
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * @return 冻结或人工修改后的入图计划。
     */
    public GraphIngestionPlan getPlan() {
        return plan;
    }

    /**
     * @return 当前审核状态。
     */
    public GraphReviewStatus getStatus() {
        return status;
    }

    /**
     * @return 乐观锁版本。
     */
    public long getReviewVersion() {
        return reviewVersion;
    }

    /**
     * @return 最近一次决策或失败原因。
     */
    public String getReason() {
        return reason;
    }

    /**
     * @return 最近一次操作人或系统标识。
     */
    public String getActor() {
        return actor;
    }

    /**
     * @return 创建时间。
     */
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    /**
     * @return 最后更新时间。
     */
    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    /**
     * @return 计划中的稳定操作号。
     */
    public String getOperationId() {
        if (plan.getNextState() != null && !plan.getNextState().getOperationId().isEmpty()) {
            return plan.getNextState().getOperationId();
        }
        return plan.getMutation().getOperationId();
    }
}
