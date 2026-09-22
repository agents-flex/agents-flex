package com.agentsflex.graph.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.GraphOptions;

/**
 * 写入可移植的节点和边，并提供在线导入能力。
 */
public interface GraphWriter {
    /**
     * 执行一批删除和 upsert 变更。
     */
    GraphWriteResult mutate(GraphMutation mutation, GraphOptions options);

    /**
     * 使用默认选项执行变更。
     */
    default GraphWriteResult mutate(GraphMutation mutation) {
        return mutate(mutation, GraphOptions.DEFAULT);
    }

    /**
     * upsert 一个节点。
     */
    default GraphWriteResult upsert(GraphNode node, GraphOptions options) {
        return mutate(GraphMutation.builder().upsertNode(node).build(), options);
    }

    /**
     * 使用默认选项 upsert 一个节点。
     */
    default GraphWriteResult upsert(GraphNode node) {
        return upsert(node, GraphOptions.DEFAULT);
    }

    /**
     * upsert 一条边。
     */
    default GraphWriteResult upsert(GraphEdge edge, GraphOptions options) {
        return mutate(GraphMutation.builder().upsertEdge(edge).build(), options);
    }

    /**
     * 使用默认选项 upsert 一条边。
     */
    default GraphWriteResult upsert(GraphEdge edge) {
        return upsert(edge, GraphOptions.DEFAULT);
    }

    /**
     * 按节点优先、分批策略导入数据。
     */
    GraphImportReport importData(GraphImportRequest request, GraphOptions options);

    /**
     * 使用默认选项导入数据。
     */
    default GraphImportReport importData(GraphImportRequest request) {
        return importData(request, GraphOptions.DEFAULT);
    }
}
