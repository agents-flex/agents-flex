package com.agentsflex.graph.query;

/**
 * 分页执行结果。
 *
 * <p>结果保留普通 {@link GraphResult} 的记录、元数据和可选子图，同时单独提供下一页 opaque token，
 * 调用方不应自行解析或拼接 token。</p>
 */
public final class GraphPageResult {
    /**
     * 当前页结果。
     */
    private final GraphResult result;
    /**
     * 下一页 token；没有下一页时为空字符串。
     */
    private final String nextCursor;

    /**
     * 创建分页结果。
     *
     * @param result     当前页结果，不允许为 {@code null}
     * @param nextCursor 下一页 token，为 {@code null} 时按空 token 处理
     */
    public GraphPageResult(GraphResult result, String nextCursor) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        this.result = result;
        this.nextCursor = nextCursor == null ? "" : nextCursor;
    }

    /**
     * @return 当前页查询结果。
     */
    public GraphResult getResult() {
        return result;
    }

    /**
     * @return 下一页 token，没有下一页时为空字符串。
     */
    public String getNextCursor() {
        return nextCursor;
    }

    /**
     * @return 是否存在下一页。
     */
    public boolean hasNext() {
        return !nextCursor.isEmpty();
    }
}
