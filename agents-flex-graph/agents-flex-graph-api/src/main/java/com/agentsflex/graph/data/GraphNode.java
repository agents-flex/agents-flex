package com.agentsflex.graph.data;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可移植的属性图节点。
 *
 * <p>节点标识由应用负责维护，适配器不会自动替换该标识。节点可以包含一个或多个
 * 标签以及任意属性；构造完成后所有集合均为只读快照。</p>
 */
public final class GraphNode {
    /**
     * 应用侧稳定节点标识。
     */
    private final String id;
    /**
     * 节点标签集合，保持调用方添加时的顺序。
     */
    private final Set<String> labels;
    /**
     * 节点属性。
     */
    private final Map<String, Object> properties;

    /**
     * 根据 Builder 创建并冻结节点内容。
     */
    private GraphNode(Builder builder) {
        this.id = GraphIdentifiers.requireText(builder.id, "node id");
        if (builder.labels.isEmpty()) {
            throw new IllegalArgumentException("node must have at least one label");
        }
        this.labels = Collections.unmodifiableSet(new LinkedHashSet<>(builder.labels));
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.properties));
    }

    /**
     * @return 节点标识
     */
    public String getId() {
        return id;
    }

    /**
     * @return 只读标签集合
     */
    public Set<String> getLabels() {
        return labels;
    }

    /**
     * @return 按声明顺序排列的只读标签列表
     */
    public List<String> getLabelList() {
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }

    /**
     * @return 只读属性映射
     */
    public Map<String, Object> getProperties() {
        return properties;
    }

    /**
     * @param id 节点标识 @param label 初始标签 @return 节点构造器
     */
    public static Builder builder(String id, String label) {
        return new Builder(id).label(label);
    }

    public static final class Builder {
        /**
         * 待构建节点的标识。
         */
        private final String id;
        /**
         * 待构建节点的标签。
         */
        private final Set<String> labels = new LinkedHashSet<>();
        /**
         * 待构建节点的属性。
         */
        private final Map<String, Object> properties = new LinkedHashMap<>();

        /**
         * 创建节点构造器。
         */
        private Builder(String id) {
            this.id = id;
        }

        /**
         * 添加标签；重复标签会被集合自动去重。
         */
        public Builder label(String label) {
            labels.add(GraphIdentifiers.requireValid(label, "node label"));
            return this;
        }

        /**
         * 添加或覆盖单个属性。
         */
        public Builder property(String name, Object value) {
            properties.put(GraphIdentifiers.requireValid(name, "property name"), value);
            return this;
        }

        /**
         * 批量添加属性；传入 {@code null} 时不执行任何操作。
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
         * @return 校验并冻结后的节点
         */
        public GraphNode build() {
            return new GraphNode(this);
        }
    }
}
