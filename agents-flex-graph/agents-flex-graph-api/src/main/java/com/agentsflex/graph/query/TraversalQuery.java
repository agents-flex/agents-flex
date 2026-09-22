package com.agentsflex.graph.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.identifier.GraphIdentifiers;
import com.agentsflex.graph.query.GraphQuery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
        PATH
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
        validate();
    }

    /**
     * @param start 起始节点模式 @return 查询构造器
     */
    public static Builder from(NodePattern start) {
        return new Builder(start);
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
        }
        validateFilterAliases(filter, aliases);
        for (Projection projection : projections) {
            if (projection.getKind() != ProjectionKind.PATH && !aliases.contains(projection.getAlias())) {
                throw new IllegalArgumentException("unknown projection alias: " + projection.getAlias());
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
         * 节点标签；为空表示任意标签。
         */
        private final String label;

        /**
         * 创建节点模式。
         */
        private NodePattern(String alias, String label) {
            this.alias = GraphIdentifiers.requireValid(alias, "node alias");
            this.label = label == null ? null : GraphIdentifiers.requireValid(label, "node label");
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
         * @return 节点别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 节点标签
         */
        public String getLabel() {
            return label;
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
        private final String type;
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
         * 创建边模式并校验跳数范围。
         */
        private EdgePattern(String alias, String type, Direction direction, int minHops, int maxHops) {
            this.alias = GraphIdentifiers.requireValid(alias, "edge alias");
            this.type = GraphIdentifiers.requireValid(type, "edge type");
            if (direction == null) throw new IllegalArgumentException("edge direction must not be null");
            if (minHops < 1 || maxHops < minHops || maxHops > 16) {
                throw new IllegalArgumentException("hop range must satisfy 1 <= min <= max <= 16");
            }
            this.direction = direction;
            this.minHops = minHops;
            this.maxHops = maxHops;
        }

        /**
         * 创建单跳边模式。
         */
        public static EdgePattern edge(String alias, String type, Direction direction) {
            return new EdgePattern(alias, type, direction, 1, 1);
        }

        /**
         * 返回具有新跳数范围的边模式。
         */
        public EdgePattern hops(int min, int max) {
            return new EdgePattern(alias, type, direction, min, max);
        }

        /**
         * @return 边别名
         */
        public String getAlias() {
            return alias;
        }

        /**
         * @return 边类型
         */
        public String getType() {
            return type;
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
         * 创建投影定义。
         */
        private Projection(ProjectionKind kind, String alias, String property, String outputName) {
            this.kind = kind;
            this.alias = alias;
            this.property = property;
            this.outputName = GraphIdentifiers.requireValid(outputName, "projection output name");
        }

        /**
         * 投影整个节点或边实体。
         */
        public static Projection entity(String alias) {
            return new Projection(ProjectionKind.ENTITY, GraphIdentifiers.requireValid(alias, "projection alias"), null, alias);
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
