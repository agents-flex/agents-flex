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
     * 根据构造器创建不可变导入请求。
     */
    private GraphImportRequest(Builder builder) {
        this.nodes = builder.nodes;
        this.edges = builder.edges;
        this.batchSize = builder.batchSize;
        this.stopOnError = builder.stopOnError;
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
         * @return 校验后的导入请求
         */
        public GraphImportRequest build() {
            return new GraphImportRequest(this);
        }
    }
}
