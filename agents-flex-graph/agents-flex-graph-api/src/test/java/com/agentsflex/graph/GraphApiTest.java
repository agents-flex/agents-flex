package com.agentsflex.graph;

import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.query.TraversalQuery;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphApiTest {
    @Test
    public void shouldValidateTraversalAliasesAndBounds() {
        TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("person", "Person"))
            .traverse(TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT),
                TraversalQuery.NodePattern.node("friend", "Person"))
            .where(GraphFilter.and(
                GraphFilter.eq("person", "tenant", "tenant-a"),
                GraphFilter.in("friend", "status", Arrays.asList("active", "pending"))))
            .select(TraversalQuery.Projection.property("friend", "name", "name"))
            .limit(20)
            .build();

        assertEquals("friend", query.getProjections().get(0).getAlias());
        assertEquals(20, query.getLimit());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnknownFilterAlias() {
        TraversalQuery.from(TraversalQuery.NodePattern.anyNode("person"))
            .where(GraphFilter.eq("missing", "name", "x"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsafeIdentifier() {
        GraphNode.builder("id", "Person`).RETURN n").build();
    }

    @Test
    public void shouldReportImportFailures() {
        GraphImportReport report = new GraphImportReport();
        report.add(GraphWriteResult.success(2, 1));
        report.add(GraphWriteResult.failure("bad row", new IllegalArgumentException("bad")));
        assertEquals(2, report.getNodesImported());
        assertEquals(1, report.getEdgesImported());
        assertFalse(report.isSuccess());
        assertTrue(report.getErrors().contains("bad row"));
    }

    @Test
    public void shouldBuildStableEdgeIdentity() {
        GraphEdgeKey key = new GraphEdgeKey("a", "KNOWS", "b", 0);
        assertEquals(key, new GraphEdgeKey("a", "KNOWS", "b", 0));
        assertEquals("a\u001fKNOWS\u001fb\u001f0", key.portableId());
    }
}
