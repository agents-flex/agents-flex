package com.agentsflex.graph.tools;

import com.agentsflex.graph.schema.GraphElementMetadata;
import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaMetadata;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把公开 GraphSchema 转换为适合大模型消费的分层 JSON。
 *
 * <p>摘要阶段只返回类型名称、说明和关系端点；详情阶段才返回属性。两阶段输出相互配合，避免
 * 把完整 Schema 一次性塞入模型上下文。</p>
 */
final class GraphSchemaFormatter {
    /**
     * 工具类不允许实例化。
     */
    private GraphSchemaFormatter() {
    }

    /**
     * 生成第一阶段类型摘要，不披露任何属性定义。
     *
     * @param source 已通过白名单注册的知识源
     * @return 格式化后的 JSON 文本
     */
    static String summary(KnowledgeGraphSource source) {
        GraphSchema schema = source.getSchema();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("knowledgeSourceName", source.getName());
        root.put("schema", schemaMetadata(schema.getMetadata()));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphSchema.NodeType node : schema.getNodeTypes()) {
            Map<String, Object> value = element(node.getLabel(), node.getMetadata());
            nodes.add(value);
        }
        root.put("nodeTypes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (GraphSchema.EdgeType edge : schema.getEdgeTypes()) {
            Map<String, Object> value = element(edge.getType(), edge.getMetadata());
            value.put("sourceLabel", edge.getSourceLabel());
            value.put("targetLabel", edge.getTargetLabel());
            edges.add(value);
        }
        root.put("edgeTypes", edges);
        root.put("nextStep", "Call describeKnowledgeGraphTypes for only the node and edge types needed by the query.");
        return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
    }

    /**
     * 生成第二阶段 Schema 详情，只展开调用方明确选择的节点和边类型。
     *
     * @param source     知识源
     * @param nodeLabels 需要展开的节点标签
     * @param edgeTypes  需要展开的边类型
     * @return 格式化后的 JSON 文本
     */
    static String details(KnowledgeGraphSource source, List<String> nodeLabels, List<String> edgeTypes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("knowledgeSourceName", source.getName());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String label : nodeLabels) {
            GraphSchema.NodeType node = findNode(source.getSchema(), label);
            Map<String, Object> value = element(node.getLabel(), node.getMetadata());
            value.put("properties", properties(node.getProperties()));
            nodes.add(value);
        }
        root.put("nodeTypes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (String type : edgeTypes) {
            GraphSchema.EdgeType edge = findEdge(source.getSchema(), type);
            Map<String, Object> value = element(edge.getType(), edge.getMetadata());
            value.put("sourceLabel", edge.getSourceLabel());
            value.put("targetLabel", edge.getTargetLabel());
            value.put("properties", properties(edge.getProperties()));
            edges.add(value);
        }
        root.put("edgeTypes", edges);
        root.put("nextStep", "Build a portable MATCH query using only the returned names, then call queryKnowledgeGraph.");
        return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
    }

    /**
     * 提取 Schema 级展示元数据，并限制自由文本长度。
     */
    private static Map<String, Object> schemaMetadata(GraphSchemaMetadata metadata) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", ToolText.clean(metadata.getId(), 200));
        value.put("version", ToolText.clean(metadata.getVersion(), 100));
        value.put("displayName", ToolText.clean(metadata.getDisplayName(), 300));
        value.put("description", ToolText.clean(metadata.getDescription(), 1_000));
        return value;
    }

    /**
     * 生成节点或边类型共有的名称和展示信息。
     */
    private static Map<String, Object> element(String name, GraphElementMetadata metadata) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("displayName", ToolText.clean(metadata.getDisplayName(), 300));
        value.put("description", ToolText.clean(metadata.getDescription(), 1_000));
        return value;
    }

    /**
     * 生成模型写查询所需的精确属性契约。
     *
     * <p>默认值可能携带业务数据，当前 Tool 不需要它来构造只读查询，因此不对模型披露。</p>
     */
    private static List<Map<String, Object>> properties(List<GraphSchema.Property> properties) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (GraphSchema.Property property : properties) {
            GraphPropertyMetadata metadata = property.getMetadata();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", property.getName());
            value.put("type", property.getType().name());
            value.put("required", property.isRequired());
            value.put("displayName", ToolText.clean(metadata.getDisplayName(), 300));
            value.put("description", ToolText.clean(metadata.getDescription(), 1_000));
            if (!metadata.getEnumValues().isEmpty()) {
                value.put("enumValues", metadata.getEnumValues());
            }
            values.add(value);
        }
        return values;
    }

    /**
     * 按大小写敏感的精确名称查找公开节点类型。
     */
    static GraphSchema.NodeType findNode(GraphSchema schema, String label) {
        for (GraphSchema.NodeType node : schema.getNodeTypes()) {
            if (node.getLabel().equals(label)) {
                return node;
            }
        }
        throw new KnowledgeGraphToolException("UNKNOWN_GRAPH_TYPE", "Unknown node label: " + label);
    }

    /**
     * 按大小写敏感的精确名称查找公开边类型。
     */
    static GraphSchema.EdgeType findEdge(GraphSchema schema, String type) {
        for (GraphSchema.EdgeType edge : schema.getEdgeTypes()) {
            if (edge.getType().equals(type)) {
                return edge;
            }
        }
        throw new KnowledgeGraphToolException("UNKNOWN_GRAPH_TYPE", "Unknown edge type: " + type);
    }
}
