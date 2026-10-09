package com.agentsflex.graph.extractor.ingestion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程安全的进程内导入操作存储，仅适用于测试和单实例临时任务。
 */
public final class InMemoryGraphIngestionOperationStore implements GraphIngestionOperationStore {
    /**
     * operationId 到最新阶段记录的映射。
     */
    private final Map<String, GraphIngestionOperation> operations = new LinkedHashMap<>();
    /**
     * operationId 到首次执行计划的映射，用于模拟进程恢复。
     */
    private final Map<String, GraphIngestionPlan> plans = new LinkedHashMap<>();

    /**
     * 查询当前阶段记录。
     */
    @Override
    public synchronized GraphIngestionOperation get(String operationId) {
        return operations.get(operationId);
    }

    /**
     * 支持当前进程内的计划恢复；进程退出后仍需由持久化实现保存记录。
     */
    @Override
    public boolean isRecoverySupported() {
        return true;
    }

    /**
     * 在同一个同步临界区中原子保存操作和原始计划。
     */
    @Override
    public synchronized boolean createIfAbsent(GraphIngestionOperation operation,
                                               GraphIngestionPlan plan) {
        if (operation == null || plan == null) {
            throw new IllegalArgumentException("operation and plan must not be null");
        }
        if (operations.containsKey(operation.getOperationId())) return false;
        operations.put(operation.getOperationId(), operation);
        plans.put(operation.getOperationId(), plan);
        return true;
    }

    /**
     * 原子推进阶段。
     */
    @Override
    public synchronized boolean compareAndSet(String operationId, GraphIngestionOperation.Stage expected,
                                              GraphIngestionOperation next) {
        if (expected == null || next == null) throw new IllegalArgumentException("expected and next must not be null");
        GraphIngestionOperation current = operations.get(operationId);
        if (current == null || current.getStage() != expected
            || !sameIdentity(current, next)) return false;
        operations.put(operationId, next);
        return true;
    }

    /**
     * 查询首次执行时冻结的计划对象。
     */
    @Override
    public synchronized GraphIngestionPlan getPlan(String operationId) {
        return plans.get(operationId);
    }

    /**
     * 返回按更新时间、操作号稳定排序的未完成操作。
     */
    @Override
    public synchronized List<GraphIngestionOperation> listRecoverableOperations(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<GraphIngestionOperation> result = new ArrayList<>();
        for (GraphIngestionOperation operation : operations.values()) {
            if (operation.getStage() != GraphIngestionOperation.Stage.COMPLETED) result.add(operation);
        }
        Collections.sort(result, new Comparator<GraphIngestionOperation>() {
            @Override
            public int compare(GraphIngestionOperation left, GraphIngestionOperation right) {
                int time = Long.compare(left.getUpdatedAtMillis(), right.getUpdatedAtMillis());
                return time != 0 ? time : left.getOperationId().compareTo(right.getOperationId());
            }
        });
        if (result.size() > limit) result = new ArrayList<>(result.subList(0, limit));
        return Collections.unmodifiableList(result);
    }

    /**
     * 防止 CAS 调用方在推进阶段时意外篡改操作绑定的计划身份。
     */
    private static boolean sameIdentity(GraphIngestionOperation current, GraphIngestionOperation next) {
        return current.getOperationId().equals(next.getOperationId())
            && current.getSpace().equals(next.getSpace())
            && current.getDocumentId().equals(next.getDocumentId())
            && current.getExpectedRevision() == next.getExpectedRevision()
            && current.getPlanFingerprint().equals(next.getPlanFingerprint());
    }
}
