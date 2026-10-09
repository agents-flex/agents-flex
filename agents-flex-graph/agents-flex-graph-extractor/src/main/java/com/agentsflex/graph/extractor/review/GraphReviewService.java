package com.agentsflex.graph.extractor.review;

import com.agentsflex.graph.extractor.ingestion.GraphIngestionPlan;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionResult;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionService;
import com.agentsflex.graph.mutation.GraphWriter;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * 审核任务门面。
 *
 * <p>该服务把审核状态变化和 {@link GraphIngestionService} 的计划执行连接起来。自动接受场景不需要创建
 * 本服务，也不需要配置 {@link GraphReviewStore}，直接调用入图服务即可。</p>
 */
public final class GraphReviewService {
    /**
     * 审核任务快照存储。
     */
    private final GraphReviewStore store;
    /**
     * 负责执行审核通过的入图计划。
     */
    private final GraphIngestionService ingestion;
    /**
     * 任务状态时间来源，便于测试和多环境控制。
     */
    private final LongSupplier clock;

    /**
     * 创建使用系统时钟的审核服务。
     */
    public GraphReviewService(GraphReviewStore store, GraphIngestionService ingestion) {
        this(store, ingestion, System::currentTimeMillis);
    }

    GraphReviewService(GraphReviewStore store, GraphIngestionService ingestion, LongSupplier clock) {
        if (store == null || ingestion == null || clock == null) {
            throw new IllegalArgumentException("store, ingestion and clock must not be null");
        }
        this.store = store;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    /**
     * 保存一份等待人工审核的入图计划。
     */
    public GraphReviewTask submit(GraphIngestionPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        if (plan.getStatus() == GraphIngestionPlan.Status.UNCHANGED) {
            throw new IllegalArgumentException("unchanged plan does not require review");
        }
        return store.create(GraphReviewTask.pending(plan, clock.getAsLong()));
    }

    /**
     * 按 taskId 查询审核任务。
     */
    public GraphReviewTask get(String taskId) {
        return store.get(taskId);
    }

    /**
     * 按条件分页查询审核任务。
     */
    public List<GraphReviewTask> list(GraphReviewTaskQuery query) {
        return store.list(query);
    }

    /**
     * 用人工修改后的新计划替换当前计划，并将任务置为待再次审核。
     */
    public GraphReviewTask updatePlan(String taskId, long expectedReviewVersion,
                                      GraphIngestionPlan plan, String reason, String actor) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        GraphReviewTask current = requireActionable(taskId);
        requireVersion(current, expectedReviewVersion);
        if (plan.getStatus() == GraphIngestionPlan.Status.UNCHANGED)
            throw new IllegalArgumentException("unchanged plan does not require review");
        if (!current.getPlan().getSpace().equals(plan.getSpace())
            || !current.getPlan().getDocumentId().equals(plan.getDocumentId())) {
            throw new IllegalArgumentException("updated plan must keep the task space and documentId");
        }
        GraphReviewTask next = current.transition(GraphReviewTaskStatus.CHANGES_REQUESTED, plan,
            reason, actor, clock.getAsLong());
        return store.update(next, expectedReviewVersion);
    }

    /**
     * 使用空操作人更新审核计划。
     */
    public GraphReviewTask updatePlan(String taskId, long expectedReviewVersion,
                                      GraphIngestionPlan plan, String reason) {
        return updatePlan(taskId, expectedReviewVersion, plan, reason, "");
    }

    /**
     * 应用人工修改并重新生成计划，不调用模型、不写图。
     *
     * <p>SDK 同步重建批准候选、实体映射、Mutation、文档状态、事实来源和实体注册记录。
     * 不允许修改已经开始执行的计划。</p>
     */
    public GraphReviewTask applyPatch(String taskId, long expectedReviewVersion,
                                      GraphReviewPatch patch, String reason, String actor) {
        if (patch == null) throw new IllegalArgumentException("patch must not be null");
        GraphReviewTask current = requireActionable(taskId);
        requireVersion(current, expectedReviewVersion);
        GraphIngestionPlan old = current.getPlan();
        GraphIngestionPlan reviewed = ingestion.rebuild(old,
            patch.apply(old.getExtractionResult(), old.getReviewSchema()));
        return updatePlan(taskId, expectedReviewVersion, reviewed, reason, actor);
    }

