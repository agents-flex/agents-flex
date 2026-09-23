package com.agentsflex.graph.extractor.incremental;

/**
 * 持久化导入操作状态机的扩展点。
 *
 * <p>生产实现应以 operationId 建立唯一索引，并原子实现 createIfAbsent 和 compareAndSet。
 * 该存储配合 GraphMutation.operationId 支持进程崩溃后的安全恢复与对账。</p>
 */
public interface GraphIngestionOperationStore {
    /**
     * 查询操作，不存在时返回 null。
     */
    GraphIngestionOperation get(String operationId);

    /**
     * 首次创建返回 true；已存在时返回 false。
     */
    boolean createIfAbsent(GraphIngestionOperation operation);

    /**
     * 原子比较阶段并保存完整新记录。
     */
    boolean compareAndSet(String operationId, GraphIngestionOperation.Stage expected,
                          GraphIngestionOperation next);
}
