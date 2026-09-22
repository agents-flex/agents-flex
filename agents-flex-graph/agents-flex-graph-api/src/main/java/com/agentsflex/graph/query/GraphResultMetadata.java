package com.agentsflex.graph.query;


/**
 * 查询执行和结果物化元数据，供查询工作台展示。
 */
public final class GraphResultMetadata {
    private final int recordCount;
    private final boolean truncated;
    private final long executionTimeMillis;

    /**
     * 创建结果元数据。
     */
    public GraphResultMetadata(int recordCount, boolean truncated, long executionTimeMillis) {
        if (recordCount < 0) throw new IllegalArgumentException("recordCount must not be negative");
        if (executionTimeMillis < 0) throw new IllegalArgumentException("executionTimeMillis must not be negative");
        this.recordCount = recordCount;
        this.truncated = truncated;
        this.executionTimeMillis = executionTimeMillis;
    }

    /**
     * @return 已物化记录数
     */
    public int getRecordCount() {
        return recordCount;
    }

    /**
     * @return 是否因 maxRecords 限制截断
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * @return 从发起执行到物化完成的耗时
     */
    public long getExecutionTimeMillis() {
        return executionTimeMillis;
    }
}
