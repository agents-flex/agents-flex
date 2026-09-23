package com.agentsflex.graph.query;

/**
 * 统一查询的分页请求。
 *
 * <p>SDK 默认使用稳定的 offset token 表示下一页；后端适配器可以在支持原生 keyset 游标时
 * 使用自己的 opaque token。token 不应由业务层解析或修改。</p>
 */
public final class GraphPageRequest {
    /**
     * 默认 offset 分页的起始位置；opaque cursor 请求中为 0。
     */
    private final int offset;
    /**
     * 本页最多返回的记录数。
     */
    private final int limit;
    /**
     * 上一页返回的不透明游标；第一页为空。
     */
    private final String cursor;

    /**
     * 创建并校验分页请求的内部构造器。
     */
    private GraphPageRequest(int offset, int limit, String cursor) {
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (limit <= 0 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        this.offset = offset;
        this.limit = limit;
        this.cursor = cursor == null ? "" : cursor.trim();
        // 适配器可以返回 opaque cursor；默认执行器只解释 offset 游标。
    }

    /**
     * 创建 offset 分页请求。
     *
     * @param offset 从零开始的跳过记录数
     * @param limit  本页最多返回的记录数，范围为 1 到 10000
     * @return 不可变分页请求
     */
    public static GraphPageRequest of(int offset, int limit) {
        return new GraphPageRequest(offset, limit, "");
    }

    /**
     * 根据上一页返回的 token 创建下一页请求。
     *
     * <p>SDK 默认实现能解析 {@code offset:数字} token；其他 token 会原样保留，交由覆写了分页
     * 执行逻辑的后端适配器解释。</p>
     *
     * @param cursor 上一页返回的 opaque token，不允许为空
     * @param limit  本页最多返回的记录数
     * @return 下一页请求
     */
    public static GraphPageRequest after(String cursor, int limit) {
        if (cursor == null || cursor.trim().isEmpty()) throw new IllegalArgumentException("cursor must not be blank");
        String value = cursor.trim();
        if (!value.startsWith("offset:")) return new GraphPageRequest(0, limit, value);
        try {
            return new GraphPageRequest(Integer.parseInt(value.substring("offset:".length())), limit, value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid graph page cursor", error);
        }
    }

    /**
     * @return offset 分页的跳过位置；opaque cursor 模式下为 0。
     */
    public int getOffset() {
        return offset;
    }

    /**
     * @return 本页最大记录数。
     */
    public int getLimit() {
        return limit;
    }

    /**
     * @return 上一页 token；第一页为空。
     */
    public String getCursor() {
        return cursor;
    }
}
