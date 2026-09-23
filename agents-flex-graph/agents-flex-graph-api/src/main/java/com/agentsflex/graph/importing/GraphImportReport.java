package com.agentsflex.graph.importing;

import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.GraphException;

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
     * 失败批次数。
     */
    private int batchesFailed;
    /**
     * 各失败批次的错误信息。
     */
    private final List<String> errors = new ArrayList<>();
    private final List<GraphImportError> errorDetails = new ArrayList<>();

    /**
     * 合并一批写入结果。
     */
    public synchronized void add(GraphWriteResult result) {
        add(-1, result);
    }

    /**
     * 合并一批结果并保留批次号和稳定错误分类。
     */
    public synchronized void add(int batchIndex, GraphWriteResult result) {
        if (result == null) {
            throw new IllegalArgumentException("write result must not be null");
        }
        if (result.isSuccess()) {
            nodesImported += result.getNodesAffected();
            edgesImported += result.getEdgesAffected();
            batchesCompleted++;
        } else {
            batchesFailed++;
            errors.add(result.getMessage());
            GraphErrorCode code = result.getErrorCode();
            if (code == null || code == GraphErrorCode.UNKNOWN) {
                code = result.getError() instanceof GraphException
                    ? ((GraphException) result.getError()).getCode() : GraphErrorCode.IMPORT_BATCH_FAILED;
            }
            errorDetails.add(new GraphImportError(batchIndex, result.getMessage(), code));
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
     * @return 已失败批次数。
     */
    public synchronized int getBatchesFailed() {
        return batchesFailed;
    }

    /**
     * @return 已尝试批次数，等于成功批次与失败批次之和。
     */
    public synchronized int getBatchesAttempted() {
        return batchesCompleted + batchesFailed;
    }

    /**
     * @return 只读错误信息列表
     */
    public synchronized List<String> getErrors() {
        return Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * @return 结构化批次错误详情。
     */
    public synchronized List<GraphImportError> getErrorDetails() {
        return Collections.unmodifiableList(new ArrayList<>(errorDetails));
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
        copy.batchesFailed = batchesFailed;
        copy.errors.addAll(errors);
        copy.errorDetails.addAll(errorDetails);
        return copy;
    }
}
