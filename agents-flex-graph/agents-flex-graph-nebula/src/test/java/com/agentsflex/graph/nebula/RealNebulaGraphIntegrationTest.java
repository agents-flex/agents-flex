package com.agentsflex.graph.nebula;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 使用 Docker 中真实 Nebula Graph 验证空间、Schema、写入、读取和反查。
 *
 * <p>该测试只在 {@code GRAPH_INTEGRATION=true} 时执行，默认连接 localhost:19670。</p>
 */
public class RealNebulaGraphIntegrationTest {
    private static final String BOOTSTRAP_SPACE = "agents_flex_bootstrap";
    private NebulaGraphStore store;
    private String space;

    @Before
    public void setUp() {
        Assume.assumeTrue("set GRAPH_INTEGRATION=true to run Docker integration tests",
            "true".equalsIgnoreCase(System.getenv("GRAPH_INTEGRATION")));
        space = "agents_flex_it_" + System.currentTimeMillis();
        NebulaGraphStoreConfig config = new NebulaGraphStoreConfig()
            .setHost(value("GRAPH_NEBULA_HOST", "127.0.0.1"))
            .setPort(Integer.parseInt(value("GRAPH_NEBULA_PORT", "19670")))
            .setUsername(value("GRAPH_NEBULA_USER", "root"))
            .setPassword(value("GRAPH_NEBULA_PASSWORD", "nebula"))
            // SessionPool 初始化时必须绑定已存在的空间；业务测试空间在测试体内单独创建。
            .setDefaultSpace(value("GRAPH_NEBULA_BOOTSTRAP_SPACE", BOOTSTRAP_SPACE));
        store = new NebulaGraphStore(config);
    }

    @Test
    public void shouldCreateSchemaWriteQueryAndInspectAgainstRealNebula() throws Exception {
        final String tag = "ItPerson";
        final String edgeType = "ItKnows";
        GraphManager manager = store.manager();
        try {
            manager.createSpace(GraphSpaceDefinition.builder(space).partitionCount(1).replicaFactor(1).build(),
                GraphManager.CreateMode.IF_ABSENT);
            waitForSpace(manager, store, space);

            GraphSchema schema = GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(tag,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType,
                    new GraphSchema.Property("weight", GraphSchema.PropertyType.INT64, false)))
                .build();
            manager.applySchema(space, schema, GraphManager.SchemaMode.ADDITIVE);
            Thread.sleep(1_000L);

            GraphNode alice = GraphNode.builder("it-alice", tag).property("name", "Alice").build();
            GraphNode bob = GraphNode.builder("it-bob", tag).property("name", "Bob").build();
            GraphEdge edge = GraphEdge.builder(alice.getId(), edgeType, bob.getId()).property("weight", 1L).build();
            GraphWriteResult write = store.writer().mutate(GraphMutation.builder()
                .upsertNodes(Arrays.asList(alice, bob)).upsertEdge(edge).build(), GraphOptions.ofSpace(space));
            assertTrue(write.getMessage(), write.isSuccess());
            assertEquals(2L, write.getNodesAffected());
            assertEquals(1L, write.getEdgesAffected());

            GraphResult result = store.query().execute(NativeGraphQuery.of(
                    "MATCH (v:ItPerson) RETURN v", Collections.<String, Object>emptyMap()),
                GraphOptions.ofSpace(space));
            assertEquals(2, result.getRecords().size());
            assertNotNull(result.getSubgraph());
            assertEquals(2, result.getSubgraph().getNodes().size());

            GraphSchemaInspection inspection = manager.inspectSchema(space);
            assertNotNull(inspection.getSchema());
            assertTrue(inspection.getSchema().getNodeTypes().stream()
                .anyMatch(node -> tag.equals(node.getLabel())));
            assertTrue(inspection.getSchema().getEdgeTypes().stream()
                .anyMatch(edgeDefinition -> edgeType.equals(edgeDefinition.getType())));
        } finally {
            try {
                if (manager.spaceExists(space)) manager.dropSpace(space);
            } finally {
                store.close();
            }
        }
    }

    private static void waitForSpace(GraphManager manager, NebulaGraphStore store, String space)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (manager.spaceExists(space)) {
                try {
                    // SHOW SPACES 只代表 MetaD 已记录空间；绑定会话并执行语句才能证明
                    // StorageD 已完成分片注册，目标空间可以真正承载 Schema 和数据操作。
                    com.vesoft.nebula.client.graph.data.ResultSet result = store.pool(space).execute("SHOW TAGS");
                    if (result.isSucceeded()) return;
                } catch (Exception ignored) {
                    // Nebula 在空间创建后的短暂传播窗口内可能仍返回 SpaceNotFound，继续轮询。
                }
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Nebula space was not ready: " + space);
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
