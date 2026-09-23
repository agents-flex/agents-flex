package com.agentsflex.graph.schema;

import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.manager.GraphManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 可移植的属性图 Schema 定义。
 *
 * <p>Schema 只描述意图，不直接执行 DDL。调用方应通过 {@link GraphManager#applySchema}
 * 交给具体后端处理；后端无法表达的约束必须返回校验错误或明确抛出异常。</p>
 */
public final class GraphSchema {
    /**
     * 图属性支持的基础类型。
     */
    public enum PropertyType {
        /**
         * UTF-8 文本。
         */
        STRING,
        /**
         * 布尔值。
         */
        BOOLEAN,
        /**
         * 64 位整数。
         */
        INT64,
        /**
         * 双精度浮点数。
         */
        DOUBLE,
        /**
         * 日历日期。
         */
        DATE,
        /**
         * 日期和时间。
         */
        DATETIME
    }

    /**
     * 索引作用于节点标签还是边类型。
     */
    public enum IndexTarget {
        /**
         * 节点标签。
         */
        NODE,
        /**
         * 边类型。
         */
        EDGE
    }

    /**
     * Schema 中声明的节点类型定义。
     */
    private final List<NodeType> nodeTypes;
    /**
     * Schema 中声明的边类型定义。
     */
    private final List<EdgeType> edgeTypes;
    /**
     * Schema 中声明的索引定义。
     */
    private final List<Index> indexes;
    /**
     * 面向 Schema 版本和开发工具的元数据。
     */
    private final GraphSchemaMetadata metadata;

    /**
     * 根据构造器内容创建不可变 Schema。
     */
    private GraphSchema(Builder builder) {
        validateUniqueDefinitions(builder);
        this.nodeTypes = immutable(builder.nodeTypes);
        this.edgeTypes = immutable(builder.edgeTypes);
        this.indexes = immutable(builder.indexes);
        this.metadata = builder.metadata == null ? GraphSchemaMetadata.empty() : builder.metadata;
    }

    /**
     * @return 不可变的节点类型列表
     */
    public List<NodeType> getNodeTypes() {
        return nodeTypes;
    }

    /**
     * @return 不可变的边类型列表
     */
    public List<EdgeType> getEdgeTypes() {
        return edgeTypes;
    }

    /**
     * @return 不可变的索引列表
     */
    public List<Index> getIndexes() {
        return indexes;
    }

    /**
     * @return Schema 版本和展示元数据。
     */
    public GraphSchemaMetadata getMetadata() {
        return metadata;
    }

    /**
     * @return 新的 Schema 构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    /**
     * 校验 Schema 集合级唯一性，避免后续比较或 DDL 编译时发生静默覆盖。
     */
    private static void validateUniqueDefinitions(Builder builder) {
        Set<String> nodeLabels = new HashSet<>();
        for (NodeType node : builder.nodeTypes) {
            if (node == null) throw new IllegalArgumentException("node type must not be null");
            if (!nodeLabels.add(node.getLabel())) {
                throw new IllegalArgumentException("duplicate node label: " + node.getLabel());
            }
            validateUniqueProperties("node " + node.getLabel(), node.getProperties());
        }
        Set<String> edgeTypes = new HashSet<>();
        for (EdgeType edge : builder.edgeTypes) {
            if (edge == null) throw new IllegalArgumentException("edge type must not be null");
            if (!edgeTypes.add(edge.getType())) {
                throw new IllegalArgumentException("duplicate edge type: " + edge.getType());
            }
            validateUniqueProperties("edge " + edge.getType(), edge.getProperties());
        }
        Set<String> indexNames = new HashSet<>();
        for (Index index : builder.indexes) {
            if (index == null) throw new IllegalArgumentException("index must not be null");
            if (!indexNames.add(index.getName())) {
                throw new IllegalArgumentException("duplicate index name: " + index.getName());
            }
            Set<String> indexProperties = new HashSet<>();
            for (String property : index.getProperties()) {
                if (!indexProperties.add(property)) {
                    throw new IllegalArgumentException("duplicate index property: " + property);
                }
            }
        }
    }

    /**
     * 校验单个节点或边类型中的属性名唯一性。
     */
    private static void validateUniqueProperties(String owner, List<Property> properties) {
        Set<String> names = new HashSet<>();
        for (Property property : properties) {
            if (property == null) throw new IllegalArgumentException(owner + " property must not be null");
            if (!names.add(property.getName())) {
                throw new IllegalArgumentException("duplicate property " + property.getName() + " in " + owner);
            }
        }
    }

    public static final class Builder {
        /**
         * 待添加的节点类型。
         */
        private final List<NodeType> nodeTypes = new ArrayList<>();
        /**
         * 待添加的边类型。
         */
        private final List<EdgeType> edgeTypes = new ArrayList<>();
        /**
         * 待添加的索引。
         */
        private final List<Index> indexes = new ArrayList<>();
        private GraphSchemaMetadata metadata = GraphSchemaMetadata.empty();

        /**
         * 添加一个节点类型。
         */
        public Builder nodeType(NodeType nodeType) {
            nodeTypes.add(nodeType);
            return this;
        }

        /**
         * 添加一个边类型。
         */
        public Builder edgeType(EdgeType edgeType) {
            edgeTypes.add(edgeType);
            return this;
        }

        /**
         * 添加一个索引。
         */
        public Builder index(Index index) {
            indexes.add(index);
            return this;
        }

        /**
         * 设置 Schema 版本、显示名称和扩展元数据。
         */
        public Builder metadata(GraphSchemaMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * @return 不可变 Schema 定义
         */
        public GraphSchema build() {
            return new GraphSchema(this);
        }
    }

    public static final class Property {
        /**
         * 属性名。
         */
        private final String name;
        /**
         * 属性的可移植类型。
         */
        private final PropertyType type;
        /**
         * 是否要求每个实体都提供该属性。
         */
        private final boolean required;
        /**
         * 属性展示、默认值和枚举元数据。
         */
        private final GraphPropertyMetadata metadata;

        /**
         * 创建属性定义。
         */
        public Property(String name, PropertyType type, boolean required) {
            this(name, type, required, GraphPropertyMetadata.empty());
        }

        /**
         * 创建带工具元数据的属性定义。
         */
        public Property(String name, PropertyType type, boolean required, GraphPropertyMetadata metadata) {
            this.name = GraphIdentifiers.requireValid(name, "property name");
            if (type == null) {
                throw new IllegalArgumentException("property type must not be null");
            }
            this.type = type;
            this.required = required;
            this.metadata = metadata == null ? GraphPropertyMetadata.empty() : metadata;
        }

        /**
         * @return 属性名
         */
        public String getName() {
            return name;
        }

        /**
         * @return 属性类型
         */
        public PropertyType getType() {
            return type;
        }

        /**
         * @return 是否必填
         */
        public boolean isRequired() {
            return required;
        }

        /**
         * @return 属性展示和默认值元数据。
         */
        public GraphPropertyMetadata getMetadata() {
            return metadata;
        }
    }

    public static final class NodeType {
        /**
         * 后端中的节点标签名。
         */
        private final String label;
        /**
         * 该标签声明的属性。
         */
        private final List<Property> properties;
        /**
         * 节点类型展示元数据。
         */
        private final GraphElementMetadata metadata;

        private NodeType(String label, List<Property> properties, GraphElementMetadata metadata) {
            this.label = GraphIdentifiers.requireValid(label, "node label");
            this.properties = immutable(properties);
            this.metadata = metadata == null ? GraphElementMetadata.empty() : metadata;
        }

        /**
         * @return 节点标签名
         */
        public String getLabel() {
            return label;
        }

        /**
         * @return 不可变属性列表
         */
        public List<Property> getProperties() {
            return properties;
        }

        /**
         * @return 节点类型展示元数据。
         */
        public GraphElementMetadata getMetadata() {
            return metadata;
        }

        /**
         * 创建节点类型；属性数组可以为空。
         */
        public static NodeType of(String label, Property... properties) {
            List<Property> values = new ArrayList<>();
            if (properties != null) {
                Collections.addAll(values, properties);
            }
            return new NodeType(label, values, GraphElementMetadata.empty());
        }

        /**
         * 创建带展示元数据的节点类型。
         */
        public static NodeType of(String label, GraphElementMetadata metadata, Property... properties) {
            List<Property> values = new ArrayList<>();
            if (properties != null) Collections.addAll(values, properties);
            return new NodeType(label, values, metadata);
        }
    }

    public static final class EdgeType {
        /**
         * 后端中的边类型名。
         */
        private final String type;
        /**
         * 允许的起点标签；为空表示后端没有端点约束元数据。
         */
        private final String sourceLabel;
        /**
         * 允许的终点标签；为空表示后端没有端点约束元数据。
         */
        private final String targetLabel;
        /**
         * 边属性定义。
         */
        private final List<Property> properties;
        /**
         * 边类型展示元数据。
         */
        private final GraphElementMetadata metadata;

        private EdgeType(String type, String sourceLabel, String targetLabel, List<Property> properties,
                         GraphElementMetadata metadata) {
            this.type = GraphIdentifiers.requireValid(type, "edge type");
            this.sourceLabel = sourceLabel == null ? null : GraphIdentifiers.requireValid(sourceLabel, "source label");
            this.targetLabel = targetLabel == null ? null : GraphIdentifiers.requireValid(targetLabel, "target label");
            this.properties = immutable(properties);
            this.metadata = metadata == null ? GraphElementMetadata.empty() : metadata;
        }

        /**
         * @return 边类型名
         */
        public String getType() {
            return type;
        }

        /**
         * @return 起点标签
         */
        public String getSourceLabel() {
            return sourceLabel;
        }

        /**
         * @return 终点标签
         */
        public String getTargetLabel() {
            return targetLabel;
        }

        /**
         * @return 不可变属性列表
         */
        public List<Property> getProperties() {
            return properties;
        }

        /**
         * @return 边类型展示元数据。
         */
        public GraphElementMetadata getMetadata() {
            return metadata;
        }

        /**
         * 创建边类型；属性数组可以为空。
         */
        public static EdgeType of(String type, String sourceLabel, String targetLabel, Property... properties) {
            List<Property> values = new ArrayList<>();
            if (properties != null) {
                Collections.addAll(values, properties);
            }
            return new EdgeType(type, sourceLabel, targetLabel, values, GraphElementMetadata.empty());
        }

        /**
         * 创建带展示元数据的边类型。
         */
        public static EdgeType of(String type, String sourceLabel, String targetLabel,
                                  GraphElementMetadata metadata, Property... properties) {
            List<Property> values = new ArrayList<>();
            if (properties != null) Collections.addAll(values, properties);
            return new EdgeType(type, sourceLabel, targetLabel, values, metadata);
        }

        /**
         * 创建不声明端点标签约束的边类型，主要用于后端 Schema 反查。
         */
        public static EdgeType any(String type, Property... properties) {
            return of(type, null, null, properties);
        }
    }

    public static final class Index {
        /**
         * 索引名称。
         */
        private final String name;
        /**
         * 索引作用于节点还是边。
         */
        private final IndexTarget target;
        /**
         * 被索引的标签或边类型。
         */
        private final String typeName;
        /**
         * 索引字段，当前后端通常要求至少一个字段。
         */
        private final List<String> properties;
        /**
         * 是否要求唯一性。
         */
        private final boolean unique;

        /**
         * 创建索引定义。
         */
        public Index(String name, IndexTarget target, String typeName, List<String> properties, boolean unique) {
            this.name = GraphIdentifiers.requireValid(name, "index name");
            if (target == null) {
                throw new IllegalArgumentException("index target must not be null");
            }
            this.target = target;
            this.typeName = GraphIdentifiers.requireValid(typeName, "index type name");
            if (properties == null || properties.isEmpty()) {
                throw new IllegalArgumentException("index properties must not be empty");
            }
            List<String> copy = new ArrayList<>();
            for (String property : properties) {
                copy.add(GraphIdentifiers.requireValid(property, "index property"));
            }
            this.properties = Collections.unmodifiableList(copy);
            this.unique = unique;
        }

        /**
         * @return 索引名
         */
        public String getName() {
            return name;
        }

        /**
         * @return 索引目标类型
         */
        public IndexTarget getTarget() {
            return target;
        }

        /**
         * @return 标签或边类型名
         */
        public String getTypeName() {
            return typeName;
        }

        /**
         * @return 不可变索引字段列表
         */
        public List<String> getProperties() {
            return properties;
        }

        /**
         * @return 是否唯一索引
         */
        public boolean isUnique() {
            return unique;
        }
    }
}
