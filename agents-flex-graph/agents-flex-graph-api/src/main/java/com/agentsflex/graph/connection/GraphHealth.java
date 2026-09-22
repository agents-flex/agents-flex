package com.agentsflex.graph.connection;


/**
 * 图存储连接探活结果，供管理后台和监控系统使用。
 */
public final class GraphHealth {
    /**
     * 探活状态。
     */
    public enum Status {
        /**
         * 后端已连通并完成探测。
         */
        UP,
        /**
         * 探测失败或连接不可用。
         */
        DOWN,
        /**
         * 尚未执行探测或适配器没有实现探测。
         */
        UNKNOWN
    }

    /**
     * 探活状态。
     */
    private final Status status;
    /**
     * 后端名称或适配器标识。
     */
    private final String backend;
    /**
     * 面向运维人员的结果说明。
     */
    private final String message;
    /**
     * 探测耗时，单位为毫秒；未测量时为 -1。
     */
    private final long latencyMillis;
    /**
     * 探测完成时间，Unix epoch 毫秒。
     */
    private final long checkedAtMillis;

    private GraphHealth(Status status, String backend, String message, long latencyMillis, long checkedAtMillis) {
        this.status = status;
        this.backend = backend == null ? "" : backend;
        this.message = message == null ? "" : message;
        this.latencyMillis = latencyMillis;
        this.checkedAtMillis = checkedAtMillis;
    }

    /**
     * 创建成功探活结果。
     */
    public static GraphHealth up(String backend, long latencyMillis) {
        return new GraphHealth(Status.UP, backend, "Connection is healthy", latencyMillis, System.currentTimeMillis());
    }

    /**
     * 创建失败探活结果。
     */
    public static GraphHealth down(String backend, String message, long latencyMillis) {
        return new GraphHealth(Status.DOWN, backend, message, latencyMillis, System.currentTimeMillis());
    }

    /**
     * 创建未知状态结果。
     */
    public static GraphHealth unknown(String backend, String message) {
        return new GraphHealth(Status.UNKNOWN, backend, message, -1L, System.currentTimeMillis());
    }

    /**
     * @return 探活状态
     */
    public Status getStatus() {
        return status;
    }

    /**
     * @return 后端标识
     */
    public String getBackend() {
        return backend;
    }

    /**
     * @return 结果说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 探测耗时（毫秒）
     */
    public long getLatencyMillis() {
        return latencyMillis;
    }

    /**
     * @return 探测完成时间（Unix epoch 毫秒）
     */
    public long getCheckedAtMillis() {
        return checkedAtMillis;
    }

    /**
     * @return 是否为健康状态
     */
    public boolean isUp() {
        return status == Status.UP;
    }
}
