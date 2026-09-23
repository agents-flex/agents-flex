package com.agentsflex.graph.importing;

import com.agentsflex.graph.mutation.GraphWriteResult;

/**
 * 导入过程回调，用于进度、批次结果、错误通知和外部监控集成。
 *
 * <p>回调由导入服务触发；实现应避免在回调中执行长时间阻塞操作。监听器异常会被 SDK
 * 吞掉，不会改变导入任务本身的成功或失败状态。</p>
 */
public interface GraphImportListener {
    /**
     * 任务状态或累计进度发生变化。
     */
    default void onProgress(GraphImportTask task) {
    }

    /**
     * 一个批次完成后回调；失败批次也会收到结果。
     */
    default void onBatchCompleted(GraphImportTask task, GraphWriteResult result) {
    }

    /**
     * 任务发生未恢复错误时回调。
     */
    default void onError(GraphImportTask task, Throwable error) {
    }
}
