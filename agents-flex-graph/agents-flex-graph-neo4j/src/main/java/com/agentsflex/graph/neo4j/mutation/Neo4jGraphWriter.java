package com.agentsflex.graph.neo4j.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.neo4j.Neo4jGraphStore;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 将统一节点、边变更编译为 Neo4j MERGE/DELETE Cypher。
 */
public final class Neo4jGraphWriter implements GraphWriter {
    /**
     * 非事务写入使用的驱动。
     */
    private final org.neo4j.driver.Driver driver;
    /**
     * 默认数据库配置。
     */
    private final Neo4jGraphStoreConfig config;
    /**
     * 事务或测试注入的运行器。
     */
    private final QueryRunner runner;

    /**
     * 创建绑定驱动的写入器。
     */
    public Neo4jGraphWriter(org.neo4j.driver.Driver driver, Neo4jGraphStoreConfig config) {
        this.driver = driver;
        this.config = config;
        this.runner = null;
    }

    public Neo4jGraphWriter(QueryRunner runner, Neo4jGraphStoreConfig config) {
        this.driver = null;
        this.config = config;
        this.runner = runner;
    }

    /**
     * 执行删除和 upsert；独立调用自动开启并提交事务。
     */
    @Override
    public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
        if (mutation == null || mutation.isEmpty()) return GraphWriteResult.success(0, 0);
        if (runner != null) {
            try {
                return write(runner, mutation);
            } catch (RuntimeException e) {
                return GraphWriteResult.failure(GraphErrorCode.WRITE_FAILED,
                    "Neo4j mutation failed: " + e.getMessage(), e);
            }
        }
        String database = options == null ? config.getDefaultSpace() : options.getSpaceOrDefault(config.getDefaultSpace());
        try (Session session = driver.session(org.neo4j.driver.SessionConfig.forDatabase(database));
             Transaction tx = session.beginTransaction(Neo4jGraphStore.transactionConfig(options))) {
            GraphWriteResult result = write(tx, mutation);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            return GraphWriteResult.failure(GraphErrorCode.WRITE_FAILED,
                "Neo4j mutation failed: " + e.getMessage(), e);
        }
    }

    /**
     * 按约定顺序向目标运行器发送 Cypher。
     */
    private GraphWriteResult write(QueryRunner target, GraphMutation mutation) {
        long nodes = 0, edges = 0;
        for (String id : mutation.getDeleteNodeIds()) {
            target.run("MATCH (n {__agentsflex_id: $id}) "
                + (mutation.isDetachDeletedNodes() ? "DETACH " : "") + "DELETE n", map("id", id));
            nodes++;
        }
        for (com.agentsflex.graph.data.GraphEdgeKey key : mutation.getDeleteEdgeKeys()) {
            target.run("MATCH (a {__agentsflex_id: $source})-[r:" + key.getType()
                    + "]->(b {__agentsflex_id: $target}) WHERE r.__agentsflex_rank = $rank DELETE r",
                map("source", key.getSourceId(), "target", key.getTargetId(), "rank", key.getRank()));
            edges++;
        }
        for (GraphNode node : mutation.getNodes()) {
            List<String> labels = node.getLabelList();
            StringBuilder additionalLabels = new StringBuilder();
            for (int i = 1; i < labels.size(); i++) additionalLabels.append(":").append(labels.get(i));
            String statement = "MERGE (n:" + labels.get(0) + " {__agentsflex_id: $id})"
                + (additionalLabels.length() == 0 ? "" : " SET n" + additionalLabels)
                + " SET n += $props";
            target.run(statement, map("id", node.getId(), "props", new LinkedHashMap<>(node.getProperties())));
            nodes++;
        }
        for (GraphEdge edge : mutation.getEdges()) {
            String statement = "MATCH (a {__agentsflex_id: $source}), (b {__agentsflex_id: $target}) "
                + "MERGE (a)-[r:" + edge.getType() + " {__agentsflex_rank: $rank}]->(b) SET r += $props";
            target.run(statement, map("source", edge.getSourceId(), "target", edge.getTargetId(),
                "rank", edge.getRank(), "props", new LinkedHashMap<>(edge.getProperties())));
            edges++;
        }
        return GraphWriteResult.success(nodes, edges);
    }

    /**
     * 先分批导入节点，再分批导入边。
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
     * 合并批次结果，并按请求策略决定是否抛出异常。
     */
    private void add(GraphImportReport report, GraphWriteResult result, boolean stopOnError) {
        report.add(result);
        if (stopOnError && !result.isSuccess()) throw new IllegalStateException(result.getMessage(), result.getError());
    }

    /**
     * 将交替的键值参数转换为驱动参数映射。
     */
    private static LinkedHashMap<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
/**
 * 创建绑定 QueryRunner 的写入器。
 */
