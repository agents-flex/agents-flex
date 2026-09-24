package com.agentsflex.graph.extractor.incremental;

import java.util.Collections;
import java.util.List;

/**
 * 持久化导入操作状态机的扩展点。
 *
 * <p>生产实现应以 operationId 建立唯一索引，并原子实现操作与执行计划的首次创建及阶段 CAS。
 * 该存储配合 GraphMutation.operationId 支持进程崩溃后的安全恢复与对账。旧实现可以只实现三个
 * 基础方法，但只有同时覆盖计划存取和待恢复扫描方法，才能使用自动恢复 API。</p>
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
     * 原子创建操作及其原始执行计划。
     *
     * <p>生产实现应在同一存储事务中保存二者，避免只留下状态而无法重放。默认实现用于兼容只保存
     * 操作状态的旧实现，此时 {@link #getPlan(String)} 将无法支持跨进程恢复。</p>
     *
     * @return 首次创建返回 true；已存在时返回 false
     */
    default boolean createIfAbsent(GraphIngestionOperation operation, IncrementalGraphIngestionPlan plan) {
        return createIfAbsent(operation);
    }

    /**
     * 原子比较阶段并保存完整新记录。
     */
    boolean compareAndSet(String operationId, GraphIngestionOperation.Stage expected,
                          GraphIngestionOperation next);

    /**
     * 返回首次创建操作时保存的不可变计划，不存在或实现不支持时返回 null。
     */
    default IncrementalGraphIngestionPlan getPlan(String operationId) {
        return null;
    }

    /**
     * 按更新时间从旧到新返回未完成操作，供调用方启动恢复任务。
     *
     * @param limit 最大返回数量，必须大于 0
     * @return 不包含 COMPLETED 的只读操作列表
     */
    default List<GraphIngestionOperation> listRecoverable(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        return Collections.emptyList();
    }
}
