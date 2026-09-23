package com.agentsflex.graph.query;


/**
 * 查询执行和结果物化元数据，供查询工作台展示。
 */
public final class GraphResultMetadata {
    /**
     * 已物化到客户端的记录数。
     */
    private final int recordCount;
    /**
     * 是否因为结果保护上限或分页探测而存在未返回记录。
     */
    private final boolean truncated;
    /**
     * 从开始执行到当前结果物化完成的耗时。
     */
    private final long executionTimeMillis;
    /**
     * 下一页 opaque 游标，没有下一页时为空。
     */
    private final String nextCursor;

    /**
     * 创建结果元数据。
     */
    public GraphResultMetadata(int recordCount, boolean truncated, long executionTimeMillis) {
        this(recordCount, truncated, executionTimeMillis, "");
    }

    /**
     * 创建带下一页游标的结果元数据。
     *
     * @param recordCount         已物化记录数
     * @param truncated           是否还有未返回记录
     * @param executionTimeMillis 执行耗时
     * @param nextCursor          下一页游标，可以为 {@code null}
     */
    public GraphResultMetadata(int recordCount, boolean truncated, long executionTimeMillis, String nextCursor) {
        if (recordCount < 0) throw new IllegalArgumentException("recordCount must not be negative");
        if (executionTimeMillis < 0) throw new IllegalArgumentException("executionTimeMillis must not be negative");
        this.recordCount = recordCount;
        this.truncated = truncated;
        this.executionTimeMillis = executionTimeMillis;
        this.nextCursor = nextCursor == null ? "" : nextCursor;
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

    /**
     * @return 下一页游标；不支持或没有下一页时为空。
     */
    public String getNextCursor() {
        return nextCursor;
    }

    /**
     * 创建仅替换下一页 token 的元数据快照。
     */
    public GraphResultMetadata withNextCursor(String cursor) {
        return new GraphResultMetadata(recordCount, truncated, executionTimeMillis, cursor);
    }

    /**
     * 创建分页后的元数据快照，并更新当前页记录数、截断状态和游标。
     */
    public GraphResultMetadata forPage(int pageRecordCount, boolean pageTruncated, String cursor) {
        return new GraphResultMetadata(pageRecordCount, pageTruncated, executionTimeMillis, cursor);
    }
}
