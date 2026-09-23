package com.agentsflex.graph.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.data.GraphNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一批有序图变更。
 *
 * <p>适配器应先执行删除，再执行节点和边的 upsert；这样可以避免同一批次中旧数据
 * 影响新数据写入。对象创建后不可变，适合在线导入和事务重放。</p>
 */
public final class GraphMutation {
    /**
     * 待 upsert 的节点。
     */
    private final List<GraphNode> nodes;
    /**
     * 待 upsert 的边。
     */
    private final List<GraphEdge> edges;
    /**
     * 待删除节点标识。
     */
    private final Set<String> deleteNodeIds;
    /**
     * 待删除边身份。
     */
    private final Set<GraphEdgeKey> deleteEdgeKeys;
    /**
     * 删除节点时是否同时删除关联边。
     */
    private final boolean detachDeletedNodes;

    /**
     * 将构造器中的可变集合复制为只读快照。
     */
    private GraphMutation(Builder builder) {
        validate(builder);
        this.nodes = immutable(builder.nodes);
        this.edges = immutable(builder.edges);
        this.deleteNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(builder.deleteNodeIds));
        this.deleteEdgeKeys = Collections.unmodifiableSet(new LinkedHashSet<>(builder.deleteEdgeKeys));
        this.detachDeletedNodes = builder.detachDeletedNodes;
    }

    /**
     * @return 只读节点 upsert 列表
     */
    public List<GraphNode> getNodes() {
        return nodes;
    }

    /**
     * @return 只读边 upsert 列表
     */
    public List<GraphEdge> getEdges() {
        return edges;
    }

    /**
     * @return 只读待删除节点集合
     */
    public Set<String> getDeleteNodeIds() {
        return deleteNodeIds;
    }

    /**
     * @return 只读待删除边集合
     */
    public Set<GraphEdgeKey> getDeleteEdgeKeys() {
        return deleteEdgeKeys;
    }

    /**
     * @return 是否级联删除关联边
     */
    public boolean isDetachDeletedNodes() {
        return detachDeletedNodes;
    }

    /**
     * @return 是否不包含任何变更
     */
    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty() && deleteNodeIds.isEmpty() && deleteEdgeKeys.isEmpty();
    }

    /**
     * @return 新的变更构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * 构造阶段拒绝空实体，避免后端在执行批次时才出现难以定位的空指针。
     */
    private static void validate(Builder builder) {
        for (GraphNode node : builder.nodes) {
            if (node == null) throw new IllegalArgumentException("upsert node must not be null");
        }
        for (GraphEdge edge : builder.edges) {
            if (edge == null) throw new IllegalArgumentException("upsert edge must not be null");
        }
        for (GraphEdgeKey key : builder.deleteEdgeKeys) {
            if (key == null) throw new IllegalArgumentException("delete edge key must not be null");
        }
    }

    public static final class Builder {
        /**
         * 待 upsert 节点。
         */
        private final List<GraphNode> nodes = new ArrayList<>();
        /**
         * 待 upsert 边。
         */
        private final List<GraphEdge> edges = new ArrayList<>();
        /**
         * 待删除节点。
         */
        private final Set<String> deleteNodeIds = new LinkedHashSet<>();
        /**
         * 待删除边。
         */
        private final Set<GraphEdgeKey> deleteEdgeKeys = new LinkedHashSet<>();
        /**
         * 默认级联删除关联边。
         */
        private boolean detachDeletedNodes = true;

        /**
         * 添加一个节点 upsert。
         */
        public Builder upsertNode(GraphNode node) {
            nodes.add(node);
            return this;
        }

        /**
         * 批量添加节点 upsert。
         */
        public Builder upsertNodes(Collection<GraphNode> values) {
            if (values != null) nodes.addAll(values);
            return this;
        }

        /**
         * 添加一个边 upsert。
         */
        public Builder upsertEdge(GraphEdge edge) {
            edges.add(edge);
            return this;
        }

        /**
         * 批量添加边 upsert。
         */
        public Builder upsertEdges(Collection<GraphEdge> values) {
            if (values != null) edges.addAll(values);
            return this;
        }

        /**
         * 添加节点删除操作。
         */
        public Builder deleteNode(String id) {
            deleteNodeIds.add(GraphIdentifiers.requireText(id, "node id"));
            return this;
        }

        /**
         * 添加边删除操作。
         */
        public Builder deleteEdge(GraphEdgeKey key) {
            if (key == null) throw new IllegalArgumentException("delete edge key must not be null");
            deleteEdgeKeys.add(key);
            return this;
        }

        /**
         * 设置删除节点时是否解除关联边。
         */
        public Builder detachDeletedNodes(boolean detach) {
            this.detachDeletedNodes = detach;
            return this;
        }

        /**
         * @return 校验并冻结后的变更批次
         */
        public GraphMutation build() {
            return new GraphMutation(this);
        }
    }
}
