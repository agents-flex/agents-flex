package com.agentsflex.graph.extractor.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内审核任务存储，适合测试和单实例演示。
 *
 * <p>进程退出后任务会丢失；生产环境应替换为带唯一约束和版本条件更新的持久化实现。</p>
 */
public final class InMemoryGraphReviewStore implements GraphReviewStore {
    /**
     * 当前任务快照；所有读写使用同一监视器保证版本 CAS 的原子性。
     */
    private final Map<String, GraphReviewTask> tasks = new LinkedHashMap<>();

    @Override
    public synchronized GraphReviewTask create(GraphReviewTask task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        if (tasks.containsKey(task.getTaskId())) {
            throw new IllegalStateException("review task already exists: " + task.getTaskId());
        }
        tasks.put(task.getTaskId(), task);
        return task;
    }

    @Override
    public synchronized GraphReviewTask findTask(String taskId) {
        return taskId == null ? null : tasks.get(taskId);
    }

    @Override
    public synchronized List<GraphReviewTask> findTasks(GraphReviewTaskQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        List<GraphReviewTask> matched = new ArrayList<>();
        for (GraphReviewTask task : tasks.values()) {
            if (!query.getSpace().isEmpty() && !query.getSpace().equals(task.getPlan().getSpace())) continue;
            if (!query.getDocumentId().isEmpty() && !query.getDocumentId().equals(task.getPlan().getDocumentId()))
                continue;
            if (!query.getStatuses().contains(task.getStatus())) continue;
            matched.add(task);
        }
        Collections.sort(matched, new Comparator<GraphReviewTask>() {
            @Override
            public int compare(GraphReviewTask left, GraphReviewTask right) {
                int time = Long.compare(right.getUpdatedAtMillis(), left.getUpdatedAtMillis());
                return time != 0 ? time : left.getTaskId().compareTo(right.getTaskId());
            }
        });
        int from = Math.min(query.getOffset(), matched.size());
        long requestedEnd = (long) from + query.getLimit();
        int to = (int) Math.min(requestedEnd, matched.size());
        return Collections.unmodifiableList(new ArrayList<>(matched.subList(from, to)));
    }

    @Override
    public synchronized GraphReviewTask update(GraphReviewTask task, long expectedReviewVersion) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        GraphReviewTask current = tasks.get(task.getTaskId());
        if (current == null) throw new IllegalStateException("review task was not found: " + task.getTaskId());
        if (current.getReviewVersion() != expectedReviewVersion) {
            throw new IllegalStateException("review task version conflict: " + task.getTaskId());
        }
        if (task.getReviewVersion() != expectedReviewVersion + 1L) {
            throw new IllegalArgumentException("task reviewVersion must increase by one");
        }
        tasks.put(task.getTaskId(), task);
        return task;
    }
}
