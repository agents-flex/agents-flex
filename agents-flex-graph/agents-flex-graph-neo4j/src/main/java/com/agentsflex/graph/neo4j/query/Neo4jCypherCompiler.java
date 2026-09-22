package com.agentsflex.graph.neo4j.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;

import java.lang.reflect.Array;
import java.util.*;

/**
 * 将可移植遍历和过滤 DSL 编译为参数化 Cypher。
 */
final class Neo4jCypherCompiler {
    /**
     * 编译遍历查询。
     */
    Compiled compile(TraversalQuery query) {
        query.validate();
        StringBuilder cypher = new StringBuilder();
        Map<String, Object> parameters = new LinkedHashMap<>();
        cypher.append("MATCH ");
        if (hasPathProjection(query)) cypher.append("p = ");
        cypher.append(node(query.getStart()));
        for (TraversalQuery.Step step : query.getSteps()) {
            cypher.append(pattern(step));
        }
        if (query.getFilter() != null) {
            cypher.append(" WHERE ");
            appendFilter(cypher, query.getFilter(), parameters);
        }
        cypher.append(" RETURN ");
        if (query.isDistinct()) cypher.append("DISTINCT ");
        List<String> projections = new ArrayList<>();
        for (TraversalQuery.Projection projection : query.getProjections()) {
            if (projection.getKind() == TraversalQuery.ProjectionKind.PATH) {
                projections.add("p AS " + projection.getOutputName());
            } else if (projection.getKind() == TraversalQuery.ProjectionKind.ENTITY) {
                projections.add(projection.getAlias() + " AS " + projection.getOutputName());
            } else {
                projections.add(projection.getAlias() + "." + projection.getProperty()
                    + " AS " + projection.getOutputName());
            }
        }
        cypher.append(join(projections, ", "));
        if (!query.getSorts().isEmpty()) {
            cypher.append(" ORDER BY ");
            List<String> sorts = new ArrayList<>();
            for (TraversalQuery.Sort sort : query.getSorts()) {
                sorts.add(sort.getAlias() + "." + sort.getProperty() + " " + sort.getDirection().name());
            }
            cypher.append(join(sorts, ", "));
        }
        if (query.getSkip() > 0) cypher.append(" SKIP ").append(query.getSkip());
        cypher.append(" LIMIT ").append(query.getLimit());
        return new Compiled(cypher.toString(), parameters);
    }

    /**
     * 原生查询无需改写，仅包装为统一编译结果。
     */
    Compiled compile(NativeGraphQuery query) {
        return new Compiled(query.getStatement(), query.getParameters());
    }

    /**
     * 渲染节点模式。
     */
    private String node(TraversalQuery.NodePattern node) {
        return "(" + node.getAlias() + (node.getLabel() == null ? "" : ":" + node.getLabel()) + ")";
    }

    /**
     * 渲染一个有向、反向或双向遍历步骤。
     */
    private String pattern(TraversalQuery.Step step) {
        TraversalQuery.EdgePattern edge = step.getEdge();
        String range = edge.getMinHops() == 1 && edge.getMaxHops() == 1 ? "" : "*"
            + edge.getMinHops() + ".." + edge.getMaxHops();
        String rel = "[" + edge.getAlias() + ":" + edge.getType() + range + "]";
        String target = node(step.getNode());
        switch (edge.getDirection()) {
            case IN:
                return "< -".replace(" ", "") + rel + "-" + target;
            case BOTH:
                return "-" + rel + "-" + target;
            default:
                return "-" + rel + "->" + target;
        }
    }

    /**
     * 递归渲染过滤树，并为每个值分配参数名。
     */
    private void appendFilter(StringBuilder out, GraphFilter filter, Map<String, Object> parameters) {
        switch (filter.getKind()) {
            case PREDICATE:
                String left = filter.getAlias() + "." + filter.getProperty();
                switch (filter.getOperator()) {
                    case IS_NULL:
                        out.append(left).append(" IS NULL");
                        return;
                    case IS_NOT_NULL:
                        out.append(left).append(" IS NOT NULL");
                        return;
                    case IN:
                    case NOT_IN:
                        out.append(left).append(filter.getOperator() == GraphFilter.Operator.IN ? " IN " : " NOT IN ");
                        out.append(parameter(filter.getValue(), parameters));
                        return;
                    case BETWEEN:
                        List<Object> bounds = values(filter.getValue());
                        out.append(left).append(" >= ").append(parameter(bounds.get(0), parameters));
                        out.append(" AND ").append(left).append(" <= ").append(parameter(bounds.get(1), parameters));
                        return;
                    default:
                        out.append(left).append(operator(filter.getOperator())).append(parameter(filter.getValue(), parameters));
                        return;
                }
            case NOT:
                out.append("NOT (");
                appendFilter(out, filter.getChildren().get(0), parameters);
                out.append(")");
                return;
            case AND:
            case OR:
                out.append("(");
                String separator = filter.getKind() == GraphFilter.Kind.AND ? " AND " : " OR ";
                for (int i = 0; i < filter.getChildren().size(); i++) {
                    if (i > 0) out.append(separator);
                    appendFilter(out, filter.getChildren().get(i), parameters);
                }
                out.append(")");
                return;
            default:
                throw new IllegalArgumentException("Unsupported filter kind: " + filter.getKind());
        }
    }

    /**
     * 写入参数映射并返回 Cypher 参数占位符。
     */
    private String parameter(Object value, Map<String, Object> parameters) {
        String name = "p" + parameters.size();
        parameters.put(name, value);
        return "$" + name;
    }

    /**
     * 将统一运算符转换为 Cypher 运算符。
     */
    private String operator(GraphFilter.Operator operator) {
        switch (operator) {
            case EQ:
                return " = ";
            case NE:
                return " <> ";
            case GT:
                return " > ";
            case GE:
                return " >= ";
            case LT:
                return " < ";
            case LE:
                return " <= ";
            default:
                throw new IllegalArgumentException("Unsupported operator: " + operator);
        }
    }

    /**
     * 将 Collection 或数组展开为范围值列表。
     */
    private List<Object> values(Object value) {
        if (value instanceof Collection) return new ArrayList<>((Collection<?>) value);
        List<Object> result = new ArrayList<>();
        if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) result.add(Array.get(value, i));
        }
        return result;
    }

    /**
     * 以指定分隔符连接渲染片段。
     */
    private String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }

    /**
     * 判断是否需要在 MATCH 中绑定路径变量。
     */
    private boolean hasPathProjection(TraversalQuery query) {
        for (TraversalQuery.Projection projection : query.getProjections()) {
            if (projection.getKind() == TraversalQuery.ProjectionKind.PATH) return true;
        }
        return false;
    }

    static final class Compiled {
        /**
         * 编译后的 Cypher 文本。
         */
        final String statement;
        final Map<String, Object> parameters;

        /**
         * 创建编译结果。
         */
        Compiled(String statement, Map<String, Object> parameters) {
            this.statement = statement;
            this.parameters = parameters;
        }
    }
}
