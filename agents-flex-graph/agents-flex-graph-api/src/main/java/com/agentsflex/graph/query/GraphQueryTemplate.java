package com.agentsflex.graph.query;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可重复绑定参数的公共 Graph 查询模板。
 *
 * <p>模板只解析一次查询结构，并在 AST 中保留参数引用。每次调用 {@link #bind(Map)}
 * 都会创建新的不可变 {@link TraversalQuery}，因此同一模板可以安全地被并发请求复用。</p>
 */
public final class GraphQueryTemplate {
    /**
     * 原始查询表达式。
     */
    private final String expression;
    /**
     * 包含参数引用的模板 AST。
     */
    private final TraversalQuery template;
    /**
     * 按出现顺序保存的参数名称。
     */
    private final Set<String> parameterNames;

    /**
     * 创建模板并提取 AST 中的参数名称。
     */
    GraphQueryTemplate(String expression, TraversalQuery template) {
        this.expression = expression;
        this.template = template;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectFilterParameters(template.getFilter(), names);
        collectProperties(template.getStart().getProperties(), names);
        for (TraversalQuery.Step step : template.getSteps()) {
            collectProperties(step.getEdge().getProperties(), names);
            collectProperties(step.getNode().getProperties(), names);
        }
        this.parameterNames = Collections.unmodifiableSet(names);
    }

    /**
     * @return 原始查询表达式。
     */
    public String getExpression() {
        return expression;
    }

    /**
     * @return 模板要求绑定的只读参数名集合。
     */
    public Set<String> getParameterNames() {
        return parameterNames;
    }

    /**
     * 使用参数绑定模板。
     *
     * @param parameters 本次执行参数；不能缺少模板声明的参数
     * @return 不含参数引用的独立查询 AST
     */
    public TraversalQuery bind(Map<String, ?> parameters) {
        Map<String, ?> source = parameters == null
            ? Collections.<String, Object>emptyMap() : parameters;
        for (String name : parameterNames) {
            if (!source.containsKey(name)) {
                throw new IllegalArgumentException("missing query parameter '" + name + "'");
            }
        }
        Map<String, Object> values = immutableParameters(source);
        TraversalQuery.Builder builder = TraversalQuery.from(bindNode(template.getStart(), values));
        for (TraversalQuery.Step step : template.getSteps()) {
            builder.traverse(bindEdge(step.getEdge(), values), bindNode(step.getNode(), values));
        }
        if (template.getFilter() != null) builder.where(bindFilter(template.getFilter(), values));
        if (!template.getProjections().isEmpty()) {
            builder.select(template.getProjections().toArray(new TraversalQuery.Projection[0]));
        }
        for (TraversalQuery.Sort sort : template.getSorts()) builder.orderBy(sort);
        if (!template.getGroups().isEmpty()) {
            builder.groupBy(template.getGroups().toArray(new TraversalQuery.GroupKey[0]));
        }
        if (template.getHaving() != null) builder.having(bindFilter(template.getHaving(), values));
        builder.skip(template.getSkip()).limit(template.getLimit()).distinct(template.isDistinct());
        return builder.build();
    }

    /**
     * 绑定节点模式中的属性参数。
     */
    private static TraversalQuery.NodePattern bindNode(TraversalQuery.NodePattern node,
                                                       Map<String, Object> parameters) {
        Map<String, Object> properties = bindProperties(node.getProperties(), parameters);
        return node.getLabels().isEmpty()
            ? TraversalQuery.NodePattern.anyNode(node.getAlias(), properties)
            : TraversalQuery.NodePattern.node(node.getAlias(), node.getLabels(), properties);
    }

    /**
     * 绑定边模式中的属性参数。
     */
    private static TraversalQuery.EdgePattern bindEdge(TraversalQuery.EdgePattern edge,
                                                       Map<String, Object> parameters) {
        return (edge.getTypes().isEmpty()
            ? TraversalQuery.EdgePattern.edge(edge.getAlias(), (String) null, edge.getDirection(),
            bindProperties(edge.getProperties(), parameters))
            : TraversalQuery.EdgePattern.edge(edge.getAlias(), edge.getTypes(), edge.getDirection(),
            bindProperties(edge.getProperties(), parameters)))
            .hops(edge.getMinHops(), edge.getMaxHops());
    }

    /**
     * 深度绑定模式属性 Map。
     */
    private static Map<String, Object> bindProperties(Map<String, Object> properties,
                                                      Map<String, Object> parameters) {
        if (properties.isEmpty()) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            result.put(entry.getKey(), bindValue(entry.getValue(), parameters));
        }
        return result;
    }

    /**
     * 递归提取过滤树中的参数引用。
     */
    private static void collectFilterParameters(GraphFilter filter, Set<String> names) {
        if (filter == null) return;
        collectValueParameters(filter.getValue(), names);
        for (GraphFilter child : filter.getChildren()) collectFilterParameters(child, names);
    }

    /**
     * 递归提取值、集合和 Map 中的参数引用。
     */
    private static void collectValueParameters(Object value, Set<String> names) {
        if (value instanceof GraphParameterReference) {
            names.add(((GraphParameterReference) value).getName());
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                collectValueParameters(entry.getKey(), names);
                collectValueParameters(entry.getValue(), names);
            }
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) collectValueParameters(item, names);
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                collectValueParameters(Array.get(value, i), names);
            }
        }
    }

    /**
     * 提取节点或边属性 Map 中的参数引用。
     */
    private static void collectProperties(Map<String, Object> properties, Set<String> names) {
        for (Object value : properties.values()) collectValueParameters(value, names);
    }

    /**
     * 将参数引用替换成实际值并递归冻结容器。
     */
    private static Object bindValue(Object value, Map<String, Object> parameters) {
        if (value instanceof GraphParameterReference) {
            return parameters.get(((GraphParameterReference) value).getName());
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(bindValue(entry.getKey(), parameters), bindValue(entry.getValue(), parameters));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) copy.add(bindValue(item, parameters));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                copy.add(bindValue(Array.get(value, i), parameters));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * 根据过滤运算符重建不可变条件树。
     */
    private static GraphFilter bindFilter(GraphFilter filter, Map<String, Object> parameters) {
        if (filter.getKind() == GraphFilter.Kind.NOT) {
            return GraphFilter.not(bindFilter(filter.getChildren().get(0), parameters));
        }
        if (filter.getKind() == GraphFilter.Kind.AND || filter.getKind() == GraphFilter.Kind.OR) {
            List<GraphFilter> children = new ArrayList<>();
            for (GraphFilter child : filter.getChildren()) children.add(bindFilter(child, parameters));
            return filter.getKind() == GraphFilter.Kind.AND
                ? GraphFilter.and(children.toArray(new GraphFilter[0]))
                : GraphFilter.or(children.toArray(new GraphFilter[0]));
        }
        String alias = filter.getAlias();
        String property = filter.getProperty();
        if ((filter.getOperator() == GraphFilter.Operator.IN
            || filter.getOperator() == GraphFilter.Operator.NOT_IN)
            && filter.getValue() instanceof Collection
            && ((Collection<?>) filter.getValue()).size() == 1
            && ((Collection<?>) filter.getValue()).iterator().next() instanceof GraphParameterReference) {
            GraphParameterReference reference = (GraphParameterReference)
                ((Collection<?>) filter.getValue()).iterator().next();
            Collection<?> bound = collection(parameters.get(reference.getName()), filter.getOperator().name());
            return filter.getOperator() == GraphFilter.Operator.IN
                ? GraphFilter.in(alias, property, bound)
                : GraphFilter.notIn(alias, property, bound);
        }
        Object value = bindValue(filter.getValue(), parameters);
        switch (filter.getOperator()) {
            case IS_NULL:
                return GraphFilter.isNull(alias, property);
            case IS_NOT_NULL:
                return GraphFilter.isNotNull(alias, property);
            case IN:
                return GraphFilter.in(alias, property, collection(value, "IN"));
            case NOT_IN:
                return GraphFilter.notIn(alias, property, collection(value, "NOT IN"));
            case BETWEEN:
                List<?> bounds = new ArrayList<>(collection(value, "BETWEEN"));
                return GraphFilter.between(alias, property, bounds.get(0), bounds.get(1));
            default:
                return GraphFilter.predicate(alias, property, filter.getOperator(), value);
        }
    }

    /**
     * 将集合参数规范化为 GraphFilter 所需的 Collection。
     */
    private static Collection<?> collection(Object value, String operator) {
        if (value instanceof Collection) return (Collection<?>) value;
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) copy.add(Array.get(value, i));
            return copy;
        }
        throw new IllegalArgumentException(operator + " parameter must be a collection");
    }

    /**
     * 复制并深度冻结本次绑定参数。
     */
    private static Map<String, Object> immutableParameters(Map<String, ?> parameters) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            GraphIdentifiers.requireValid(entry.getKey(), "query parameter");
            copy.put(entry.getKey(), bindValue(entry.getValue(), Collections.<String, Object>emptyMap()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
