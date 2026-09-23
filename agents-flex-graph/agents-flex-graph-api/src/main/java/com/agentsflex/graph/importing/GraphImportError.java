package com.agentsflex.graph.importing;

import com.agentsflex.graph.error.GraphErrorCode;

/**
 * 单个导入批次的结构化错误详情。
 *
 * <p>该对象不会暴露底层数据库异常对象，只保留批次位置、稳定错误码和面向调用方的说明，
 * 便于任务快照序列化、日志记录以及上层管理工具展示。</p>
 */
public final class GraphImportError {
    /**
     * 发生错误的批次序号；未知时使用 {@code -1}。
     */
    private final int batchIndex;
    /**
     * 面向调用方的错误说明。
     */
    private final String message;
    /**
     * Graph SDK 稳定错误分类。
     */
    private final GraphErrorCode code;

    /**
     * 创建批次错误详情。
     *
     * @param batchIndex 批次序号，未知时可传 {@code -1}
     * @param message    错误说明，为 {@code null} 时按空字符串处理
     * @param code       稳定错误码，为 {@code null} 时使用 {@link GraphErrorCode#IMPORT_BATCH_FAILED}
     */
    public GraphImportError(int batchIndex, String message, GraphErrorCode code) {
        this.batchIndex = batchIndex;
        this.message = message == null ? "" : message;
        this.code = code == null ? GraphErrorCode.IMPORT_BATCH_FAILED : code;
    }

    /**
     * @return 发生错误的批次序号。
     */
    public int getBatchIndex() {
        return batchIndex;
    }

    /**
     * @return 面向调用方的错误说明。
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return Graph SDK 稳定错误分类。
     */
    public GraphErrorCode getCode() {
        return code;
    }
}
