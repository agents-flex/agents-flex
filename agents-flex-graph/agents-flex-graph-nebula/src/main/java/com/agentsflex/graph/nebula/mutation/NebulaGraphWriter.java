package com.agentsflex.graph.nebula.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;
import com.vesoft.nebula.client.graph.data.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将统一变更编译为 Nebula UPSERT/DELETE nGQL。
 */
public final class NebulaGraphWriter implements GraphWriter {
    /**
     * 所属存储。
     */
    private final NebulaGraphStore store;
    /**
     * 默认空间配置。
     */
    private final NebulaGraphStoreConfig config;

    /**
     * 创建写入器。
     */
    public NebulaGraphWriter(NebulaGraphStore store, NebulaGraphStoreConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * 执行删除和 upsert；SessionPool 调用不提供事务保证。
     */
    @Override
    public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
        if (mutation == null || mutation.isEmpty()) return GraphWriteResult.success(0, 0);
        try {
            String space = options == null ? config.getDefaultSpace() : options.getSpaceOrDefault(config.getDefaultSpace());
            com.vesoft.nebula.client.graph.SessionPool pool = store.pool(space);
            long nodes = 0, edges = 0;
            for (String id : mutation.getDeleteNodeIds()) {
                execute(pool, "DELETE VERTEX $id" + (mutation.isDetachDeletedNodes() ? " WITH EDGE" : ""),
                    map("id", id));
                nodes++;
            }
            for (GraphEdgeKey key : mutation.getDeleteEdgeKeys()) {
                execute(pool, "DELETE EDGE " + key.getType() + " $source -> $target @ $rank",
                    map("source", key.getSourceId(), "target", key.getTargetId(), "rank", key.getRank()));
                edges++;
            }
            for (GraphNode node : mutation.getNodes()) {
                upsertNode(pool, node);
                nodes++;
            }
            for (GraphEdge edge : mutation.getEdges()) {
                upsertEdge(pool, edge);
                edges++;
            }
            return GraphWriteResult.success(nodes, edges);
        } catch (Exception e) {
            return GraphWriteResult.failure("Nebula mutation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 写入单个节点；Nebula 仅支持单 TAG 节点。
     */
    private void upsertNode(com.vesoft.nebula.client.graph.SessionPool pool, GraphNode node) {
        if (node.getLabels().size() > 1) {
            throw new com.agentsflex.graph.UnsupportedGraphFeatureException(
                "Nebula Graph supports one tag per vertex in the portable writer");
        }
        String tag = node.getLabelList().get(0);
        if (node.getProperties().isEmpty()) {
            execute(pool, "INSERT VERTEX " + tag + "() VALUES $id:()", map("id", node.getId()));
            return;
        }
        StringBuilder statement = new StringBuilder("UPSERT VERTEX ON ").append(tag).append(" SET ");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("id", node.getId());
        int index = 0;
        for (Map.Entry<String, Object> property : node.getProperties().entrySet()) {
            if (index > 0) statement.append(", ");
            String parameter = "p" + index++;
            statement.append(tag).append(".").append(property.getKey()).append(" = $").append(parameter);
            parameters.put(parameter, property.getValue());
        }
        statement.append(" WHERE id == $id");
        execute(pool, statement.toString(), parameters);
    }

    /**
     * 写入单条边及其属性。
     */
    private void upsertEdge(com.vesoft.nebula.client.graph.SessionPool pool, GraphEdge edge) {
        if (edge.getProperties().isEmpty()) {
            execute(pool, "INSERT EDGE " + edge.getType() + "() VALUES $source -> $target @ $rank:()",
                map("source", edge.getSourceId(), "target", edge.getTargetId(), "rank", edge.getRank()));
            return;
        }
        StringBuilder statement = new StringBuilder("UPSERT EDGE ").append(edge.getType())
            .append(" $source -> $target @ $rank SET ");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("source", edge.getSourceId());
        parameters.put("target", edge.getTargetId());
        parameters.put("rank", edge.getRank());
        int index = 0;
        for (Map.Entry<String, Object> property : edge.getProperties().entrySet()) {
            if (index > 0) statement.append(", ");
            String parameter = "p" + index++;
            statement.append(edge.getType()).append(".").append(property.getKey()).append(" = $").append(parameter);
            parameters.put(parameter, property.getValue());
        }
        execute(pool, statement.toString(), parameters);
    }

    /**
     * 执行 nGQL 并检查 ResultSet 状态。
     */
    private void execute(com.vesoft.nebula.client.graph.SessionPool pool, String statement, Map<String, Object> parameters) {
        try {
            ResultSet result = pool.execute(statement, parameters);
            if (!result.isSucceeded()) throw new IllegalStateException(result.getErrorMessage());
        } catch (Exception e) {
            throw new IllegalStateException("Nebula mutation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 节点优先、分批执行在线导入。
     */
    @Override
    public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
        if (request == null) throw new IllegalArgumentException("import request must not be null");
        GraphImportReport report = new GraphImportReport();
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode node : request.getNodes()) {
            nodes.add(node);
            if (nodes.size() >= request.getBatchSize()) {
                add(report, mutate(GraphMutation.builder().upsertNodes(nodes).build(), options), request.isStopOnError());
                nodes.clear();
            }
        }
        if (!nodes.isEmpty())
            add(report, mutate(GraphMutation.builder().upsertNodes(nodes).build(), options), request.isStopOnError());
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge edge : request.getEdges()) {
            edges.add(edge);
            if (edges.size() >= request.getBatchSize()) {
                add(report, mutate(GraphMutation.builder().upsertEdges(edges).build(), options), request.isStopOnError());
                edges.clear();
            }
        }
        if (!edges.isEmpty())
            add(report, mutate(GraphMutation.builder().upsertEdges(edges).build(), options), request.isStopOnError());
        return report;
    }

    /**
     * 合并批次结果并按失败策略抛出异常。
     */
    private void add(GraphImportReport report, GraphWriteResult result, boolean stopOnError) {
        report.add(result);
        if (stopOnError && !result.isSuccess()) throw new IllegalStateException(result.getMessage(), result.getError());
    }

    /**
     * 构造 nGQL 参数映射。
     */
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
