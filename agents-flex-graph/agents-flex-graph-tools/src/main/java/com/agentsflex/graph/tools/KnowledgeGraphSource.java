package com.agentsflex.graph.tools;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.execution.GraphExecutionContext;
import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.schema.GraphSchema;

/**
 * 面向大模型暴露的逻辑知识源配置。
 *
 * <p>一个知识源把模型可见的逻辑名称绑定到固定的 {@link GraphQueryExecutor}、图空间和
 * {@link GraphSchema}。其中 Schema 既用于渐进式结构披露，也是查询和结果过滤的允许列表。</p>
 *
 * <p>该对象只保存查询入口，不拥有 {@link GraphStore} 或执行器的生命周期。连接池初始化、复用与关闭
 * 仍由应用容器负责。</p>
 */
public final class KnowledgeGraphSource {
    /**
     * 模型调用 Tool 时使用的逻辑知识源名称。
     */
    private final String name;
    /**
     * 嵌入 Tool 描述、帮助模型选择知识源的简短说明。
     */
    private final String description;
    /**
     * 每次查询都强制使用的物理图空间。
     */
    private final String space;
    /**
     * 实际执行 Portable Query 的入口。
     */
    private final GraphQueryExecutor queryExecutor;
    /**
     * 允许模型查看和查询的 Schema 子集。
     */
    private final GraphSchema schema;
    /**
     * 单次查询超时，单位为毫秒。
     */
    private final long timeoutMillis;
    /**
     * 传递给后端的批量抓取大小。
     */
    private final int fetchSize;
    /**
     * 可选的租户、追踪等执行上下文。
     */
    private final GraphExecutionContext context;
    /**
     * Tool 未显式指定 pageSize 时使用的页大小。
     */
    private final int defaultPageSize;
    /**
     * 单次 Tool 调用允许请求的最大页大小。
     */
    private final int maxPageSize;
    /**
     * 单个查询允许遍历的最大累计跳数。
     */
    private final int maxHops;

    /**
     * 校验并冻结 Builder 中的配置。
     */
    private KnowledgeGraphSource(Builder builder) {
        this.name = GraphIdentifiers.requireText(builder.name, "knowledge source name").trim();
        this.description = builder.description == null ? "" : builder.description.trim();
        this.space = GraphIdentifiers.requireValid(builder.space, "knowledge source space");
        if (builder.queryExecutor == null) {
            throw new IllegalArgumentException("query executor must not be null");
        }
        if (builder.schema == null) {
            throw new IllegalArgumentException("exposed graph schema must not be null");
        }
        if (builder.defaultPageSize <= 0 || builder.defaultPageSize > builder.maxPageSize) {
            throw new IllegalArgumentException("default page size must be between 1 and max page size");
        }
        if (builder.maxPageSize <= 0 || builder.maxPageSize >= 10_000) {
            throw new IllegalArgumentException("max page size must be between 1 and 9999");
        }
        if (builder.maxHops <= 0 || builder.maxHops > 16) {
            throw new IllegalArgumentException("max hops must be between 1 and 16");
        }
        this.queryExecutor = builder.queryExecutor;
        this.schema = builder.schema;
        this.timeoutMillis = builder.timeoutMillis;
        this.fetchSize = builder.fetchSize;
        this.context = builder.context;
        this.defaultPageSize = builder.defaultPageSize;
        this.maxPageSize = builder.maxPageSize;
        this.maxHops = builder.maxHops;
    }

    /**
     * 使用独立查询执行器创建知识源 Builder。
     *
     * @param name          模型可见的逻辑知识源名称
     * @param queryExecutor Portable Query 执行器
     * @param schema        允许模型访问的 Schema
     * @return 知识源 Builder
     */
    public static Builder builder(String name, GraphQueryExecutor queryExecutor, GraphSchema schema) {
        return new Builder(name, queryExecutor, schema);
    }

