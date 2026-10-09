package com.agentsflex.graph.extractor.ingestion;

import java.util.List;

/**
 * 持久化导入操作状态机的扩展点。
 *
 * <p>生产实现应以 operationId 建立唯一索引，并原子实现操作与执行计划的首次创建及阶段 CAS。
 * 该存储配合 GraphMutation.operationId 支持阶段恢复与对账。所有实现必须原子保存操作与计划，
 * 并显式声明是否提供计划读取和待恢复操作查询能力，不提供旧接口降级适配。</p>
 */
public interface GraphIngestionOperationStore {
    /**
     * 查询操作，不存在时返回 null。
     */
    GraphIngestionOperation get(String operationId);

    /**
     * 是否支持读取冻结计划并扫描待恢复操作。
     *
     * <p>该能力只表示此实现提供恢复所需 API；跨进程恢复还要求使用可靠的持久化存储。
     * 不支持时，调用方必须直接重试原计划，不能使用 Service 的 resume 或恢复扫描入口。</p>
     */
    boolean isRecoverySupported();

    /**
     * 原子创建操作及其原始执行计划。
     *
     * <p>生产实现应在同一存储事务中保存二者，避免只留下状态而无法重放。重复操作号必须保留首次记录与计划，不能覆盖。</p>
     *
     * @return 首次创建返回 true；已存在时返回 false
     */
    boolean createIfAbsent(GraphIngestionOperation operation, GraphIngestionPlan plan);

    /**
     * 原子比较阶段并保存完整新记录。
     */
    boolean compareAndSet(String operationId, GraphIngestionOperation.Stage expected,
                          GraphIngestionOperation next);

    /**
     * 返回首次创建操作时保存的不可变计划，不存在时返回 null。
     * 不支持恢复的实现应抛出 UnsupportedOperationException。
     */
    GraphIngestionPlan getPlan(String operationId);

    /**
     * 按更新时间从旧到新返回未完成操作，供调用方启动恢复任务。
     *
     * @param limit 最大返回数量，必须大于 0
     * @return 不包含 COMPLETED 的只读操作列表
     */
    List<GraphIngestionOperation> listRecoverableOperations(int limit);
}
