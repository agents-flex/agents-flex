package com.agentsflex.graph.importing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graph SDK 默认的进程内任务快照存储。
 *
 * <p>该实现使用并发 Map 保存最新快照，适合单实例或测试场景；进程退出后数据会丢失，
 * 不提供跨进程同步、租户隔离和任务调度能力。</p>
 */
public final class InMemoryGraphImportTaskStore implements GraphImportTaskStore {
    /**
     * 任务 ID 到最新快照的并发映射。
     */
    private final Map<String, GraphImportTask> tasks = new ConcurrentHashMap<>();

    /**
     * 保存非空任务快照。
     */
    @Override
    public void save(GraphImportTask task) {
        if (task != null) tasks.put(task.getId(), task);
    }

    /**
     * 按 ID 查询任务快照。
     */
    @Override
    public GraphImportTask get(String id) {
        return id == null ? null : tasks.get(id);
    }

    /**
     * 按提交时间倒序返回不可变任务列表。
     */
    @Override
    public java.util.List<GraphImportTask> list() {
        ArrayList<GraphImportTask> result = new ArrayList<>(tasks.values());
        Collections.sort(result, new Comparator<GraphImportTask>() {
            @Override
            public int compare(GraphImportTask left, GraphImportTask right) {
                return Long.compare(right.getSubmittedAtMillis(), left.getSubmittedAtMillis());
            }
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * 删除指定任务快照。
     */
    @Override
    public boolean remove(String id) {
        return id != null && tasks.remove(id) != null;
    }
}
