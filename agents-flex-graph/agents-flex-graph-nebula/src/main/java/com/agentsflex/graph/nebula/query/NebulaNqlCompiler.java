package com.agentsflex.graph.nebula.query;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.query.TraversalQuery;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将可移植遍历查询编译为 Nebula nGQL。
 */
final class NebulaNqlCompiler {
    /**
     * 编译遍历查询。
     */
    Compiled compile(TraversalQuery query) {
        query.validate();
        StringBuilder nql = new StringBuilder("MATCH ");
        if (hasPathProjection(query)) nql.append("path = ");
        nql.append(node(query.getStart()));
        for (TraversalQuery.Step step : query.getSteps()) nql.append(pattern(step));
        Map<String, Object> params = new LinkedHashMap<>();
        if (query.getFilter() != null) {
            nql.append(" WHERE ");
            appendFilter(nql, query.getFilter(), params);
        }
        nql.append(" RETURN ");
        List<String> projections = new ArrayList<>();
        for (TraversalQuery.Projection projection : query.getProjections()) {
            if (projection.getKind() == TraversalQuery.ProjectionKind.ENTITY) {
                projections.add(projection.getAlias() + " AS " + projection.getOutputName());
            } else if (projection.getKind() == TraversalQuery.ProjectionKind.PROPERTY) {
                projections.add(projection.getAlias() + "." + projection.getProperty() + " AS " + projection.getOutputName());
            } else if (projection.getKind() == TraversalQuery.ProjectionKind.AGGREGATE) {
                projections.add(aggregate(projection) + " AS " + projection.getOutputName());
            } else {
                projections.add("path AS " + projection.getOutputName());
            }
        }
        nql.append(join(projections, ", "));
        if (!query.getSorts().isEmpty()) {
            nql.append(" ORDER BY ");
            List<String> sorts = new ArrayList<>();
            for (TraversalQuery.Sort sort : query.getSorts())
                sorts.add(sort.getAlias() + "." + sort.getProperty() + " " + sort.getDirection().name());
            nql.append(join(sorts, ", "));
        }
        if (query.getSkip() > 0) nql.append(" SKIP ").append(query.getSkip());
        nql.append(" LIMIT ").append(query.getLimit());
        return new Compiled(nql.toString(), params);
    }

    /**
     * 原生查询直接包装为编译结果。
     */
    Compiled compile(NativeGraphQuery query) {
        return new Compiled(query.getStatement(), query.getParameters());
    }

