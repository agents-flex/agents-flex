package com.agentsflex.graph.nebula.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.error.GraphErrorCode;
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
     * 空间刚创建或 Schema 刚传播时，StorageD 可能短暂返回 Not leader；最多重试若干次。
     */
    private static final int MAX_RETRY_ATTEMPTS = 5;
    /**
     * leader 选举/分片注册的退避起始间隔（毫秒）。
     */
    private static final long RETRY_BACKOFF_MILLIS = 200L;
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
                execute(pool, "DELETE VERTEX " + literal(id)
                        + (mutation.isDetachDeletedNodes() ? " WITH EDGE" : ""),
                    java.util.Collections.<String, Object>emptyMap());
                nodes++;
            }
            for (GraphEdgeKey key : mutation.getDeleteEdgeKeys()) {
                execute(pool, "DELETE EDGE " + key.getType() + " " + literal(key.getSourceId())
                        + " -> " + literal(key.getTargetId()) + " @ " + key.getRank(),
                    java.util.Collections.<String, Object>emptyMap());
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
            return GraphWriteResult.failure(GraphErrorCode.WRITE_FAILED,
                "Nebula mutation failed: " + e.getMessage(), e);
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
            // 空属性也必须保持 upsert 的幂等语义；普通 INSERT 在重复导入时会失败。
            execute(pool, "INSERT VERTEX IF NOT EXISTS " + tag + "() VALUES " + literal(node.getId()) + ":()",
                java.util.Collections.<String, Object>emptyMap());
            return;
        }
        // Nebula UPSERT 的 VID 位于 TAG 名称之后，不使用 SQL 风格的 WHERE 子句。
        StringBuilder statement = new StringBuilder("UPSERT VERTEX ON ").append(tag).append(" ")
            .append(literal(node.getId())).append(" SET ");
        Map<String, Object> parameters = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Object> property : node.getProperties().entrySet()) {
            if (index > 0) statement.append(", ");
            String parameter = "p" + index++;
            statement.append(tag).append(".").append(property.getKey()).append(" = $").append(parameter);
            parameters.put(parameter, property.getValue());
        }
        execute(pool, statement.toString(), parameters);
    }

    /**
     * 写入单条边及其属性。
     */
    private void upsertEdge(com.vesoft.nebula.client.graph.SessionPool pool, GraphEdge edge) {
        if (edge.getProperties().isEmpty()) {
            // 与节点一致，使用 IF NOT EXISTS 避免重复导入无属性边时报错。
            execute(pool, "INSERT EDGE IF NOT EXISTS " + edge.getType() + "() VALUES " + literal(edge.getSourceId())
                    + " -> " + literal(edge.getTargetId()) + " @ " + edge.getRank() + ":()",
                java.util.Collections.<String, Object>emptyMap());
            return;
        }
        StringBuilder statement = new StringBuilder("UPSERT EDGE ON ").append(edge.getType())
            .append(" ").append(literal(edge.getSourceId())).append(" -> ")
            .append(literal(edge.getTargetId())).append(" @ ").append(edge.getRank()).append(" SET ");
        Map<String, Object> parameters = new LinkedHashMap<>();
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
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                ResultSet result = pool.execute(statement, parameters);
                if (!result.isSucceeded()) throw new IllegalStateException(result.getErrorMessage());
                return;
            } catch (Exception e) {
                last = e;
                if (!isRetryableLeaderError(e) || attempt == MAX_RETRY_ATTEMPTS) break;
                try {
                    Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Nebula mutation interrupted while retrying", interrupted);
                }
            }
        }
        throw new IllegalStateException("Nebula mutation failed: "
            + (last == null ? "unknown error" : last.getMessage()), last);
    }

    /**
     * 识别 Nebula 集群在 leader 选举和分片注册窗口内返回的可恢复错误。
     */
    private boolean isRetryableLeaderError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = String.valueOf(current.getMessage()).toLowerCase();
            if (message.contains("not the leader") || message.contains("leader changed")
                || message.contains("raft leader") || message.contains("try again later")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
     * 将 VID 编译为 nGQL 字符串字面量。Nebula 3.x 不允许在 VID 位置使用参数占位符。
     */
    private static String literal(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
