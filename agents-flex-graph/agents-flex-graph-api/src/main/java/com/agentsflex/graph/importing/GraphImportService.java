package com.agentsflex.graph.importing;

import com.agentsflex.graph.GraphOptions;

import java.util.Collections;
import java.util.List;

/**
 * 管理异步图导入任务的统一入口。
 */
public interface GraphImportService extends AutoCloseable {
    /**
     * 提交一个异步导入任务并立即返回任务快照。
     */
    GraphImportTask submit(GraphImportRequest request, GraphOptions options);

    /**
     * 查询任务最新快照。
     *
     * @param id 任务 ID
     * @return 最新快照；任务不存在时返回 {@code null}
     */
    GraphImportTask get(String id);

    /**
     * @return 当前服务保留的全部任务快照
     */
    default List<GraphImportTask> list() {
        return Collections.emptyList();
    }

    /**
     * 请求取消任务。
     *
     * @param id 任务 ID
     * @return 找到任务且接受取消请求时返回 {@code true}
     */
    boolean cancel(String id);

    /**
     * 从任务存储移除一个已经结束的任务。
     *
     * @return 成功移除时返回 {@code true}；运行中或不存在时返回 {@code false}
     */
    default boolean remove(String id) {
        return false;
    }

    /**
     * 删除完成时间早于给定时间的所有终态任务。
     */
    default int purgeCompletedBefore(long epochMillis) {
        return 0;
    }

    /**
     * 释放任务执行线程和内部资源。
     */
    @Override
    void close();
}
