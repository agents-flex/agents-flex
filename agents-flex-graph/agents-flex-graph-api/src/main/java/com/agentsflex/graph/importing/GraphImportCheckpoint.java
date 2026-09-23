package com.agentsflex.graph.importing;

/**
 * 导入批次提交后的 checkpoint 回调。
 *
 * <p>SDK 不负责重新打开外部数据源；开发者可以利用该回调持久化偏移量，并在自己的数据源层
 * 实现恢复和重试。</p>
 */
public interface GraphImportCheckpoint {
    /**
     * 一个批次成功提交或被记录后通知偏移量。
     */
    void onBatch(String taskId, long nodesProcessed, long edgesProcessed);
}
