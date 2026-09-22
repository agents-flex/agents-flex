package com.agentsflex.graph.mutation;


/**
 * 图变更结果，使用跨后端统一的节点和边影响数量。
 */
public final class GraphWriteResult {
    /**
     * 是否成功。
     */
    private final boolean success;
    /**
     * 受影响节点数。
     */
    private final long nodesAffected;
    /**
     * 受影响边数。
     */
    private final long edgesAffected;
    /**
     * 面向调用方的结果说明。
     */
    private final String message;
    /**
     * 失败时的底层异常。
     */
    private final Throwable error;

    /**
     * 创建写入结果。
     */
    private GraphWriteResult(boolean success, long nodesAffected, long edgesAffected,
                             String message, Throwable error) {
        this.success = success;
        this.nodesAffected = nodesAffected;
        this.edgesAffected = edgesAffected;
        this.message = message == null ? "" : message;
        this.error = error;
    }

    /**
     * 创建成功结果。
     */
    public static GraphWriteResult success(long nodesAffected, long edgesAffected) {
        return new GraphWriteResult(true, nodesAffected, edgesAffected, "", null);
    }

    /**
     * 创建失败结果。
     */
    public static GraphWriteResult failure(String message, Throwable error) {
        return new GraphWriteResult(false, 0, 0, message, error);
    }

    /**
     * @return 是否成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return 受影响节点数
     */
    public long getNodesAffected() {
        return nodesAffected;
    }

    /**
     * @return 受影响边数
     */
    public long getEdgesAffected() {
        return edgesAffected;
    }

    /**
     * @return 结果消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 底层异常
     */
    public Throwable getError() {
        return error;
    }
}