    /**
     * 使用 GraphStore 的查询入口创建知识源 Builder。
     *
     * @param name       模型可见的逻辑知识源名称
     * @param graphStore 应用管理的 GraphStore
     * @param schema     允许模型访问的 Schema
     * @return 知识源 Builder
     */
    public static Builder builder(String name, GraphStore graphStore, GraphSchema schema) {
        if (graphStore == null) {
            throw new IllegalArgumentException("graph store must not be null");
        }
        return new Builder(name, graphStore.query(), schema);
    }

    /**
     * @return 模型可见的逻辑知识源名称
     */
    public String getName() {
        return name;
    }

    /**
     * @return 帮助模型选择知识源的说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 查询固定使用的物理图空间
     */
    public String getSpace() {
        return space;
    }

    /**
     * @return 应用提供的查询执行器
     */
    public GraphQueryExecutor getQueryExecutor() {
        return queryExecutor;
    }

    /**
     * @return 模型允许访问的 Schema
     */
    public GraphSchema getSchema() {
        return schema;
    }

    /**
     * @return 默认页大小
     */
    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    /**
     * @return 最大页大小
     */
    public int getMaxPageSize() {
        return maxPageSize;
    }

    /**
     * @return 单个查询允许的最大累计跳数
     */
    public int getMaxHops() {
        return maxHops;
    }

    /**
     * 为一次分页查询生成强制只读的执行选项。
     *
     * <p>{@code maxRecords} 比页面大小多一条，用于默认分页实现判断是否存在下一页。Builder 已把
     * {@code maxPageSize} 限制在 9999 以内，因此这里不会超过 Graph API 的 10000 条硬上限。</p>
     */
    GraphOptions optionsForPage(int pageSize) {
        return GraphOptions.builder()
            .space(space)
            .timeoutMillis(timeoutMillis)
            .fetchSize(fetchSize)
            .maxRecords(pageSize + 1)
            .context(context)
            .readOnly(true)
            .build();
    }

    /**
     * KnowledgeGraphSource 的可变构造器。
     */
    public static final class Builder {
        private final String name;
        private final GraphQueryExecutor queryExecutor;
        private final GraphSchema schema;
        private String description = "";
        private String space;
        private long timeoutMillis = 10_000L;
        private int fetchSize = 100;
        private GraphExecutionContext context;
        private int defaultPageSize = 20;
        private int maxPageSize = 100;
        private int maxHops = 3;

        private Builder(String name, GraphQueryExecutor queryExecutor, GraphSchema schema) {
            this.name = name;
            this.queryExecutor = queryExecutor;
            this.schema = schema;
        }

        /**
         * @param description 知识源说明
         * @return 当前 Builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * @param space 固定图空间
         * @return 当前 Builder
         */
        public Builder space(String space) {
            this.space = space;
            return this;
        }

        /**
         * @param timeoutMillis 单次查询超时毫秒数
         * @return 当前 Builder
         */
        public Builder timeoutMillis(long timeoutMillis) {
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeoutMillis must be positive");
            }
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * @param fetchSize 后端批量抓取大小
         * @return 当前 Builder
         */
        public Builder fetchSize(int fetchSize) {
            if (fetchSize <= 0) {
                throw new IllegalArgumentException("fetchSize must be positive");
            }
            this.fetchSize = fetchSize;
            return this;
        }

        /**
         * @param context 可选执行上下文
         * @return 当前 Builder
         */
        public Builder context(GraphExecutionContext context) {
            this.context = context;
            return this;
        }

        /**
         * @param defaultPageSize 默认页大小
         * @return 当前 Builder
         */
        public Builder defaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
            return this;
        }

        /**
         * @param maxPageSize 最大页大小
         * @return 当前 Builder
         */
        public Builder maxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
            return this;
        }

        /**
         * @param maxHops 最大累计遍历跳数
         * @return 当前 Builder
         */
        public Builder maxHops(int maxHops) {
            this.maxHops = maxHops;
            return this;
        }

        /**
         * @return 校验并创建不可变知识源
         */
        public KnowledgeGraphSource build() {
            return new KnowledgeGraphSource(this);
        }
    }
}
