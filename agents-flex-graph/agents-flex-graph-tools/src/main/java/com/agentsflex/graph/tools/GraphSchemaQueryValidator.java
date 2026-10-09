package com.agentsflex.graph.tools;

import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.schema.GraphSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用知识源公开 Schema 校验 Portable TraversalQuery。
 *
 * <p>GraphQueryParser 负责语法和 AST 自身的一致性，本类在其上增加知识源权限边界：查询中的标签、
 * 边类型、属性、关系端点、方向和跳数都必须落在公开 Schema 内。校验发生在调用数据库之前。</p>
 */
final class GraphSchemaQueryValidator {
    /**
     * 工具类不允许实例化。
     */
    private GraphSchemaQueryValidator() {
    }

    /**
     * 完整校验一个线性遍历查询。
     *
     * @param query   已由公共查询解析器生成的遍历 AST
     * @param schema  当前知识源允许模型访问的 Schema
     * @param maxHops 当前知识源允许的最大累计跳数
     */
    static void validate(TraversalQuery query, GraphSchema schema, int maxHops) {
        // 先建立别名到公开类型的映射，后续过滤、投影、排序和分组统一复用该映射。
        Map<String, AliasDefinition> aliases = new LinkedHashMap<>();
        TraversalQuery.NodePattern current = query.getStart();
        AliasDefinition currentAlias = nodeAlias(current, schema);
        aliases.put(current.getAlias(), currentAlias);
        validatePatternProperties(current.getProperties(), currentAlias);

        int hops = 0;
        for (TraversalQuery.Step step : query.getSteps()) {
            TraversalQuery.EdgePattern edge = step.getEdge();
            TraversalQuery.NodePattern node = step.getNode();
            hops += edge.getMaxHops();
            if (hops > maxHops) {
                reject("Query traverses up to " + hops + " hops; this source allows at most " + maxHops);
            }

            AliasDefinition edgeAlias = edgeAlias(edge, schema);
            AliasDefinition nodeAlias = nodeAlias(node, schema);
            validatePatternProperties(edge.getProperties(), edgeAlias);
            validatePatternProperties(node.getProperties(), nodeAlias);
            validateEndpoints(currentAlias.names, nodeAlias.names, edge, schema);
            aliases.put(edge.getAlias(), edgeAlias);
            aliases.put(node.getAlias(), nodeAlias);
            current = node;
            currentAlias = nodeAlias;
        }

        validateFilter(query.getFilter(), aliases);
        validateFilter(query.getHaving(), aliases);
        for (TraversalQuery.Projection projection : query.getProjections()) {
            if (projection.getProperty() != null) {
                requireProperty(aliases, projection.getAlias(), projection.getProperty());
            }
        }
        for (TraversalQuery.Sort sort : query.getSorts()) {
            requireProperty(aliases, sort.getAlias(), sort.getProperty());
        }
        for (TraversalQuery.GroupKey group : query.getGroups()) {
            requireProperty(aliases, group.getAlias(), group.getProperty());
        }
    }

    /**
     * 解析节点模式对应的公开类型；无标签节点会绕过 Schema 边界，因此直接拒绝。
     */
    private static AliasDefinition nodeAlias(TraversalQuery.NodePattern pattern, GraphSchema schema) {
        if (pattern.getLabels().isEmpty()) {
            reject("Every node pattern must declare an exposed label");
        }
        List<GraphSchema.NodeType> types = new ArrayList<>();
        for (String label : pattern.getLabels()) {
            types.add(GraphSchemaFormatter.findNode(schema, label));
        }
        return AliasDefinition.nodes(types);
    }

    /**
     * 解析边模式对应的公开类型；无类型边可能遍历到未授权关系，因此直接拒绝。
     */
    private static AliasDefinition edgeAlias(TraversalQuery.EdgePattern pattern, GraphSchema schema) {
        if (pattern.getTypes().isEmpty()) {
            reject("Every edge pattern must declare an exposed edge type");
        }
        List<GraphSchema.EdgeType> types = new ArrayList<>();
        for (String type : pattern.getTypes()) {
            types.add(GraphSchemaFormatter.findEdge(schema, type));
        }
        return AliasDefinition.edges(types);
    }

    /**
     * 校验 MATCH 模式内联属性，例如 {@code (p:Person {name: :name})}。
     */
    private static void validatePatternProperties(Map<String, Object> properties, AliasDefinition alias) {
        for (String property : properties.keySet()) {
            if (!alias.hasProperty(property)) {
                reject("Unknown or unavailable property '" + property + "'");
            }
        }
    }

