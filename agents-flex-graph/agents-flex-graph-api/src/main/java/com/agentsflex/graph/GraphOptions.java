package com.agentsflex.graph;

import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.execution.GraphExecutionContext;

/**
 * 单次图操作的路由信息和安全限制。
 */
public final class GraphOptions {
    /**
     * 使用适配器默认图空间、30 秒超时和 1000 条抓取批次的默认选项。
     */
    public static final GraphOptions DEFAULT = builder().build();

    /**
     * 本次操作指定的逻辑图空间；为空时使用适配器默认值。
     */
    private final String space;
    /**
     * 查询超时时间，单位为毫秒。
     */
    private final long timeoutMillis;
    /**
     * 后端读取结果时建议使用的批量大小。
     */
    private final int fetchSize;
    /**
     * 客户端最多物化的记录数，用于保护查询工作台内存。
     */
    private final int maxRecords;
    /**
     * 可选的调用链和 Schema 版本上下文。
     */
    private final GraphExecutionContext context;
    /**
     * 是否强制当前操作只能执行只读查询。
     */
    private final boolean readOnly;

    private GraphOptions(Builder builder) {
        this.space = builder.space;
        this.timeoutMillis = builder.timeoutMillis;
        this.fetchSize = builder.fetchSize;
        this.maxRecords = builder.maxRecords;
        this.context = builder.context;
        this.readOnly = builder.readOnly;
    }

    /**
     * @return 显式配置的图空间，未配置时为 {@code null}
     */
    public String getSpace() {
        return space;
    }

    /**
     * @param fallback 为空配置时使用的后备空间 @return 实际空间名
     */
    public String getSpaceOrDefault(String fallback) {
        return space == null || space.trim().isEmpty() ? fallback : space;
    }

    /**
     * @return 超时时间（毫秒）
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * @return 结果抓取批量大小
     */
    public int getFetchSize() {
        return fetchSize;
    }

    /**
     * @return 客户端最大物化记录数
     */
    public int getMaxRecords() {
        return maxRecords;
    }

    /**
     * @return 本次操作的可选执行上下文。
     */
    public GraphExecutionContext getContext() {
        return context;
    }

    /**
     * @return 是否启用只读保护。
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * 创建只调整最大物化记录数的不可变选项副本。
     */
    public GraphOptions withMaxRecords(int maxRecords) {
        Builder copy = builder();
        if (space != null) copy.space(space);
        return copy.timeoutMillis(timeoutMillis).fetchSize(fetchSize).maxRecords(maxRecords)
            .context(context).readOnly(readOnly).build();
    }

    /**
     * @return 新的选项构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param space 本次操作使用的图空间 @return 仅指定空间的选项
     */
    public static GraphOptions ofSpace(String space) {
        return builder().space(space).build();
    }

    public static final class Builder {
        /**
         * 构造阶段暂存的图空间。
         */
        private String space;
        /**
         * 默认 30 秒，防止无限期占用连接。
         */
        private long timeoutMillis = 30_000L;
        /**
         * 默认每批读取 1000 条记录。
         */
        private int fetchSize = 1_000;
        /**
         * 默认最多物化 10000 条记录。
         */
        private int maxRecords = 10_000;
        private GraphExecutionContext context;
        private boolean readOnly;

        /**
         * 设置图空间。
         */
        public Builder space(String space) {
            this.space = GraphIdentifiers.requireValid(space, "space");
            return this;
        }

        /**
         * 设置查询超时，必须为正数。
         */
        public Builder timeoutMillis(long timeoutMillis) {
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeoutMillis must be positive");
            }
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * 设置读取批次大小，必须为正数。
         */
        public Builder fetchSize(int fetchSize) {
            if (fetchSize <= 0) {
                throw new IllegalArgumentException("fetchSize must be positive");
            }
            this.fetchSize = fetchSize;
            return this;
        }

        /**
         * 设置客户端最大物化记录数，必须为正数。
         */
        public Builder maxRecords(int maxRecords) {
            if (maxRecords <= 0) throw new IllegalArgumentException("maxRecords must be positive");
            this.maxRecords = maxRecords;
            return this;
        }

        /**
         * 设置连接、租户、请求和 Schema 版本等关联上下文。
         */
        public Builder context(GraphExecutionContext context) {
            this.context = context;
            return this;
        }

        /**
         * 强制原生查询只能声明为 READ。
         */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /**
         * @return 校验后的不可变选项
         */
        public GraphOptions build() {
            return new GraphOptions(this);
        }
    }
}
