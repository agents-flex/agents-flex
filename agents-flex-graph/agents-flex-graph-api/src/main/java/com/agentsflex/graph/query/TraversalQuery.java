package com.agentsflex.graph.query;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 可移植的线性模式/遍历查询。
 */
public final class TraversalQuery implements GraphQuery {
    /**
     * 边遍历方向。
     */
    public enum Direction {
        /**
         * 沿出边遍历。
         */
        OUT,
        /**
         * 沿入边遍历。
         */
        IN,
        /**
         * 忽略方向双向遍历。
         */
        BOTH
    }

    /**
     * 投影内容类型。
     */
    public enum ProjectionKind {
        /**
         * 返回完整实体。
         */
        ENTITY,
        /**
         * 返回实体属性。
         */
        PROPERTY,
        /**
         * 返回完整路径。
         */
        PATH,
        /**
         * 聚合结果，例如计数、求和或平均值。
         */
        AGGREGATE
    }

    /**
     * 可移植聚合函数。聚合投影不包含后端方言，适配器负责编译成目标语言。
     */
    public enum AggregateFunction {
        /**
         * 统计记录数量。
         */
        COUNT,
        /**
         * 统计不重复值数量。
         */
        COUNT_DISTINCT,
        /**
         * 求和。
         */
        SUM,
        /**
         * 求平均值。
         */
        AVG,
        /**
         * 求最小值。
         */
        MIN,
        /**
         * 求最大值。
         */
        MAX
    }

    /**
     * 排序方向。
     */
    public enum SortDirection {
        /**
         * 升序。
         */
        ASC,
        /**
         * 降序。
         */
        DESC
    }

    /**
     * 起始节点模式。
     */
    private final NodePattern start;
    /**
     * 按顺序执行的遍历步骤。
     */
    private final List<Step> steps;
    /**
     * 可选过滤条件。
     */
    private final GraphFilter filter;
    /**
     * 返回列定义。
     */
    private final List<Projection> projections;
    /**
     * 排序列定义。
     */
    private final List<Sort> sorts;
    /**
     * 跳过记录数。
     */
    private final int skip;
    /**
     * 最大返回记录数。
     */
    private final int limit;
    /**
     * 是否去重。
     */
    private final boolean distinct;
    /**
     * 分组键；聚合查询可用来声明普通投影的分组语义。
     */
    private final List<GroupKey> groups;
    /**
     * 分组后的过滤条件。
     */
    private final GraphFilter having;

    /**
     * 构造并校验遍历查询。
     */
    private TraversalQuery(Builder builder) {
        this.start = builder.start;
        this.steps = immutable(builder.steps);
        this.filter = builder.filter;
        this.projections = immutable(builder.projections);
        this.sorts = immutable(builder.sorts);
        this.skip = builder.skip;
        this.limit = builder.limit;
        this.distinct = builder.distinct;
        this.groups = immutable(builder.groups);
        this.having = builder.having;
        validate();
    }

    /**
     * @param start 起始节点模式 @return 查询构造器
     */
    public static Builder from(NodePattern start) {
        return new Builder(start);
    }

    /**
     * 按分页请求创建同一查询的不可变副本。
     */
    public TraversalQuery page(GraphPageRequest page) {
        if (page == null) throw new IllegalArgumentException("page must not be null");
        Builder builder = new Builder(start);
        builder.steps.addAll(steps);
        builder.filter = filter;
        builder.projections.addAll(projections);
        builder.sorts.addAll(sorts);
        builder.skip = page.getOffset();
        builder.limit = page.getLimit();
        builder.distinct = distinct;
        builder.groups.addAll(groups);
        builder.having = having;
        return new TraversalQuery(builder);
    }

