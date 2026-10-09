package com.agentsflex.graph.tools;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphPageResult;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.GraphResultMetadata;
import com.agentsflex.graph.query.GraphSubgraphResult;
import com.agentsflex.graph.schema.GraphSchema;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把后端查询结果规范化为模型可消费的 JSON，并执行第二层 Schema 过滤。
 *
 * <p>查询在执行前已经过 Schema 校验，但数据库中的实体可能包含 Schema 未公开的额外标签或属性。
 * 本类会递归处理节点、边、子图、集合和 Map，确保完整实体或路径投影不会绕过公开 Schema。</p>
 *
 * <p>{@link GraphResult#getQueryText()} 可能包含后端方言、物理空间或诊断信息，输出时不会复制。</p>
 */
final class GraphResultFormatter {
    /**
     * 防止恶意或异常嵌套结果导致无限递归。
     */
    private static final int MAX_NESTING_DEPTH = 20;

    /**
     * 工具类不允许实例化。
     */
    private GraphResultFormatter() {
    }

    /**
     * 格式化一个分页结果。
     *
     * @param source 当前知识源，用于逻辑名称和公开 Schema
     * @param page   后端返回的当前页
     * @return 不包含后端查询文本的 JSON
     */
    static String format(KnowledgeGraphSource source, GraphPageResult page) {
        GraphResult result = page.getResult();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("knowledgeSourceName", source.getName());

        List<Map<String, Object>> records = new ArrayList<>();
        for (GraphRecord record : result.getRecords()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : record.getValues().entrySet()) {
                values.put(entry.getKey(), normalize(entry.getValue(), source.getSchema(), 0));
            }
            records.add(values);
        }
        root.put("records", records);

        GraphResultMetadata metadata = result.getMetadata();
        Map<String, Object> metadataValue = new LinkedHashMap<>();
        metadataValue.put("recordCount", records.size());
        metadataValue.put("truncated", metadata.isTruncated());
        metadataValue.put("executionTimeMillis", metadata.getExecutionTimeMillis());
        metadataValue.put("hasNext", page.hasNext());
        metadataValue.put("nextCursor", page.getNextCursor());
        root.put("metadata", metadataValue);
        return JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
    }

    /**
     * 递归规范化任意投影值。
     *
     * <p>标量属性保持原值；图实体转换为显式结构；容器递归处理。这样聚合值、属性投影和路径投影
     * 可以共享同一套输出协议。</p>
     */
    private static Object normalize(Object value, GraphSchema schema, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_NESTING_DEPTH) {
            return "[maximum nesting depth reached]";
        }
        if (value instanceof GraphNode) {
            return node((GraphNode) value, schema, depth + 1);
        }
        if (value instanceof GraphEdge) {
            return edge((GraphEdge) value, schema, depth + 1);
        }
        if (value instanceof GraphSubgraphResult) {
            return subgraph((GraphSubgraphResult) value, schema, depth + 1);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), normalize(entry.getValue(), schema, depth + 1));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                result.add(normalize(item, schema, depth + 1));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(normalize(Array.get(value, i), schema, depth + 1));
            }
            return result;
        }
        return value;
    }

    /**
     * 输出节点身份、公开标签和公开属性。
     *
     * <p>节点可能同时具有多个标签，只有存在于公开 Schema 的标签才参与属性允许列表计算。</p>
     */
    private static Map<String, Object> node(GraphNode node, GraphSchema schema, int depth) {
        Set<String> exposedLabels = new LinkedHashSet<>();
        Set<String> exposedProperties = new LinkedHashSet<>();
        for (String label : node.getLabels()) {
            GraphSchema.NodeType type = findNode(schema, label);
            if (type == null) {
                continue;
            }
            exposedLabels.add(label);
            for (GraphSchema.Property property : type.getProperties()) {
                exposedProperties.add(property.getName());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "node");
        result.put("id", node.getId());
        result.put("labels", new ArrayList<>(exposedLabels));
        result.put("properties", filterProperties(node.getProperties(), exposedProperties, schema, depth));
        return result;
    }

    /**
     * 输出边身份和公开属性。属性允许列表只取自该边类型的公开定义。
     */
    private static Map<String, Object> edge(GraphEdge edge, GraphSchema schema, int depth) {
        GraphSchema.EdgeType type = findEdge(schema, edge.getType());
        Set<String> exposedProperties = new LinkedHashSet<>();
        if (type != null) {
            for (GraphSchema.Property property : type.getProperties()) {
                exposedProperties.add(property.getName());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "edge");
        result.put("sourceId", edge.getSourceId());
        result.put("type", edge.getType());
        result.put("targetId", edge.getTargetId());
        result.put("rank", edge.getRank());
        result.put("properties", filterProperties(edge.getProperties(), exposedProperties, schema, depth));
        return result;
    }

    /**
     * 递归格式化路径或子图中的所有节点和边。
     */
    private static Map<String, Object> subgraph(GraphSubgraphResult graph, GraphSchema schema, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "subgraph");
        List<Object> nodes = new ArrayList<>();
        for (GraphNode node : graph.getNodes()) {
            nodes.add(node(node, schema, depth + 1));
        }
        List<Object> edges = new ArrayList<>();
        for (GraphEdge edge : graph.getEdges()) {
            edges.add(edge(edge, schema, depth + 1));
        }
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    /**
     * 按允许列表复制属性，并递归规范化属性值中的容器或图实体。
     */
    private static Map<String, Object> filterProperties(Map<String, Object> values, Collection<String> allowed, GraphSchema schema,
        int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : allowed) {
            if (values.containsKey(name)) {
                result.put(name, normalize(values.get(name), schema, depth + 1));
            }
        }
        return result;
    }

    /**
     * 查找公开节点类型；结果格式化阶段对未知标签采取忽略策略。
     */
    private static GraphSchema.NodeType findNode(GraphSchema schema, String label) {
        for (GraphSchema.NodeType node : schema.getNodeTypes()) {
            if (node.getLabel().equals(label)) {
                return node;
            }
        }
        return null;
    }

    /**
     * 查找公开边类型；未知边不会获得任何可输出属性。
     */
    private static GraphSchema.EdgeType findEdge(GraphSchema schema, String type) {
        for (GraphSchema.EdgeType edge : schema.getEdgeTypes()) {
            if (edge.getType().equals(type)) {
                return edge;
            }
        }
        return null;
    }
}
