package com.agentsflex.graph.extractor.review;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 审核任务查询条件。
 *
 * <p>查询条件只描述通用过滤维度，具体数据库实现可以在此基础上增加索引和分页优化。</p>
 */
public final class GraphReviewQuery {
    /**
     * Space 过滤条件。
     */
    private final String space;
    /**
     * 逻辑文档 ID 过滤条件。
     */
    private final String documentId;
    /**
     * 审核状态过滤集合。
     */
    private final Set<GraphReviewStatus> statuses;
    /**
     * 分页起始偏移。
     */
    private final int offset;
    /**
     * 单页最大记录数。
     */
    private final int limit;

    private GraphReviewQuery(Builder builder) {
        this.space = optional(builder.space);
        this.documentId = optional(builder.documentId);
        this.statuses = Collections.unmodifiableSet(
            builder.statuses.isEmpty()
                ? EnumSet.allOf(GraphReviewStatus.class)
                : EnumSet.copyOf(builder.statuses));
        if (builder.offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (builder.limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.offset = builder.offset;
        this.limit = builder.limit;
    }

    /**
     * 创建查询条件构造器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return Space 过滤条件，空字符串表示不限制。
     */
    public String getSpace() {
        return space;
    }

    /**
     * @return 文档过滤条件，空字符串表示不限制。
     */
    public String getDocumentId() {
        return documentId;
    }

    /**
     * @return 状态过滤集合。
     */
    public Set<GraphReviewStatus> getStatuses() {
        return statuses;
    }

    /**
     * @return 跳过的记录数。
     */
    public int getOffset() {
        return offset;
    }

    /**
     * @return 最多返回的记录数。
     */
    public int getLimit() {
        return limit;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 审核任务查询条件构造器。
     */
    public static final class Builder {
        /**
         * 待过滤的 Space。
         */
        private String space;
        /**
         * 待过滤的逻辑文档 ID。
         */
        private String documentId;
        /**
         * 待过滤的审核状态。
         */
        private final Set<GraphReviewStatus> statuses = EnumSet.noneOf(GraphReviewStatus.class);
        /**
         * 分页偏移。
         */
        private int offset;
        /**
         * 分页大小。
         */
        private int limit = 50;

        /**
         * 按 Space 过滤。
         */
        public Builder space(String value) {
            this.space = value;
            return this;
        }

        /**
         * 按逻辑文档 ID 过滤。
         */
        public Builder documentId(String value) {
            this.documentId = value;
            return this;
        }

        /**
         * 只查询指定状态。
         */
        public Builder status(GraphReviewStatus value) {
            if (value != null) statuses.add(value);
            return this;
        }

        /**
         * 查询多个状态。
         */
        public Builder statuses(Iterable<GraphReviewStatus> values) {
            if (values != null) for (GraphReviewStatus value : values) if (value != null) statuses.add(value);
            return this;
        }

        /**
         * 设置偏移量。
         */
        public Builder offset(int value) {
            this.offset = value;
            return this;
        }

        /**
         * 设置分页大小。
         */
        public Builder limit(int value) {
            this.limit = value;
            return this;
        }

        /**
         * 构造不可变查询条件。
         */
        public GraphReviewQuery build() {
            return new GraphReviewQuery(this);
        }
    }
}