    /**
     * 递归校验 WHERE 和 HAVING 过滤树引用的属性。
     */
    private static void validateFilter(GraphFilter filter, Map<String, AliasDefinition> aliases) {
        if (filter == null) {
            return;
        }
        if (filter.getKind() == GraphFilter.Kind.PREDICATE) {
            requireProperty(aliases, filter.getAlias(), filter.getProperty());
        }
        for (GraphFilter child : filter.getChildren()) {
            validateFilter(child, aliases);
        }
    }

    /**
     * 确认别名存在，且目标属性对该别名代表的类型可见。
     */
    private static void requireProperty(Map<String, AliasDefinition> aliases, String alias, String property) {
        AliasDefinition definition = aliases.get(alias);
        if (definition == null) {
            reject("Unknown query alias: " + alias);
        }
        if (!definition.hasProperty(property)) {
            reject("Property '" + property + "' is not exposed for alias '" + alias + "'");
        }
    }

    /**
     * 校验关系端点和箭头方向是否与 Schema 声明一致。
     *
     * <p>某些后端反查得到的 Schema 可能没有端点信息；端点为空时只能校验边类型本身，方向检查会跳过。</p>
     */
    private static void validateEndpoints(List<String> fromLabels, List<String> toLabels,
                                          TraversalQuery.EdgePattern pattern, GraphSchema schema) {
        for (String type : pattern.getTypes()) {
            GraphSchema.EdgeType edge = GraphSchemaFormatter.findEdge(schema, type);
            if (edge.getSourceLabel() == null || edge.getTargetLabel() == null) {
                continue;
            }
            boolean forward = fromLabels.contains(edge.getSourceLabel()) && toLabels.contains(edge.getTargetLabel());
            boolean reverse = fromLabels.contains(edge.getTargetLabel()) && toLabels.contains(edge.getSourceLabel());
            boolean compatible;
            switch (pattern.getDirection()) {
                case OUT:
                    compatible = forward;
                    break;
                case IN:
                    compatible = reverse;
                    break;
                case BOTH:
                    compatible = forward || reverse;
                    break;
                default:
                    compatible = false;
            }
            if (!compatible) {
                reject("Edge type '" + type + "' is incompatible with the declared node labels and direction");
            }
        }
    }

    /**
     * 以稳定错误码终止校验，让 Tool 能将异常转换成模型可纠正的软错误。
     */
    private static void reject(String message) {
        throw new KnowledgeGraphToolException("QUERY_NOT_ALLOWED", message);
    }

    /**
     * 一个查询别名允许代表的公开类型及属性集合。
     */
    private static final class AliasDefinition {
        /**
         * 节点标签或边类型名称，用于端点校验。
         */
        private final List<String> names;
        /**
         * 每个候选类型各自的属性集合。
         */
        private final List<List<GraphSchema.Property>> propertySets;
        /**
         * 多边类型选择时，属性是否必须存在于所有候选边类型中。
         */
        private final boolean requirePropertyOnEveryType;

        private AliasDefinition(List<String> names, List<List<GraphSchema.Property>> propertySets,
                                boolean requirePropertyOnEveryType) {
            this.names = names;
            this.propertySets = propertySets;
            this.requirePropertyOnEveryType = requirePropertyOnEveryType;
        }

        /**
         * 节点多标签表示同一节点同时具备这些标签，因此属性取标签属性的并集。
         */
        static AliasDefinition nodes(List<GraphSchema.NodeType> types) {
            List<String> names = new ArrayList<>();
            List<List<GraphSchema.Property>> properties = new ArrayList<>();
            for (GraphSchema.NodeType type : types) {
                names.add(type.getLabel());
                properties.add(type.getProperties());
            }
            return new AliasDefinition(names, properties, false);
        }

        /**
         * 多边类型表示运行时可能命中任一类型，因此属性必须被每种候选边共同支持。
         */
        static AliasDefinition edges(List<GraphSchema.EdgeType> types) {
            List<String> names = new ArrayList<>();
            List<List<GraphSchema.Property>> properties = new ArrayList<>();
            for (GraphSchema.EdgeType type : types) {
                names.add(type.getType());
                properties.add(type.getProperties());
            }
            return new AliasDefinition(names, properties, true);
        }

        /**
         * 根据节点并集或边交集规则判断属性是否可用。
         */
        boolean hasProperty(String name) {
            boolean found = false;
            for (List<GraphSchema.Property> properties : propertySets) {
                boolean inSet = false;
                for (GraphSchema.Property property : properties) {
                    if (property.getName().equals(name)) {
                        inSet = true;
                        found = true;
                        break;
                    }
                }
                if (requirePropertyOnEveryType && !inSet) {
                    return false;
                }
            }
            return found;
        }
    }
}