    /**
     * 使用空操作人应用计划修改。
     */
    public GraphReviewTask applyPatch(String taskId, long expectedReviewVersion, GraphReviewPatch patch,
                                      String reason) {
        return applyPatch(taskId, expectedReviewVersion, patch, reason, "");
    }

    /**
     * 接受并执行审核任务。
     *
     * <p>taskId 只用于读取审核任务；真正传给入图服务的是任务中冻结的 GraphIngestionPlan。</p>
     */
    public GraphReviewExecutionResult accept(String taskId, long expectedReviewVersion,
                                             GraphWriter writer, String actor) {
        if (writer == null) throw new IllegalArgumentException("writer must not be null");
        GraphReviewTask current = store.get(taskId);
        if (current == null) throw new IllegalArgumentException("review task was not found: " + taskId);
        requireVersion(current, expectedReviewVersion);
        // 客户端超时后可能重复点击。已完成任务不再写图，直接返回幂等成功结果。
        if (current.getStatus() == GraphReviewTaskStatus.COMPLETED) {
            return alreadyCompleted(current);
        }
        if (current.getStatus() != GraphReviewTaskStatus.PENDING_REVIEW
            && current.getStatus() != GraphReviewTaskStatus.CHANGES_REQUESTED) {
            throw new IllegalStateException("review task is not actionable: " + current.getStatus());
        }
        GraphReviewTask executing = store.update(
            current.transition(GraphReviewTaskStatus.EXECUTING, null, "", actor, clock.getAsLong()),
            expectedReviewVersion);
        return executeTask(executing, writer, actor);
    }

    /**
     * 恢复已经中断的任务，并同步审核状态。
     *
     * <p>用于进程退出后滞留的 EXECUTING 或 FAILED 任务。需要配置入图操作存储，保证能够识别
     * 已经完成的图写入阶段。不要对正在另一进程中执行的任务主动调用此方法。</p>
     */
    public GraphReviewExecutionResult resume(String taskId, long expectedReviewVersion,
                                             GraphWriter writer, String actor) {
        if (writer == null) throw new IllegalArgumentException("writer must not be null");
        if (!ingestion.isRecoverySupported())
            throw new IllegalStateException("operationStore is required for review recovery");
        GraphReviewTask current = store.get(taskId);
        if (current == null) throw new IllegalArgumentException("review task was not found: " + taskId);
        requireVersion(current, expectedReviewVersion);
        if (current.getStatus() == GraphReviewTaskStatus.COMPLETED) return alreadyCompleted(current);
        if (current.getStatus() != GraphReviewTaskStatus.EXECUTING && current.getStatus() != GraphReviewTaskStatus.FAILED)
            throw new IllegalStateException("review task is not recoverable: " + current.getStatus());
        GraphReviewTask executing = store.update(
            current.transition(GraphReviewTaskStatus.EXECUTING, null, "", actor, clock.getAsLong()), expectedReviewVersion);
        return executeTask(executing, writer, actor);
    }

    /**
     * 使用空操作人恢复中断任务。
     */
    public GraphReviewExecutionResult resume(String taskId, long expectedReviewVersion, GraphWriter writer) {
        return resume(taskId, expectedReviewVersion, writer, "");
    }

    /**
     * 执行冻结计划；执行异常和审核状态落库异常必须分开处理。
     */
    private GraphReviewExecutionResult executeTask(GraphReviewTask executing, GraphWriter writer, String actor) {
        GraphIngestionResult result;
        try {
            result = ingestion.execute(executing.getPlan(), writer);
        } catch (RuntimeException failure) {
            GraphReviewTask failed = executing.transition(GraphReviewTaskStatus.FAILED, null,
                failure.getMessage(), actor, clock.getAsLong());
            try {
                store.update(failed, executing.getReviewVersion());
            } catch (RuntimeException stateFailure) {
                failure.addSuppressed(stateFailure);
            }
            throw failure;
        }
        // 此处若存储失败，图可能已提交。保留 EXECUTING 状态供 resume，不能误记为写图失败。
        GraphReviewTaskStatus finalStatus = result.isSuccess() ? GraphReviewTaskStatus.COMPLETED : GraphReviewTaskStatus.FAILED;
        GraphReviewTask completed = store.update(executing.transition(finalStatus, null,
            result.getWriteResult().getMessage(), actor, clock.getAsLong()), executing.getReviewVersion());
        return new GraphReviewExecutionResult(completed, result);
    }

