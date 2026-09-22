package com.agentsflex.graph;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphWriteResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖变更批次和在线导入请求的顺序、默认值及不可变性。
 */
public class GraphMutationAndImportTest {
    @Test
    public void mutationShouldKeepDeletesAndUpsertsInSeparateReadOnlyCollections() {
        GraphNode alice = GraphNode.builder("alice", "Person").build();
        GraphEdge knows = GraphEdge.builder("alice", "KNOWS", "bob").build();
        GraphEdgeKey oldEdge = new GraphEdgeKey("old", "KNOWS", "bob", 0);

        GraphMutation mutation = GraphMutation.builder()
            .upsertNode(alice)
            .upsertNodes(Collections.singletonList(alice))
            .upsertEdge(knows)
            .upsertEdges(Collections.singletonList(knows))
            .deleteNode("old")
            .deleteEdge(oldEdge)
            .detachDeletedNodes(false)
            .build();

        assertEquals(2, mutation.getNodes().size());
        assertEquals(2, mutation.getEdges().size());
        assertTrue(mutation.getDeleteNodeIds().contains("old"));
        assertTrue(mutation.getDeleteEdgeKeys().contains(oldEdge));
        assertFalse(mutation.isDetachDeletedNodes());
        assertFalse(mutation.isEmpty());
        assertUnmodifiable(mutation.getNodes());
        assertUnmodifiable(mutation.getDeleteNodeIds());
    }

    @Test
    public void emptyMutationShouldBeExplicitlyDetectable() {
        assertTrue(GraphMutation.builder().build().isEmpty());
        assertEquals(0, GraphMutation.builder().build().getNodes().size());
    }

    @Test
    public void importRequestShouldUseSafeDefaultsAndPreserveIterables() {
        Iterable<GraphNode> nodes = Arrays.asList(GraphNode.builder("a", "Person").build());
        Iterable<GraphEdge> edges = Arrays.asList(GraphEdge.builder("a", "KNOWS", "b").build());
        GraphImportRequest request = GraphImportRequest.builder()
            .nodes(nodes)
            .edges(edges)
            .batchSize(2)
            .stopOnError(false)
            .build();

        assertTrue(request.getNodes() == nodes);
        assertTrue(request.getEdges() == edges);
        assertEquals(2, request.getBatchSize());
        assertFalse(request.isStopOnError());

        GraphImportRequest defaults = GraphImportRequest.builder().nodes(null).edges(null).build();
        assertEquals(0, count(defaults.getNodes()));
        assertEquals(0, count(defaults.getEdges()));
        assertEquals(500, defaults.getBatchSize());
        assertTrue(defaults.isStopOnError());
    }

    @Test(expected = IllegalArgumentException.class)
    public void importRequestShouldRejectNonPositiveBatchSize() {
        GraphImportRequest.builder().batchSize(0);
    }

    @Test
    public void importReportShouldAggregateSuccessfulBatchesAndErrors() {
        GraphImportReport report = new GraphImportReport();
        report.add(GraphWriteResult.success(3, 2));
        report.add(GraphWriteResult.success(1, 4));
        report.add(GraphWriteResult.failure("invalid edge", null));

        assertEquals(4, report.getNodesImported());
        assertEquals(6, report.getEdgesImported());
        assertEquals(2, report.getBatchesCompleted());
        assertEquals(Collections.singletonList("invalid edge"), report.getErrors());
        assertFalse(report.isSuccess());
        assertUnmodifiable(report.getErrors());
    }

    private static int count(Iterable<?> values) {
        int count = 0;
        for (Object ignored : values) count++;
        return count;
    }

    private static void assertUnmodifiable(Object collection) {
        try {
            if (collection instanceof java.util.Set) {
                ((java.util.Set<?>) collection).clear();
            } else if (collection instanceof java.util.List) {
                ((java.util.List<?>) collection).clear();
            } else {
                throw new AssertionError("unsupported collection type");
            }
            throw new AssertionError("collection should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // 变更批次和报告均提供只读结果。
        }
    }
}
