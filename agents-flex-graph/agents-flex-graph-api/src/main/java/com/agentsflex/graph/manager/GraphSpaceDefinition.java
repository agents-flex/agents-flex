package com.agentsflex.graph.manager;

import com.agentsflex.graph.identifier.GraphIdentifiers;

/**
 * 可移植的逻辑图空间定义。
 */
public final class GraphSpaceDefinition {
    /**
     * 图空间名称。
     */
    private final String name;
    /**
     * 分区数量；Nebula 等分布式后端会使用该值。
     */
    private final int partitionCount;
    /**
     * 副本数量；不支持该概念的后端可以忽略并给出警告。
     */
    private final int replicaFactor;

    /**
     * 根据构造器创建并校验图空间定义。
     */
    private GraphSpaceDefinition(Builder builder) {
        this.name = GraphIdentifiers.requireValid(builder.name, "space name");
        this.partitionCount = builder.partitionCount;
        this.replicaFactor = builder.replicaFactor;
    }

    /**
     * @return 图空间名称
     */
    public String getName() {
        return name;
    }

    /**
     * @return 分区数
     */
    public int getPartitionCount() {
        return partitionCount;
    }

    /**
     * @return 副本数
     */
    public int getReplicaFactor() {
        return replicaFactor;
    }

    /**
     * @param name 图空间名称 @return 构造器
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        /**
         * 图空间名称。
         */
        private final String name;
        /**
         * 默认分区数。
         */
        private int partitionCount = 10;
        /**
         * 默认副本数。
         */
        private int replicaFactor = 1;

        /**
         * 创建空间构造器。
         */
        private Builder(String name) {
            this.name = name;
        }

        /**
         * 设置分区数。
         */
        public Builder partitionCount(int partitionCount) {
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("partitionCount must be positive");
            }
            this.partitionCount = partitionCount;
            return this;
        }

        /**
         * 设置副本数。
         */
        public Builder replicaFactor(int replicaFactor) {
            if (replicaFactor <= 0) {
                throw new IllegalArgumentException("replicaFactor must be positive");
            }
            this.replicaFactor = replicaFactor;
            return this;
        }

        /**
         * @return 校验后的空间定义
         */
        public GraphSpaceDefinition build() {
            return new GraphSpaceDefinition(this);
        }
    }
}
