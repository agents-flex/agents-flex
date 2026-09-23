package com.agentsflex.graph.importing;

import java.util.Collections;
import java.util.List;

/**
 * 导入任务快照存储扩展点。
 *
 * <p>SDK 默认只提供内存实现；开发者可以将快照持久化到自己的数据库、缓存或任务系统，
 * 但任务调度、租户和权限仍由上层应用负责。</p>
 */
public interface GraphImportTaskStore {
    /**
     * 保存或覆盖一个任务的最新只读快照。
     */
    void save(GraphImportTask task);

    /**
     * 按任务 ID 查询快照。
     *
     * @return 任务存在时返回快照，否则返回 {@code null}
     */
    default GraphImportTask get(String id) {
        return null;
    }

    /**
     * @return 当前存储中的任务快照，默认返回空集合。
     */
    default List<GraphImportTask> list() {
        return Collections.emptyList();
    }

    /**
     * @return 删除成功时返回 {@code true}，任务不存在时返回 {@code false}。
     */
    default boolean remove(String id) {
        return false;
    }
}
