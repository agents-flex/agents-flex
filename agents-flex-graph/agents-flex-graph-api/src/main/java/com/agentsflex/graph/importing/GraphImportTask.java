package com.agentsflex.graph.importing;

/**
 * 异步导入任务的只读快照。
 *
 * <p>任务对象适合直接序列化给管理后台；需要刷新进度时，应通过任务 ID 再次调用
 * {@link GraphImportService#get(String)} 获取新的快照。</p>
 */
public final class GraphImportTask {
    /**
     * 任务唯一标识。
     */
    private final String id;
    /**
     * 当前生命周期状态。
     */
    private final GraphImportStatus status;
    /**
     * 当前累计报告。
     */
    private final GraphImportReport report;
    /**
     * 失败或取消原因。
     */
    private final String message;
    /**
     * 创建时间。
     */
    private final long submittedAtMillis;
    /**
     * 开始执行时间；未开始时为 -1。
     */
    private final long startedAtMillis;
    /**
     * 完成时间；未完成时为 -1。
     */
    private final long completedAtMillis;

    GraphImportTask(String id, GraphImportStatus status, GraphImportReport report, String message,
                    long submittedAtMillis, long startedAtMillis, long completedAtMillis) {
        this.id = id;
        this.status = status;
        this.report = report == null ? new GraphImportReport() : report.snapshot();
        this.message = message == null ? "" : message;
        this.submittedAtMillis = submittedAtMillis;
        this.startedAtMillis = startedAtMillis;
        this.completedAtMillis = completedAtMillis;
    }

    /**
     * @return 任务 ID
     */
    public String getId() {
        return id;
    }

    /**
     * @return 当前状态
     */
    public GraphImportStatus getStatus() {
        return status;
    }

    /**
     * @return 当前报告快照
     */
    public GraphImportReport getReport() {
        return report.snapshot();
    }

    /**
     * @return 失败或取消原因
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 提交时间
     */
    public long getSubmittedAtMillis() {
        return submittedAtMillis;
    }

    /**
     * @return 开始时间
     */
    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    /**
     * @return 完成时间
     */
    public long getCompletedAtMillis() {
        return completedAtMillis;
    }

    /**
     * @return 是否已经进入终态
     */
    public boolean isTerminal() {
        return status == GraphImportStatus.SUCCEEDED || status == GraphImportStatus.FAILED
            || status == GraphImportStatus.CANCELLED;
    }
}
