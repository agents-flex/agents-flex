package com.agentsflex.graph.importing;

import com.agentsflex.graph.mutation.GraphWriteResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可移植在线导入的累计结果。
 */
public final class GraphImportReport {
    /**
     * 成功写入的节点数。
     */
    private long nodesImported;
    /**
     * 成功写入的边数。
     */
    private long edgesImported;
    /**
     * 成功完成的批次数。
     */
    private int batchesCompleted;
    /**
     * 各失败批次的错误信息。
     */
    private final List<String> errors = new ArrayList<>();

    /**
     * 合并一批写入结果。
     */
    public synchronized void add(GraphWriteResult result) {
        if (result == null) {
            throw new IllegalArgumentException("write result must not be null");
        }
        if (result.isSuccess()) {
            nodesImported += result.getNodesAffected();
            edgesImported += result.getEdgesAffected();
            batchesCompleted++;
        } else {
            errors.add(result.getMessage());
        }
    }

    /**
     * @return 成功导入的节点数
     */
    public synchronized long getNodesImported() {
        return nodesImported;
    }

    /**
     * @return 成功导入的边数
     */
    public synchronized long getEdgesImported() {
        return edgesImported;
    }

    /**
     * @return 成功完成的批次数
     */
    public synchronized int getBatchesCompleted() {
        return batchesCompleted;
    }

    /**
     * @return 只读错误信息列表
     */
    public synchronized List<String> getErrors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * @return 是否没有任何失败批次
     */
    public synchronized boolean isSuccess() {
        return errors.isEmpty();
    }

    /**
     * 创建当前报告的不可变数值快照，供异步任务轮询使用。
     */
    public synchronized GraphImportReport snapshot() {
        GraphImportReport copy = new GraphImportReport();
        copy.nodesImported = nodesImported;
        copy.edgesImported = edgesImported;
        copy.batchesCompleted = batchesCompleted;
        copy.errors.addAll(errors);
        return copy;
    }
}
