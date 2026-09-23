package com.agentsflex.graph.extractor.incremental;

import java.util.LinkedHashMap;
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
     * 查询当前阶段记录。
     */
    @Override
    public synchronized GraphIngestionOperation get(String operationId) {
        return operations.get(operationId);
    }

    /**
     * 原子创建操作。
     */
    @Override
    public synchronized boolean createIfAbsent(GraphIngestionOperation operation) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        if (operations.containsKey(operation.getOperationId())) return false;
        operations.put(operation.getOperationId(), operation);
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
            || !operationId.equals(next.getOperationId())) return false;
        operations.put(operationId, next);
        return true;
    }
}
