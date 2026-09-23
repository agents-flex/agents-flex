package com.agentsflex.graph.extractor.incremental;

/**
 * 一次增量导入跨越图写入、实体注册和文档状态提交的不可变恢复记录。
 *
 * <p>操作记录不是分布式事务，但可以准确识别故障发生在哪个阶段。调用方使用同一 operationId
 * 重试时，服务可以跳过已经成功的图写入，并继续完成后续幂等步骤。</p>
 */
public final class GraphIngestionOperation {
    /**
     * 导入操作阶段。
     */
    public enum Stage {
        /**
         * 已持久化执行意图，尚未确认图写入。
         */
        PREPARED,
        /**
         * GraphWriter 已返回成功。
         */
        GRAPH_APPLIED,
        /**
         * 文档当前状态和历史快照已经提交。
         */
        STATE_COMMITTED,
        /**
         * 所有步骤均已完成。
         */
        COMPLETED,
        /**
         * 最近一次执行失败，可以使用同一操作号重试。
         */
        FAILED
    }

    /**
     * 稳定幂等操作号。
     */
    private final String operationId;
    /**
     * 目标 Space。
     */
    private final String space;
    /**
     * 逻辑文档 ID。
     */
    private final String documentId;
    /**
     * 计划所基于的文档 revision。
     */
    private final long expectedRevision;
    /**
     * 完整执行计划的稳定指纹，用于拒绝相同操作号承载不同写入内容。
     */
    private final String planFingerprint;
    /**
     * 当前阶段。
     */
    private final Stage stage;
    /**
     * 最近更新时间。
     */
    private final long updatedAtMillis;
    /**
     * 失败原因；非失败阶段通常为空。
     */
    private final String failureMessage;

    /**
     * 创建不可变操作记录。
     */
    public GraphIngestionOperation(String operationId, String space, String documentId, long expectedRevision,
                                   String planFingerprint, Stage stage, long updatedAtMillis,
                                   String failureMessage) {
        this.operationId = text(operationId, "operationId");
        this.space = text(space, "space");
        this.documentId = text(documentId, "documentId");
        if (expectedRevision < 0L) throw new IllegalArgumentException("expectedRevision must not be negative");
        this.planFingerprint = text(planFingerprint, "planFingerprint");
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        if (updatedAtMillis < 0L) throw new IllegalArgumentException("updatedAtMillis must not be negative");
        this.expectedRevision = expectedRevision;
        this.stage = stage;
        this.updatedAtMillis = updatedAtMillis;
        this.failureMessage = failureMessage == null ? "" : failureMessage;
    }

    /**
     * @return 稳定幂等操作号。
     */
    public String getOperationId() {
        return operationId;
    }

    /**
     * @return 目标 Space。
     */
    public String getSpace() {
        return space;
    }

    /**
     * @return 逻辑文档 ID。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 计划所基于的 revision。
     */
    public long getExpectedRevision() {
        return expectedRevision;
    }

    /**
     * @return 完整执行计划的 SHA-256 指纹。
     */
    public String getPlanFingerprint() {
        return planFingerprint;
    }

    /**
     * @return 当前操作阶段。
     */
    public Stage getStage() {
        return stage;
    }

    /**
     * @return 最近更新时间。
     */
    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    /**
     * @return 最近失败原因。
     */
    public String getFailureMessage() {
        return failureMessage;
    }

    /**
     * 创建身份不变的新阶段记录。
     */
    public GraphIngestionOperation transition(Stage next, long time, String failure) {
        if (!canTransition(stage, next)) {
            throw new IllegalStateException("Illegal ingestion operation transition: " + stage + " -> " + next);
        }
        if (time < updatedAtMillis) {
            throw new IllegalArgumentException("operation update time must not move backwards");
        }
        return new GraphIngestionOperation(operationId, space, documentId, expectedRevision, planFingerprint,
            next, time, failure);
    }

    /**
     * 校验状态机迁移。失败只会发生在图写入确认之前，因此重试统一回到 PREPARED；
     * 已确认写图后则只能向状态提交和最终完成单向推进。
     */
    private static boolean canTransition(Stage current, Stage next) {
        if (current == null || next == null || current == next) return false;
        switch (current) {
            case PREPARED:
                return next == Stage.GRAPH_APPLIED || next == Stage.FAILED;
            case FAILED:
                return next == Stage.PREPARED;
            case GRAPH_APPLIED:
                return next == Stage.STATE_COMMITTED;
            case STATE_COMMITTED:
                return next == Stage.COMPLETED;
            case COMPLETED:
            default:
                return false;
        }
    }

    /**
     * 校验必填文本。
     */
    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