    /**
     * 编译多分支 UNION，并为每个分支重命名参数避免冲突。
     */
    Compiled compile(GraphUnionQuery query) {
        query.validate();
        StringBuilder statement = new StringBuilder();
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < query.getBranches().size(); i++) {
            if (i > 0) statement.append(query.isAll() ? " UNION ALL " : " UNION ");
            Compiled branch = compile(query.getBranches().get(i));
            String renamed = branch.statement;
            for (String name : branch.parameters.keySet()) {
                String target = "u" + i + "_" + name;
                renamed = renamed.replace("$" + name, "$" + target);
                parameters.put(target, branch.parameters.get(name));
            }
            statement.append(renamed);
        }
        return new Compiled(statement.toString(), parameters);
    }

    /**
     * 渲染节点模式。
     */
    private String node(TraversalQuery.NodePattern node) {
        return "(" + node.getAlias() + (node.getLabel() == null ? "" : ":" + node.getLabel()) + ")";
    }

    /**
     * 渲染一跳边模式。
     */
    private String pattern(TraversalQuery.Step step) {
        TraversalQuery.EdgePattern edge = step.getEdge();
        String range = edge.getMinHops() == 1 && edge.getMaxHops() == 1 ? "" : "*"
            + edge.getMinHops() + ".." + edge.getMaxHops();
        String rel = "[" + edge.getAlias() + ":" + edge.getType() + range + "]";
        String node = node(step.getNode());
        switch (edge.getDirection()) {
            case IN:
                return "<-[" + edge.getAlias() + ":" + edge.getType() + range + "]-" + node;
            case BOTH:
                return "-[" + edge.getAlias() + ":" + edge.getType() + range + "]-" + node;
            default:
                return "-[" + edge.getAlias() + ":" + edge.getType() + range + "]->" + node;
        }
    }

    /**
     * 递归渲染过滤树并绑定参数。
     */
    private void appendFilter(StringBuilder out, GraphFilter filter, Map<String, Object> params) {
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
                        out.append(left).append(filter.getOperator() == GraphFilter.Operator.IN ? " IN " : " NOT IN ").append(parameter(filter.getValue(), params));
                        return;
                    case BETWEEN:
                        List<Object> bounds = values(filter.getValue());
                        out.append(left).append(" >= ").append(parameter(bounds.get(0), params)).append(" AND ").append(left).append(" <= ").append(parameter(bounds.get(1), params));
                        return;
                    default:
                        out.append(left).append(operator(filter.getOperator())).append(parameter(filter.getValue(), params));
                        return;
                }
            case NOT:
                out.append("NOT (");
                appendFilter(out, filter.getChildren().get(0), params);
                out.append(")");
                return;
            case AND:
            case OR:
                out.append("(");
                String separator = filter.getKind() == GraphFilter.Kind.AND ? " AND " : " OR ";
                for (int i = 0; i < filter.getChildren().size(); i++) {
                    if (i > 0) out.append(separator);
                    appendFilter(out, filter.getChildren().get(i), params);
                }
                out.append(")");
                return;
            default:
                throw new IllegalArgumentException("Unsupported filter kind: " + filter.getKind());
        }
    }

    /**
     * 添加参数并返回占位符。
     */
    private String parameter(Object value, Map<String, Object> params) {
        String name = "p" + params.size();
        params.put(name, value);
        return "$" + name;
    }

    /**
     * 映射统一比较运算符。
     */
    private String operator(GraphFilter.Operator op) {
        switch (op) {
            case EQ:
                return " == ";
            case NE:
                return " != ";
            case GT:
                return " > ";
            case GE:
                return " >= ";
            case LT:
                return " < ";
            case LE:
                return " <= ";
            default:
                throw new IllegalArgumentException("Unsupported operator: " + op);
        }
    }

    /**
     * 将可移植聚合投影转换为 nGQL 聚合表达式。
     */
    private String aggregate(TraversalQuery.Projection projection) {
        String expression = projection.getProperty() == null
            ? projection.getAlias() : projection.getAlias() + "." + projection.getProperty();
        switch (projection.getAggregateFunction()) {
            case COUNT:
                return "count(" + expression + ")";
            case COUNT_DISTINCT:
                return "count(distinct " + expression + ")";
            case SUM:
                return "sum(" + expression + ")";
            case AVG:
                return "avg(" + expression + ")";
            case MIN:
                return "min(" + expression + ")";
            case MAX:
                return "max(" + expression + ")";
            default:
                throw new IllegalArgumentException("Unsupported aggregate function: "
                    + projection.getAggregateFunction());
        }
    }

    /**
     * 展开范围条件的集合或数组。
     */
    private List<Object> values(Object value) {
        if (value instanceof Collection) return new ArrayList<>((Collection<?>) value);
        List<Object> result = new ArrayList<>();
        if (value != null && value.getClass().isArray())
            for (int i = 0; i < Array.getLength(value); i++) result.add(Array.get(value, i));
        return result;
    }

    /**
     * 连接渲染片段。
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
     * 判断是否需要绑定路径变量。
     */
    private boolean hasPathProjection(TraversalQuery query) {
        for (TraversalQuery.Projection projection : query.getProjections()) {
            if (projection.getKind() == TraversalQuery.ProjectionKind.PATH) return true;
        }
        return false;
    }

    static final class Compiled {
        /**
         * 编译后的 nGQL。
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