    /**
     * 校验别名唯一性、投影引用和分页边界。
     */
    @Override
    public void validate() {
        if (start == null) throw new IllegalArgumentException("start node pattern must not be null");
        if (limit <= 0 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        if (skip < 0) throw new IllegalArgumentException("skip must not be negative");
        Set<String> aliases = new HashSet<>();
        addAlias(aliases, start.getAlias());
        for (Step step : steps) {
            addAlias(aliases, step.getEdge().getAlias());
            addAlias(aliases, step.getNode().getAlias());
            if (!step.getEdge().getProperties().isEmpty()
                && (step.getEdge().getMinHops() != 1 || step.getEdge().getMaxHops() != 1)) {
                throw new IllegalArgumentException("edge property patterns cannot use variable-length hops");
            }
        }
        validateFilterAliases(filter, aliases);
        validateFilterAliases(having, aliases);
        for (Projection projection : projections) {
            if (projection.getKind() != ProjectionKind.PATH && !aliases.contains(projection.getAlias())) {
                throw new IllegalArgumentException("unknown projection alias: " + projection.getAlias());
            }
            if (projection.getKind() == ProjectionKind.AGGREGATE
                && projection.getAggregateFunction() != AggregateFunction.COUNT
                && projection.getProperty() == null) {
                throw new IllegalArgumentException("aggregate projection requires a property");
            }
        }
        Set<String> outputNames = new HashSet<>();
        boolean hasAggregate = false;
        boolean hasNonAggregate = false;
        for (Projection projection : projections) {
            if (!outputNames.add(projection.getOutputName())) {
                throw new IllegalArgumentException("duplicate projection output name: " + projection.getOutputName());
            }
            if (projection.getKind() == ProjectionKind.AGGREGATE) hasAggregate = true;
            else hasNonAggregate = true;
        }
        if (hasAggregate && hasNonAggregate && groups.isEmpty()) {
            throw new IllegalArgumentException("aggregate and non-aggregate projections require explicit grouping");
        }
        for (GroupKey group : groups) {
            if (!aliases.contains(group.getAlias()))
                throw new IllegalArgumentException("unknown group alias: " + group.getAlias());
        }
        if (hasAggregate && hasNonAggregate && !groups.isEmpty()) {
            for (Projection projection : projections) {
                if (projection.getKind() == ProjectionKind.PROPERTY && !containsGroup(groups,
                    projection.getAlias(), projection.getProperty())) {
                    throw new IllegalArgumentException("non-aggregate projection must be included in GROUP BY: "
                        + projection.getAlias() + "." + projection.getProperty());
                }
                if (projection.getKind() == ProjectionKind.ENTITY) {
                    throw new IllegalArgumentException("entity projection cannot be mixed with aggregate projection");
                }
            }
        }
        for (Sort sort : sorts) {
            if (!aliases.contains(sort.getAlias()))
                throw new IllegalArgumentException("unknown sort alias: " + sort.getAlias());
        }
    }

    /**
     * 加入别名并拒绝重复引用。
     */
    private static void addAlias(Set<String> aliases, String alias) {
        if (!aliases.add(alias)) throw new IllegalArgumentException("duplicate query alias: " + alias);
    }

    /**
     * 判断属性投影是否已声明为分组键。
     */
    private static boolean containsGroup(List<GroupKey> groups, String alias, String property) {
        for (GroupKey group : groups) {
            if (group.alias.equals(alias) && group.property.equals(property)) return true;
        }
        return false;
    }

    /**
     * 递归校验过滤条件中的别名。
     */
    private static void validateFilterAliases(GraphFilter filter, Set<String> aliases) {
        if (filter == null) return;
        if (filter.getKind() == GraphFilter.Kind.PREDICATE && !aliases.contains(filter.getAlias())) {
            throw new IllegalArgumentException("unknown filter alias: " + filter.getAlias());
        }
        for (GraphFilter child : filter.getChildren()) validateFilterAliases(child, aliases);
    }

    /**
     * 复制列表并暴露只读视图。
     */
    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * 校验属性名并递归冻结属性值，避免模式被外部 Map 修改。
     */
    private static Map<String, Object> immutableProperties(Map<String, ?> values, String name) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            copy.put(GraphIdentifiers.requireValid(entry.getKey(), name), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 递归冻结属性模式中的数组、集合和 Map。
     */
    private static Object immutableValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(immutableValue(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (java.util.Collection<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                copy.add(immutableValue(java.lang.reflect.Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * @return 起始节点模式
     */
    public NodePattern getStart() {
        return start;
    }

    /**
     * @return 只读遍历步骤
     */
    public List<Step> getSteps() {
        return steps;
    }

    /**
     * @return 过滤条件
     */
    public GraphFilter getFilter() {
        return filter;
    }

    /**
     * @return 只读投影列表
     */
    public List<Projection> getProjections() {
        return projections;
    }

    /**
     * @return 只读排序列表
     */
    public List<Sort> getSorts() {
        return sorts;
    }

    /**
     * @return 跳过数量
     */
    public int getSkip() {
        return skip;
    }

    /**
     * @return 最大返回数
     */
    public int getLimit() {
        return limit;
    }

    /**
     * @return 是否去重
     */
    public boolean isDistinct() {
        return distinct;
    }

    /**
     * @return 分组键的只读列表。
     */
    public List<GroupKey> getGroups() {
        return groups;
    }

    /**
     * @return HAVING 过滤条件；没有分组过滤时返回 null。
     */
    public GraphFilter getHaving() {
        return having;
    }

    /**
     * @return 是否包含变长路径步骤
     */
    public boolean hasVariableLengthStep() {
        for (Step step : steps) if (step.edge.minHops != 1 || step.edge.maxHops != 1) return true;
        return false;
    }

    public static final class NodePattern {
        /**
         * 查询中的节点别名。
         */
        private final String alias;
        /**
         * 节点标签；为空表示任意标签。为兼容旧 API，getLabel() 返回第一个标签。
         */
        private final List<String> labels;
        /**
         * 节点模式中的属性约束；为空表示不限制属性。
         */
        private final Map<String, Object> properties;

        /**
         * 创建节点模式。
         */
        private NodePattern(String alias, String label) {
            this(alias, label == null ? Collections.<String>emptyList() : Collections.singletonList(label),
                Collections.<String, Object>emptyMap());
        }

        /**
         * 创建带标签和属性约束的节点模式。
         */
        private NodePattern(String alias, String label, Map<String, ?> properties) {
            this(alias, label == null ? Collections.<String>emptyList() : Collections.singletonList(label), properties);
        }

        /**
         * 创建带多个标签和属性约束的节点模式。
         */
        private NodePattern(String alias, List<String> labels, Map<String, ?> properties) {
            this.alias = GraphIdentifiers.requireValid(alias, "node alias");
            if (labels == null) throw new IllegalArgumentException("node labels must not be null");
            List<String> copied = new ArrayList<>();
            for (String label : labels) {
                String valid = GraphIdentifiers.requireValid(label, "node label");
                if (copied.contains(valid)) throw new IllegalArgumentException("duplicate node label: " + valid);
                copied.add(valid);
            }
            this.labels = Collections.unmodifiableList(copied);
            this.properties = immutableProperties(properties, "node property");
        }

        /**
         * 创建带标签的节点模式。
         */
        public static NodePattern node(String alias, String label) {
            return new NodePattern(alias, label);
        }

        /**
         * 创建不限制标签的节点模式。
         */
        public static NodePattern anyNode(String alias) {
            return new NodePattern(alias, null);
        }

        /**
         * 创建不限制标签但带属性约束的节点模式。
         */
        public static NodePattern anyNode(String alias, Map<String, ?> properties) {
            return new NodePattern(alias, Collections.<String>emptyList(), properties);
        }

        /**
         * 创建带标签和属性约束的节点模式。
         */
        public static NodePattern node(String alias, String label, Map<String, ?> properties) {
            return new NodePattern(alias, label, properties);
        }

        /**
         * 创建带多个标签和属性约束的节点模式。
         */
        public static NodePattern node(String alias, List<String> labels, Map<String, ?> properties) {
            return new NodePattern(alias, labels, properties);
        }

        /**
         * 创建带多个标签且不带属性约束的节点模式。
         */
        public static NodePattern node(String alias, List<String> labels) {
            return new NodePattern(alias, labels, Collections.<String, Object>emptyMap());
        }

        /**
         * @return 节点别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 节点标签
         */
        public String getLabel() {
            return labels.isEmpty() ? null : labels.get(0);
        }

        /**
         * @return 节点标签的只读列表；空列表表示任意标签。
         */
        public List<String> getLabels() {
            return labels;
        }

        /**
         * @return 节点属性约束的只读快照。
         */
        public Map<String, Object> getProperties() {
            return properties;
        }
    }

    public static final class EdgePattern {
        /**
         * 查询中的边别名。
         */
        private final String alias;
        /**
         * 边类型。
         */
        private final List<String> types;
        /**
         * 遍历方向。
         */
        private final Direction direction;
        /**
         * 最小跳数。
         */
        private final int minHops;
        /**
         * 最大跳数。
         */
        private final int maxHops;
        /**
         * 边模式中的属性约束；为空表示不限制属性。
         */
        private final Map<String, Object> properties;

        /**
         * 创建边模式并校验跳数范围。
         */
        private EdgePattern(String alias, String type, Direction direction, int minHops, int maxHops) {
            this(alias, type, direction, minHops, maxHops, Collections.<String, Object>emptyMap());
        }

        /**
         * 创建带属性约束的边模式并校验所有结构字段。
         */
        private EdgePattern(String alias, String type, Direction direction, int minHops, int maxHops,
                            Map<String, ?> properties) {
            this(alias, type == null ? Collections.<String>emptyList() : Collections.singletonList(type), direction,
                minHops, maxHops, properties);
        }

        /**
         * 创建带多个边类型的边模式。
         */
        private EdgePattern(String alias, List<String> types, Direction direction, int minHops, int maxHops,
                            Map<String, ?> properties) {
            this.alias = GraphIdentifiers.requireValid(alias, "edge alias");
            if (types == null) throw new IllegalArgumentException("edge types must not be null");
            List<String> copied = new ArrayList<>();
            for (String type : types) {
                String valid = GraphIdentifiers.requireValid(type, "edge type");
                if (copied.contains(valid)) throw new IllegalArgumentException("duplicate edge type: " + valid);
                copied.add(valid);
            }
            this.types = Collections.unmodifiableList(copied);
            if (direction == null) throw new IllegalArgumentException("edge direction must not be null");
            if (minHops < 1 || maxHops < minHops || maxHops > 16) {
                throw new IllegalArgumentException("hop range must satisfy 1 <= min <= max <= 16");
            }
            this.direction = direction;
            this.minHops = minHops;
            this.maxHops = maxHops;
            this.properties = immutableProperties(properties, "edge property");
        }

        /**
         * 创建单跳边模式。
         */
        public static EdgePattern edge(String alias, String type, Direction direction) {
            return new EdgePattern(alias, type, direction, 1, 1);
        }

        /**
         * 创建带属性约束的单跳边模式。
         */
        public static EdgePattern edge(String alias, String type, Direction direction,
                                       Map<String, ?> properties) {
            return new EdgePattern(alias, type, direction, 1, 1, properties);
        }

        /**
         * 创建带多个边类型的单跳边模式。
         */
        public static EdgePattern edge(String alias, List<String> types, Direction direction,
                                       Map<String, ?> properties) {
            return new EdgePattern(alias, types, direction, 1, 1, properties);
        }

        /**
         * 创建带多个边类型且不带属性约束的单跳边模式。
         */
        public static EdgePattern edge(String alias, List<String> types, Direction direction) {
            return new EdgePattern(alias, types, direction, 1, 1, Collections.<String, Object>emptyMap());
        }

        /**
         * 返回具有新跳数范围的边模式。
         */
        public EdgePattern hops(int min, int max) {
            return new EdgePattern(alias, types, direction, min, max, properties);
        }

        /**
         * @return 边别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 边类型
         * {@code null} 表示匹配任意边类型
         */
        public String getType() {
            return types.isEmpty() ? null : types.get(0);
        }

        /**
         * @return 边类型的只读列表；空列表表示任意边类型。
         */
        public List<String> getTypes() {
            return types;
        }

        /**
         * @return 遍历方向
         */
        public Direction getDirection() {
            return direction;
        }

        /**
         * @return 最小跳数
         */
        public int getMinHops() {
            return minHops;
        }

        /**
         * @return 最大跳数
         */
        public int getMaxHops() {
            return maxHops;
        }

        /**
         * @return 边属性约束的只读快照。
         */
        public Map<String, Object> getProperties() {
            return properties;
        }
    }

    public static final class Step {
        /**
         * 当前步骤使用的边模式。
         */
        private final EdgePattern edge;
        /**
         * 当前步骤到达的节点模式。
         */
        private final NodePattern node;

        /**
         * 创建遍历步骤。
         */
        private Step(EdgePattern edge, NodePattern node) {
            if (edge == null || node == null) throw new IllegalArgumentException("step patterns must not be null");
            this.edge = edge;
            this.node = node;
        }

        /**
         * @return 边模式
         */
        public EdgePattern getEdge() {
            return edge;
        }

        /**
         * @return 目标节点模式
         */
        public NodePattern getNode() {
            return node;
        }
    }

    public static final class Projection {
        /**
         * 投影种类。
         */
        private final ProjectionKind kind;
        /**
         * 投影引用的别名。
         */
        private final String alias;
        /**
         * 属性投影的属性名。
         */
        private final String property;
        /**
         * 返回记录中的列名。
         */
        private final String outputName;
        /**
         * 聚合函数；非聚合投影为空。
         */
        private final AggregateFunction aggregateFunction;

        /**
         * 创建投影定义。
         */
        private Projection(ProjectionKind kind, String alias, String property, String outputName) {
            this(kind, alias, property, outputName, null);
        }

        private Projection(ProjectionKind kind, String alias, String property, String outputName,
                           AggregateFunction aggregateFunction) {
            this.kind = kind;
            this.alias = alias;
            this.property = property;
            this.outputName = GraphIdentifiers.requireValid(outputName, "projection output name");
            this.aggregateFunction = aggregateFunction;
        }

        /**
         * 投影整个节点或边实体。
         */
        public static Projection entity(String alias) {
            return new Projection(ProjectionKind.ENTITY, GraphIdentifiers.requireValid(alias, "projection alias"), null, alias);
        }

        /**
         * 投影完整实体并指定结果列别名。
         */
        public static Projection entity(String alias, String outputName) {
            return new Projection(ProjectionKind.ENTITY,
                GraphIdentifiers.requireValid(alias, "projection alias"), null, outputName);
        }

        /**
         * 投影实体上的单个属性。
         */
        public static Projection property(String alias, String property, String outputName) {
            return new Projection(ProjectionKind.PROPERTY, GraphIdentifiers.requireValid(alias, "projection alias"),
                GraphIdentifiers.requireValid(property, "projection property"), outputName);
        }

        /**
         * 投影完整路径。
         */
        public static Projection path(String outputName) {
            return new Projection(ProjectionKind.PATH, null, null, outputName);
        }

        /**
         * 创建针对完整实体的计数聚合。
         */
        public static Projection count(String alias, String outputName) {
            return aggregate(AggregateFunction.COUNT, alias, null, outputName);
        }

        /**
         * 创建针对属性的聚合投影。COUNT_DISTINCT 可用于去重计数。
         */
        public static Projection aggregate(AggregateFunction function, String alias, String property,
                                           String outputName) {
            if (function == null) throw new IllegalArgumentException("aggregate function must not be null");
            String validAlias = GraphIdentifiers.requireValid(alias, "aggregate alias");
            if (function != AggregateFunction.COUNT && property == null) {
                throw new IllegalArgumentException(function + " requires an aggregate property");
            }
            String validProperty = property == null ? null : GraphIdentifiers.requireValid(property, "aggregate property");
            return new Projection(ProjectionKind.AGGREGATE, validAlias, validProperty, outputName, function);
        }

        /**
         * @return 投影种类
         */
        public ProjectionKind getKind() {
            return kind;
        }

        /**
         * @return 引用别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 属性名
         */
        public String getProperty() {
            return property;
        }

        /**
         * @return 输出列名
         */
        public String getOutputName() {
            return outputName;
        }

        /**
         * @return 聚合函数；普通投影返回 {@code null}。
         */
        public AggregateFunction getAggregateFunction() {
            return aggregateFunction;
        }
    }

    public static final class Sort {
        /**
         * 排序引用的别名。
         */
        private final String alias;
        /**
         * 排序属性。
         */
        private final String property;
        /**
         * 排序方向。
         */
        private final SortDirection direction;

        /**
         * 创建排序定义。
         */
        public Sort(String alias, String property, SortDirection direction) {
            this.alias = GraphIdentifiers.requireValid(alias, "sort alias");
            this.property = GraphIdentifiers.requireValid(property, "sort property");
            if (direction == null) throw new IllegalArgumentException("sort direction must not be null");
            this.direction = direction;
        }

        /**
         * @return 排序别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 排序属性
         */
        public String getProperty() {
            return property;
        }

        /**
         * @return 排序方向
         */
        public SortDirection getDirection() {
            return direction;
        }
    }

    /**
     * 聚合查询的分组键。
     */
    public static final class GroupKey {
        private final String alias;
        private final String property;

        /**
         * 创建分组键。
         */
        public GroupKey(String alias, String property) {
            this.alias = GraphIdentifiers.requireValid(alias, "group alias");
            this.property = GraphIdentifiers.requireValid(property, "group property");
        }

        public String getAlias() {
            return alias;
        }

        public String getProperty() {
            return property;
        }
    }

    public static final class Builder {
        /**
         * 起始节点。
         */
        private final NodePattern start;
        /**
         * 遍历步骤。
         */
        private final List<Step> steps = new ArrayList<>();
        /**
         * 过滤条件。
         */
        private GraphFilter filter;
        /**
         * 投影定义。
         */
        private final List<Projection> projections = new ArrayList<>();
        /**
         * 排序定义。
         */
        private final List<Sort> sorts = new ArrayList<>();
        /**
         * 默认不跳过记录。
         */
        private int skip;
        /**
         * 默认最多返回 100 条。
         */
        private int limit = 100;
        /**
         * 默认不去重。
         */
        private boolean distinct;
        /**
         * 分组键。
         */
        private final List<GroupKey> groups = new ArrayList<>();
        /**
         * 分组过滤条件。
         */
        private GraphFilter having;

        /**
         * 创建查询构造器。
         */
        private Builder(NodePattern start) {
            this.start = start;
        }

        /**
         * 添加一跳遍历。
         */
        public Builder traverse(EdgePattern edge, NodePattern node) {
            steps.add(new Step(edge, node));
            return this;
        }

        /**
         * 设置过滤条件。
         */
        public Builder where(GraphFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * 设置返回投影；未设置时默认返回最后一个节点。
         */
        public Builder select(Projection... values) {
            projections.addAll(Arrays.asList(values));
            return this;
        }

        /**
         * 添加排序规则。
         */
        public Builder orderBy(Sort sort) {
            sorts.add(sort);
            return this;
        }

        /**
         * 设置跳过数量。
         */
        public Builder skip(int skip) {
            this.skip = skip;
            return this;
        }

        /**
         * 设置返回上限。
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * 设置是否去重。
         */
        public Builder distinct(boolean distinct) {
            this.distinct = distinct;
            return this;
        }

        /**
         * 添加分组键。
         */
        public Builder groupBy(GroupKey... keys) {
            if (keys != null) for (GroupKey key : keys) {
                if (key == null) throw new IllegalArgumentException("group key must not be null");
                groups.add(key);
            }
            return this;
        }

        /**
         * 设置聚合后的 HAVING 条件。
         */
        public Builder having(GraphFilter filter) {
            this.having = filter;
            return this;
        }

        /**
         * @return 校验后的查询对象
         */
        public TraversalQuery build() {
            if (projections.isEmpty()) {
                String alias = steps.isEmpty() ? start.getAlias() : steps.get(steps.size() - 1).node.getAlias();
                projections.add(Projection.entity(alias));
            }
            return new TraversalQuery(this);
        }
    }
}
