package com.agentsflex.graph.query;

import com.agentsflex.graph.identifier.GraphIdentifiers;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可移植查询使用的不可变过滤条件树。
 *
 * <p>叶子节点表示属性谓词，内部节点表示 AND、OR、NOT 组合。适配器负责将该结构
 * 编译为目标数据库方言，并通过参数绑定传递值。</p>
 */
public final class GraphFilter {
    /**
     * 条件节点的结构类型。
     */
    public enum Kind {
        /**
         * 单个属性谓词。
         */
        PREDICATE,
        /**
         * 所有子条件都满足。
         */
        AND,
        /**
         * 任一子条件满足。
         */
        OR,
        /**
         * 子条件取反。
         */
        NOT
    }

    /**
     * 支持的属性比较运算符。
     */
    public enum Operator {
        /**
         * 等于。
         */
        EQ,
        /**
         * 不等于。
         */
        NE,
        /**
         * 大于。
         */
        GT,
        /**
         * 大于等于。
         */
        GE,
        /**
         * 小于。
         */
        LT,
        /**
         * 小于等于。
         */
        LE,
        /**
         * 集合包含。
         */
        IN,
        /**
         * 集合不包含。
         */
        NOT_IN,
        /**
         * 闭区间范围。
         */
        BETWEEN,
        /**
         * 为空。
         */
        IS_NULL,
        /**
         * 非空。
         */
        IS_NOT_NULL,
        /**
         * 字符串包含子串。
         */
        CONTAINS,
        /**
         * 字符串以前缀开头。
         */
        STARTS_WITH,
        /**
         * 字符串以后缀结尾。
         */
        ENDS_WITH,
        /**
         * 使用后端兼容的正则表达式匹配。
         */
        REGEX
    }

    /**
     * 该节点的结构类型。
     */
    private final Kind kind;
    /**
     * 谓词引用的查询别名。
     */
    private final String alias;
    /**
     * 谓词引用的属性名。
     */
    private final String property;
    /**
     * 谓词运算符。
     */
    private final Operator operator;
    /**
     * 比较值或值集合。
     */
    private final Object value;
    /**
     * 组合节点的子条件。
     */
    private final List<GraphFilter> children;

