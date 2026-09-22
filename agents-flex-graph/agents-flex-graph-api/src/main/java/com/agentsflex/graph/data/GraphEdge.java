package com.agentsflex.graph.data;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有向属性图边，包含端点身份、类型、rank 和属性。
 */
public final class GraphEdge {
    /**
     * 边的不可变身份。
     */
    private final GraphEdgeKey key;
    /**
     * 边属性。
     */
    private final Map<String, Object> properties;

    /**
     * 根据 Builder 创建并冻结边。
     */
    private GraphEdge(Builder builder) {
        this.key = new GraphEdgeKey(builder.sourceId, builder.type, builder.targetId, builder.rank);
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.properties));
    }

    /**
     * @return 边身份
     */
    public GraphEdgeKey getKey() {
        return key;
    }

    /**
     * @return 起点节点标识
     */
    public String getSourceId() {
        return key.getSourceId();
    }

    /**
     * @return 边类型
     */
    public String getType() {
        return key.getType();
    }

    /**
     * @return 终点节点标识
     */
    public String getTargetId() {
        return key.getTargetId();
    }

    /**
     * @return rank
     */
    public long getRank() {
        return key.getRank();
    }

    /**
     * @return 只读属性映射
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * @param sourceId 起点 @param type 边类型 @param targetId 终点 @return 边构造器
     */
    public static Builder builder(String sourceId, String type, String targetId) {
        return new Builder(sourceId, type, targetId);
    }

    public static final class Builder {
        /**
         * 起点节点标识。
         */
        private final String sourceId;
        /**
         * 边类型。
         */
        private final String type;
        /**
         * 终点节点标识。
         */
        private final String targetId;
        /**
         * 平行边序号，默认 0。
         */
        private long rank;
        /**
         * 待写入属性。
         */
        private final Map<String, Object> properties = new LinkedHashMap<>();

        /**
         * 创建边构造器。
         */
        private Builder(String sourceId, String type, String targetId) {
            this.sourceId = sourceId;
            this.type = type;
            this.targetId = targetId;
        }

        /**
         * 设置平行边序号。
         */
        public Builder rank(long rank) {
            this.rank = rank;
            return this;
        }

        /**
         * 添加或覆盖一个边属性。
         */
        public Builder property(String name, Object value) {
            properties.put(GraphIdentifiers.requireValid(name, "property name"), value);
            return this;
        }

        /**
         * 批量添加边属性。
         */
        public Builder properties(Map<String, ?> values) {
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    property(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /**
         * @return 校验并冻结后的边
         */
        public GraphEdge build() {
            return new GraphEdge(this);
        }
    }
}
