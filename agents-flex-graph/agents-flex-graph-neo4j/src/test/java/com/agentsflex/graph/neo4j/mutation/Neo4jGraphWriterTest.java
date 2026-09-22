package com.agentsflex.graph.neo4j.mutation;

import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import org.junit.Test;
import org.neo4j.driver.QueryRunner;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Neo4j 写入器生成语句的顺序和关键语义。
 */
public class Neo4jGraphWriterTest {
    @Test
    public void shouldMergeByPrimaryLabelAndAddRemainingLabels() {
        List<String> statements = new ArrayList<>();
        Neo4jGraphWriter writer = new Neo4jGraphWriter(runner(statements), new Neo4jGraphStoreConfig());
        GraphNode node = GraphNode.builder("u1", "Person").label("Employee").property("name", "Alice").build();

        GraphWriteResult result = writer.mutate(GraphMutation.builder().upsertNode(node).build(), GraphOptions.DEFAULT);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getNodesAffected());
        assertEquals("MERGE (n:Person {__agentsflex_id: $id}) SET n:Employee SET n += $props",
            statements.get(0));
    }

    @Test
    public void deletesShouldRunBeforeUpsertsAndHonorDetachOption() {
        List<String> statements = new ArrayList<>();
        Neo4jGraphWriter writer = new Neo4jGraphWriter(runner(statements), new Neo4jGraphStoreConfig());
        GraphMutation mutation = GraphMutation.builder()
            .deleteNode("old")
            .detachDeletedNodes(false)
            .upsertEdge(GraphEdge.builder("a", "KNOWS", "b").build())
            .build();

        writer.mutate(mutation, GraphOptions.DEFAULT);

        assertTrue(statements.get(0).endsWith("DELETE n"));
        assertFalse(statements.get(0).contains("DETACH"));
        assertTrue(statements.get(1).contains("MERGE (a)-[r:KNOWS"));
    }

    private static QueryRunner runner(List<String> statements) {
        return (QueryRunner) Proxy.newProxyInstance(QueryRunner.class.getClassLoader(),
            new Class<?>[]{QueryRunner.class}, (proxy, method, args) -> {
                if ("run".equals(method.getName()) && args != null && args.length > 0
                    && args[0] instanceof String) statements.add((String) args[0]);
                return null;
            });
    }
}