    /**
     * 创建不可变条件节点。
     */
    private GraphFilter(Kind kind, String alias, String property, Operator operator,
                        Object value, List<GraphFilter> children) {
        this.kind = kind;
        this.alias = alias;
        this.property = property;
        this.operator = operator;
        this.value = immutableValue(value);
        this.children = children == null ? Collections.<GraphFilter>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(children));
    }

    /**
     * 创建通用属性谓词。
     */
    public static GraphFilter predicate(String alias, String property, Operator operator, Object value) {
        GraphIdentifiers.requireValid(alias, "filter alias");
        GraphIdentifiers.requireValid(property, "filter property");
        if (operator == null) throw new IllegalArgumentException("filter operator must not be null");
        validateValue(operator, value);
        return new GraphFilter(Kind.PREDICATE, alias, property, operator, value, null);
    }

    /**
     * 创建等于谓词。
     */
    public static GraphFilter eq(String alias, String property, Object value) {
        return predicate(alias, property, Operator.EQ, value);
    }

    /**
     * 创建不等于谓词。
     */
    public static GraphFilter ne(String alias, String property, Object value) {
        return predicate(alias, property, Operator.NE, value);
    }

    /**
     * 创建大于谓词。
     */
    public static GraphFilter gt(String alias, String property, Object value) {
        return predicate(alias, property, Operator.GT, value);
    }

    /**
     * 创建大于等于谓词。
     */
    public static GraphFilter ge(String alias, String property, Object value) {
        return predicate(alias, property, Operator.GE, value);
    }

    /**
     * 创建小于谓词。
     */
    public static GraphFilter lt(String alias, String property, Object value) {
        return predicate(alias, property, Operator.LT, value);
    }

    /**
     * 创建小于等于谓词。
     */
    public static GraphFilter le(String alias, String property, Object value) {
        return predicate(alias, property, Operator.LE, value);
    }

    /**
     * 创建集合包含谓词。
     */
    public static GraphFilter in(String alias, String property, Collection<?> value) {
        return predicate(alias, property, Operator.IN, value);
    }

    /**
     * 创建集合不包含谓词。
     */
    public static GraphFilter notIn(String alias, String property, Collection<?> value) {
        return predicate(alias, property, Operator.NOT_IN, value);
    }

    /**
     * 创建闭区间范围谓词。
     */
    public static GraphFilter between(String alias, String property, Object lower, Object upper) {
        List<Object> values = new ArrayList<>();
        values.add(lower);
        values.add(upper);
        return predicate(alias, property, Operator.BETWEEN, values);
    }

    /**
     * 创建为空谓词。
     */
    public static GraphFilter isNull(String alias, String property) {
        return predicate(alias, property, Operator.IS_NULL, null);
    }

    /**
     * 创建非空谓词。
     */
    public static GraphFilter isNotNull(String alias, String property) {
        return predicate(alias, property, Operator.IS_NOT_NULL, null);
    }

    /**
     * 创建字符串包含谓词。
     */
    public static GraphFilter contains(String alias, String property, Object value) {
        return predicate(alias, property, Operator.CONTAINS, value);
    }

    /**
     * 创建字符串前缀谓词。
     */
    public static GraphFilter startsWith(String alias, String property, Object value) {
        return predicate(alias, property, Operator.STARTS_WITH, value);
    }

    /**
     * 创建字符串后缀谓词。
     */
    public static GraphFilter endsWith(String alias, String property, Object value) {
        return predicate(alias, property, Operator.ENDS_WITH, value);
    }

    /**
     * 创建正则表达式谓词。
     */
    public static GraphFilter regex(String alias, String property, Object value) {
        return predicate(alias, property, Operator.REGEX, value);
    }

    /**
     * 使用 AND 组合多个条件。
     */
    public static GraphFilter and(GraphFilter... filters) {
        return composite(Kind.AND, filters);
    }

    /**
     * 使用 OR 组合多个条件。
     */
    public static GraphFilter or(GraphFilter... filters) {
        return composite(Kind.OR, filters);
    }

    /**
     * 对单个条件取反。
     */
    public static GraphFilter not(GraphFilter filter) {
        if (filter == null) throw new IllegalArgumentException("NOT filter must not be null");
        return new GraphFilter(Kind.NOT, null, null, null, null, Collections.singletonList(filter));
    }

    /**
     * 校验并创建 AND/OR 组合节点。
     */
    private static GraphFilter composite(Kind kind, GraphFilter... filters) {
        if (filters == null || filters.length == 0) {
            throw new IllegalArgumentException(kind + " requires at least one filter");
        }
        List<GraphFilter> children = new ArrayList<>();
        for (GraphFilter filter : filters) {
            if (filter == null) throw new IllegalArgumentException(kind + " filter must not be null");
            children.add(filter);
        }
        return new GraphFilter(kind, null, null, null, null, children);
    }

    /**
     * 校验 IN、NOT_IN、BETWEEN 所需的集合形态。
     */
    private static void validateValue(Operator operator, Object value) {
        if (operator == Operator.IS_NULL || operator == Operator.IS_NOT_NULL) return;
        if (operator == Operator.IN || operator == Operator.NOT_IN || operator == Operator.BETWEEN) {
            int size = collectionSize(value);
            int expected = operator == Operator.BETWEEN ? 2 : 1;
            if (size < expected || (operator == Operator.BETWEEN && size != 2)) {
                throw new IllegalArgumentException(operator + " has an invalid value list");
            }
        }
    }

    /**
     * 同时支持 Collection 和 Java 数组，返回其元素数量。
     */
    private static int collectionSize(Object value) {
        if (value instanceof Collection) return ((Collection<?>) value).size();
        return value != null && value.getClass().isArray() ? Array.getLength(value) : 0;
    }

    /**
     * 冻结过滤值的容器结构，避免调用方在查询创建后修改 IN、BETWEEN 或复合参数，
     * 导致编译结果、分页指纹和实际执行条件不一致。
     */
    private static Object immutableValue(Object value) {
        if (value == null) return null;
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(immutableValue(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) copy.add(immutableValue(Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * @return 节点结构类型
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return 谓词别名，组合节点为 {@code null}
     */
    public String getAlias() {
        return alias;
    }

    /**
     * @return 属性名，组合节点为 {@code null}
     */
    public String getProperty() {
        return property;
    }

    /**
     * @return 运算符，组合节点为 {@code null}
     */
    public Operator getOperator() {
        return operator;
    }

    /**
     * @return 比较值
     */
    public Object getValue() {
        return value;
    }

    /**
     * @return 只读子条件列表
     */
    public List<GraphFilter> getChildren() {
        return children;
    }
}
