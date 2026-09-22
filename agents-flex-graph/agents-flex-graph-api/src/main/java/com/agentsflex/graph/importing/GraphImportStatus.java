package com.agentsflex.graph.importing;


/**
 * 异步图导入任务的生命周期状态。
 */
public enum GraphImportStatus {
    /**
     * 已创建但尚未开始执行。
     */
    QUEUED,
    /**
     * 正在消费节点和边数据。
     */
    RUNNING,
    /**
     * 所有批次均已成功完成。
     */
    SUCCEEDED,
    /**
     * 任务执行完成但存在失败批次或未处理异常。
     */
    FAILED,
    /**
     * 任务被调用方取消。
     */
    CANCELLED
}
