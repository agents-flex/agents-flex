package com.agentsflex.graph.neo4j;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.transaction.GraphTransaction;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 使用 Docker 中真实 Neo4j 验证空间、Schema、写入、查询和事务。
 *
 * <p>该测试只在 {@code GRAPH_INTEGRATION=true} 时执行，连接参数可通过环境变量覆盖：</p>
 * <ul>
 *   <li>{@code GRAPH_NEO4J_URI}，默认 {@code bolt://localhost:17687}</li>
 *   <li>{@code GRAPH_NEO4J_PASSWORD}，默认 {@code agentsflex}</li>
 * </ul>
 */
public class RealNeo4jGraphIntegrationTest {
    private Neo4jGraphStore store;

    @Before
    public void setUp() {
        Assume.assumeTrue("set GRAPH_INTEGRATION=true to run Docker integration tests",
            "true".equalsIgnoreCase(System.getenv("GRAPH_INTEGRATION")));
        Neo4jGraphStoreConfig config = new Neo4jGraphStoreConfig()
            .setUri(value("GRAPH_NEO4J_URI", "bolt://localhost:17687"))
            .setUsername(value("GRAPH_NEO4J_USER", "neo4j"))
            .setPassword(value("GRAPH_NEO4J_PASSWORD", "agentsflex"))
            .setDefaultSpace("neo4j");
        store = new Neo4jGraphStore(config);
    }

    @Test
    public void shouldCreateSchemaWriteQueryInspectAndUseTransactionAgainstRealNeo4j() {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String label = "ItPerson" + suffix;
        final String edgeType = "ItKnows" + suffix;
        try {
            GraphSchema schema = GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(label,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false)))
                .build();
            GraphManager manager = store.manager();
            assertTrue(manager.spaceExists("neo4j"));
            manager.applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);

            GraphNode alice = GraphNode.builder("it-alice-" + suffix, label).property("name", "Alice").build();
            GraphNode bob = GraphNode.builder("it-bob-" + suffix, label).property("name", "Bob").build();
            GraphEdge edge = GraphEdge.builder(alice.getId(), edgeType, bob.getId()).build();
            GraphWriteResult write = store.writer().mutate(GraphMutation.builder()
                .upsertNodes(Arrays.asList(alice, bob)).upsertEdge(edge).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(write.isSuccess());
            assertEquals(2L, write.getNodesAffected());
            assertEquals(1L, write.getEdgesAffected());

            TraversalQuery query = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .where(GraphFilter.in("n", "name", Arrays.asList("Alice", "Bob")))
                .select(TraversalQuery.Projection.property("n", "name", "name"))
                .build();
            GraphResult result = store.query().execute(query, GraphOptions.ofSpace("neo4j"));
            assertEquals(2, result.getRecords().size());
            assertEquals("Alice", result.getRecords().get(0).get("name"));

            GraphSchemaInspection inspection = manager.inspectSchema("neo4j");
            assertNotNull(inspection.getSchema());
            assertTrue(inspection.getSchema().getNodeTypes().stream()
                .anyMatch(node -> label.equals(node.getLabel())));

            GraphTransaction transaction = store.transactions().begin(GraphOptions.ofSpace("neo4j"));
            try {
                GraphResult txResult = transaction.query().execute(
                    NativeGraphQuery.of("MATCH (n) RETURN count(n) AS total", Collections.<String, Object>emptyMap()),
                    GraphOptions.ofSpace("neo4j"));
                assertEquals(2L, ((Number) txResult.getRecords().get(0).get("total")).longValue());
                transaction.rollback();
            } finally {
                transaction.close();
            }
        } finally {
            try {
                store.query().execute(NativeGraphQuery.of(
                    "MATCH (n) WHERE any(label IN labels(n) WHERE label = $label) DETACH DELETE n",
                    Collections.<String, Object>singletonMap("label", "ItPerson" + suffix),
                    com.agentsflex.graph.query.GraphQueryKind.WRITE), GraphOptions.ofSpace("neo4j"));
            } finally {
                store.close();
            }
        }
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