    /**
     * 使用空操作人接受并执行任务。
     */
    public GraphReviewExecutionResult accept(String taskId, long expectedReviewVersion, GraphWriter writer) {
        return accept(taskId, expectedReviewVersion, writer, "");
    }

    /**
     * 拒绝待审核任务，保留原因和计划，不进行图写入。
     */
    public GraphReviewTask reject(String taskId, long expectedReviewVersion, String reason, String actor) {
        GraphReviewTask current = requireActionable(taskId);
        requireVersion(current, expectedReviewVersion);
        return store.update(current.transition(GraphReviewTaskStatus.REJECTED, null, reason, actor,
            clock.getAsLong()), expectedReviewVersion);
    }

    /**
     * 使用空操作人拒绝任务。
     */
    public GraphReviewTask reject(String taskId, long expectedReviewVersion, String reason) {
        return reject(taskId, expectedReviewVersion, reason, "");
    }

    /**
     * 退回修改，不执行图写入。
     */
    public GraphReviewTask requestChanges(String taskId, long expectedReviewVersion, String reason, String actor) {
        GraphReviewTask current = requireActionable(taskId);
        requireVersion(current, expectedReviewVersion);
        return store.update(current.transition(GraphReviewTaskStatus.CHANGES_REQUESTED, null, reason, actor,
            clock.getAsLong()), expectedReviewVersion);
    }

    /**
     * 使用空操作人退回修改。
     */
    public GraphReviewTask requestChanges(String taskId, long expectedReviewVersion, String reason) {
        return requestChanges(taskId, expectedReviewVersion, reason, "");
    }

    /**
     * 归档已完成或已拒绝任务，保留当前快照。
     * 执行中和失败任务可能已经部分写图，必须先恢复，不能通过归档丢弃恢复线索。
     */
    public GraphReviewTask archive(String taskId, long expectedReviewVersion, String actor) {
        GraphReviewTask current = store.get(taskId);
        if (current == null) throw new IllegalArgumentException("review task was not found: " + taskId);
        requireVersion(current, expectedReviewVersion);
        if (current.getStatus() != GraphReviewTaskStatus.COMPLETED && current.getStatus() != GraphReviewTaskStatus.REJECTED)
            throw new IllegalStateException("only completed or rejected tasks can be archived");
        return store.update(current.transition(GraphReviewTaskStatus.ARCHIVED, null, current.getReason(), actor,
            clock.getAsLong()), expectedReviewVersion);
    }

    /**
     * 使用空操作人归档任务。
     */
    public GraphReviewTask archive(String taskId, long expectedReviewVersion) {
        return archive(taskId, expectedReviewVersion, "");
    }

    /**
     * 查询并限制可修改或拒绝的状态。
     */
    private GraphReviewTask requireActionable(String taskId) {
        GraphReviewTask task = store.get(taskId);
        if (task == null) throw new IllegalArgumentException("review task was not found: " + taskId);
        if (task.getStatus() != GraphReviewTaskStatus.PENDING_REVIEW && task.getStatus() != GraphReviewTaskStatus.CHANGES_REQUESTED)
            throw new IllegalStateException("review task is not actionable: " + task.getStatus());
        return task;
    }

    /**
     * 重复确认已完成任务不再写图，返回零影响成功结果。
     */
    private static GraphReviewExecutionResult alreadyCompleted(GraphReviewTask task) {
        return new GraphReviewExecutionResult(task, new GraphIngestionResult(task.getPlan(),
            com.agentsflex.graph.mutation.GraphWriteResult.success(0L, 0L), true));
    }

    /**
     * 防止界面上的旧版本决策覆盖另一位审核者的修改。
     */
    private static void requireVersion(GraphReviewTask task, long expectedReviewVersion) {
        if (task.getReviewVersion() != expectedReviewVersion)
            throw new IllegalStateException("review task version conflict: " + task.getTaskId());
    }
}
