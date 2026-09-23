package com.agentsflex.graph.importing;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;

import java.util.Collections;

/**
 * 面向流式处理的在线导入请求。
 *
 * <p>节点迭代器始终先于边迭代器消费，以满足多数图数据库“先有端点再建边”的约束。</p>
 */
public final class GraphImportRequest {
    /**
     * 节点数据迭代器。
     */
    private final Iterable<GraphNode> nodes;
    /**
     * 边数据迭代器。
     */
    private final Iterable<GraphEdge> edges;
    /**
     * 每批提交的最大记录数。
     */
    private final int batchSize;
    /**
     * 单批失败后是否立即停止。
     */
    private final boolean stopOnError;
    /**
     * 可选的进度和批次回调。
     */
    private final GraphImportListener listener;
    /**
     * 可选的批次 checkpoint 回调。
     */
    private final GraphImportCheckpoint checkpoint;
    /**
     * 可选的原始数据源资源。
     */
    private final GraphImportSource source;
    /**
     * 可选恢复点；默认从数据源起始位置开始。
     */
    private final GraphImportResumePoint resumePoint;

    /**
     * 根据构造器创建不可变导入请求。
     */
    private GraphImportRequest(Builder builder) {
        this.nodes = builder.nodes;
        this.edges = builder.edges;
        this.batchSize = builder.batchSize;
        this.stopOnError = builder.stopOnError;
        this.listener = builder.listener;
        this.checkpoint = builder.checkpoint;
        this.source = builder.source;
        this.resumePoint = builder.resumePoint;
    }

    /**
     * @return 节点迭代器
     */
    public Iterable<GraphNode> getNodes() {
        return nodes;
    }

    /**
     * @return 边迭代器
     */
    public Iterable<GraphEdge> getEdges() {
        return edges;
    }

    /**
     * @return 批大小
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * @return 是否遇错即停
     */
    public boolean isStopOnError() {
        return stopOnError;
    }

    /**
     * @return 导入过程监听器；未配置时为 {@code null}。
     */
    public GraphImportListener getListener() {
        return listener;
    }

    /**
     * @return checkpoint 回调；未配置时为 {@code null}。
     */
    public GraphImportCheckpoint getCheckpoint() {
        return checkpoint;
    }

    /**
     * @return 原始数据源；直接设置 nodes/edges 时为空。
     */
    public GraphImportSource getSource() {
        return source;
    }

    /**
     * @return 本次导入恢复点。
     */
    public GraphImportResumePoint getResumePoint() {
        return resumePoint;
    }

    /**
     * @return 新的导入请求构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        /**
         * 默认空节点数据。
         */
        private Iterable<GraphNode> nodes = Collections.emptyList();
        /**
         * 默认空边数据。
         */
        private Iterable<GraphEdge> edges = Collections.emptyList();
        /**
         * 默认每批 500 条。
         */
        private int batchSize = 500;
        /**
         * 默认失败即停，避免产生部分导入。
         */
        private boolean stopOnError = true;
        private GraphImportListener listener;
        private GraphImportCheckpoint checkpoint;
        private GraphImportSource source;
        private GraphImportResumePoint resumePoint = GraphImportResumePoint.beginning();

        /**
         * 设置节点数据源。
         */
        public Builder nodes(Iterable<GraphNode> nodes) {
            this.nodes = nodes == null ? Collections.<GraphNode>emptyList() : nodes;
            return this;
        }

        /**
         * 设置边数据源。
         */
        public Builder edges(Iterable<GraphEdge> edges) {
            this.edges = edges == null ? Collections.<GraphEdge>emptyList() : edges;
            return this;
        }

        /**
         * 使用统一数据源设置节点和边流。
         */
        public Builder source(GraphImportSource source) {
            this.source = source;
            if (source == null) {
                this.nodes = Collections.emptyList();
                this.edges = Collections.emptyList();
            } else {
                this.nodes = source.nodes() == null ? Collections.<GraphNode>emptyList() : source.nodes();
                this.edges = source.edges() == null ? Collections.<GraphEdge>emptyList() : source.edges();
            }
            return this;
        }

        /**
         * 设置批大小，必须为正数。
         */
        public Builder batchSize(int batchSize) {
            if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
            this.batchSize = batchSize;
            return this;
        }

        /**
         * 设置失败策略。
         */
        public Builder stopOnError(boolean stopOnError) {
            this.stopOnError = stopOnError;
            return this;
        }

        /**
         * 设置进度、批次和错误监听器。
         */
        public Builder listener(GraphImportListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * 设置批次 checkpoint 回调。
         */
        public Builder checkpoint(GraphImportCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
            return this;
        }

        /**
         * 从已确认的节点、边偏移继续导入。
         */
        public Builder resumeFrom(GraphImportResumePoint resumePoint) {
            this.resumePoint = resumePoint == null ? GraphImportResumePoint.beginning() : resumePoint;
            return this;
        }

        /**
         * @return 校验后的导入请求
         */
        public GraphImportRequest build() {
            return new GraphImportRequest(this);
        }
    }
}
