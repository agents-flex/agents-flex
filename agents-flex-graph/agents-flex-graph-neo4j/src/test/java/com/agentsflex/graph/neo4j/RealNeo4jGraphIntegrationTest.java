package com.agentsflex.graph.neo4j;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.importing.GraphImportStatus;
import com.agentsflex.graph.importing.GraphImportTask;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphRecord;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.query.GraphQueryKind;
import com.agentsflex.graph.query.GraphExplainResult;
import com.agentsflex.graph.query.GraphPageRequest;
import com.agentsflex.graph.query.GraphPageResult;
import com.agentsflex.graph.query.GraphResultCursor;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;
import com.agentsflex.graph.transaction.GraphTransaction;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

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

    /**
     * 连接目标不可用时 health 必须返回 DOWN，而不是把探活异常泄露给调用方。
     */
    @Test
    public void shouldReportDownForUnavailableNeo4jEndpoint() {
        Neo4jGraphStore unavailable = new Neo4jGraphStore(new Neo4jGraphStoreConfig()
            .setUri("bolt://127.0.0.1:1").setUsername("neo4j").setPassword("invalid"));
        try {
            assertFalse(unavailable.health().isUp());
        } finally {
            unavailable.close();
            unavailable.close();
        }
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
            assertTrue(store.health().isUp());
            assertTrue(manager.spaceExists("neo4j"));
            assertTrue(manager.listSpaces().contains("neo4j"));
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

    /**
     * 验证真实 Neo4j 数据库的创建、幂等创建、列表可见性和删除。
     */
    @Test
    public void shouldCreateAndDropRealNeo4jDatabase() throws InterruptedException {
        String database = "itdb_" + System.currentTimeMillis();
        GraphManager manager = store.manager();
        try {
            try {
                manager.createSpace(GraphSpaceDefinition.builder(database).partitionCount(1).replicaFactor(1).build(),
                    GraphManager.CreateMode.IF_ABSENT);
            } catch (UnsupportedGraphFeatureException communityEdition) {
                // 当前 Docker 使用 Community Edition；验证 SDK 能明确报告版本能力限制。
                assertTrue(communityEdition.getMessage().contains("Enterprise"));
                return;
            }
            waitForDatabase(manager, database, true);
            assertTrue(manager.listSpaces().contains(database));

            // IF_ABSENT 重复执行必须成功。
            manager.createSpace(GraphSpaceDefinition.builder(database).partitionCount(1).replicaFactor(1).build(),
                GraphManager.CreateMode.IF_ABSENT);
            assertTrue(manager.spaceExists(database));
        } finally {
            if (manager.spaceExists(database)) {
                manager.dropSpace(database);
                waitForDatabase(manager, database, false);
            }
            store.close();
        }
    }

    private static void waitForDatabase(GraphManager manager, String database, boolean expected)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (manager.spaceExists(database) == expected) return;
            Thread.sleep(500L);
        }
        throw new AssertionError("Neo4j database readiness mismatch for " + database + ": expected " + expected);
    }

    /**
     * 验证真实 Neo4j 上除主流程之外的 CRUD、分页、路径、聚合、导入和失败语义。
     */
    @Test
    public void shouldExerciseCrudPagingPathAggregateImportAndReadOnlyProtection() {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String label = "ItCrud" + suffix;
        final String edgeType = "ItFollows" + suffix;
        GraphManager manager = store.manager();
        try {
            GraphSchema schema = GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(label,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                    new GraphSchema.Property("score", GraphSchema.PropertyType.INT64, false),
                    new GraphSchema.Property("active", GraphSchema.PropertyType.BOOLEAN, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType,
                    new GraphSchema.Property("weight", GraphSchema.PropertyType.INT64, false)))
                .index(new GraphSchema.Index("it_crud_name_" + suffix, GraphSchema.IndexTarget.NODE,
                    label, Collections.singletonList("name"), false))
                .build();

            // ADDITIVE Schema 应可重复应用，第二次不应因为对象已存在而失败。
            manager.applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);
            manager.applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);

            GraphNode alice = person("crud-alice-" + suffix, label, "Alice", 30, true);
            GraphNode bob = person("crud-bob-" + suffix, label, "Bob", 20, true);
            GraphNode carol = person("crud-carol-" + suffix, label, "Carol", 10, false);
            GraphNode dave = person("crud-dave-" + suffix, label, "Dave", 5, true);
            GraphEdge aliceBob = GraphEdge.builder(alice.getId(), edgeType, bob.getId()).property("weight", 2L).build();
            GraphEdge bobCarol = GraphEdge.builder(bob.getId(), edgeType, carol.getId()).property("weight", 3L).build();
            GraphEdge aliceDave = GraphEdge.builder(alice.getId(), edgeType, dave.getId()).property("weight", 4L).build();
            GraphWriteResult seed = store.writer().mutate(GraphMutation.builder()
                .upsertNodes(Arrays.asList(alice, bob, carol, dave))
                .upsertEdges(Arrays.asList(aliceBob, bobCarol, aliceDave)).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(seed.getMessage(), seed.isSuccess());
            assertEquals(4L, seed.getNodesAffected());
            assertEquals(3L, seed.getEdgesAffected());

            // 真实验证公共字符串 DSL 的完整入口：参数不会拼接进 Cypher，而是由编译器绑定。
            java.util.Map<String, Object> names = new java.util.HashMap<>();
            names.put("names", Arrays.asList("Alice", "Bob"));
            GraphResult stringRanked = store.query().execute(
                "MATCH (n:" + label + ") WHERE n.name IN :names "
                    + "RETURN n.name AS name, n.score AS score ORDER BY n.score DESC LIMIT 10",
                names, GraphOptions.ofSpace("neo4j"));
            assertEquals(2, stringRanked.getRecords().size());
            assertEquals("Alice", stringRanked.getRecords().get(0).get("name"));

            java.util.Map<String, Object> edgeParameters = new java.util.HashMap<>();
            edgeParameters.put("weight", 2L);
            edgeParameters.put("fragment", "Ali");
            GraphResult stringPath = store.query().execute(
                "MATCH (a:" + label + ")-[r:" + edgeType + " {weight: :weight}]->(b:" + label + ") "
                    + "WHERE a.name CONTAINS :fragment RETURN PATH AS route, b.name AS name",
                edgeParameters, GraphOptions.ofSpace("neo4j"));
            assertEquals(1, stringPath.getRecords().size());
            assertNotNull(stringPath.getRecords().get(0).get("route"));
            assertEquals("Bob", stringPath.getRecords().get(0).get("name"));

            java.util.Map<String, Object> minimum = Collections.<String, Object>singletonMap("minimum", 20L);
            GraphResult stringUnion = store.query().execute(
                "MATCH (n:" + label + ") WHERE n.score >= :minimum RETURN n.name AS name "
                    + "UNION ALL MATCH (n:" + label + ") WHERE n.score < :minimum RETURN n.name AS name",
                minimum, GraphOptions.ofSpace("neo4j"));
            assertEquals(4, stringUnion.getRecords().size());

            GraphResult stringOptional = store.query().execute(
                "OPTIONAL MATCH (n:" + label + ")-[r:" + edgeType + "]->(m:" + label + ") "
                    + "RETURN n.name AS name, m.name AS friend ORDER BY n.name ASC LIMIT 10",
                Collections.<String, Object>emptyMap(), GraphOptions.ofSpace("neo4j"));
            assertEquals(3, stringOptional.getRecords().size());

            GraphResult stringGrouped = store.query().execute(
                "MATCH (n:" + label + ") RETURN n.active AS active, COUNT(n) AS total "
                    + "GROUP BY n.active ORDER BY n.active ASC LIMIT 10",
                Collections.<String, Object>emptyMap(), GraphOptions.ofSpace("neo4j"));
            assertEquals(2, stringGrouped.getRecords().size());
            GraphPageResult stringPage = store.query().executePage(
                "MATCH (n:" + label + ") RETURN n.name AS name ORDER BY n.name ASC LIMIT 10",
                Collections.<String, Object>emptyMap(), GraphPageRequest.of(0, 2), GraphOptions.ofSpace("neo4j"));
            assertEquals(2, stringPage.getResult().getRecords().size());
            assertTrue(stringPage.hasNext());
            GraphExplainResult stringExplain = store.query().explain(
                "MATCH (n:" + label + ") WHERE n.score >= :minimum RETURN n.name AS name",
                minimum, GraphOptions.ofSpace("neo4j"));
            assertFalse(stringExplain.getPlanText().trim().isEmpty());

            // 部分属性更新不得影响未涉及的属性。
            GraphWriteResult update = store.writer().mutate(GraphMutation.builder()
                    .upsertNode(person(alice.getId(), label, "Alice Updated", 42, true)).build(),
                GraphOptions.ofSpace("neo4j"));
            assertTrue(update.isSuccess());
            GraphResult updated = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + label + " {__agentsflex_id: $id}) RETURN n.name AS name, n.score AS score, n.active AS active",
                Collections.<String, Object>singletonMap("id", alice.getId())), GraphOptions.ofSpace("neo4j"));
            assertEquals("Alice Updated", updated.getRecords().get(0).get("name"));
            assertEquals(42L, ((Number) updated.getRecords().get(0).get("score")).longValue());
            assertEquals(true, updated.getRecords().get(0).get("active"));

            TraversalQuery ranked = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .where(GraphFilter.gt("n", "score", 10L))
                .select(TraversalQuery.Projection.property("n", "name", "name"),
                    TraversalQuery.Projection.property("n", "score", "score"))
                .orderBy(new TraversalQuery.Sort("n", "score", TraversalQuery.SortDirection.DESC))
                .limit(2).build();
            GraphResult rankedResult = store.query().execute(ranked, GraphOptions.ofSpace("neo4j"));
            assertEquals(2, rankedResult.getRecords().size());
            assertEquals("Alice Updated", rankedResult.getRecords().get(0).get("name"));

            TraversalQuery path = TraversalQuery.from(TraversalQuery.NodePattern.node("start", label))
                .traverse(TraversalQuery.EdgePattern.edge("e1", edgeType, TraversalQuery.Direction.OUT),
                    TraversalQuery.NodePattern.node("middle", label))
                .traverse(TraversalQuery.EdgePattern.edge("e2", edgeType, TraversalQuery.Direction.OUT),
                    TraversalQuery.NodePattern.node("end", label))
                .where(GraphFilter.eq("start", "name", "Alice Updated"))
                .select(TraversalQuery.Projection.path("path")).build();
            GraphResult pathResult = store.query().execute(path, GraphOptions.ofSpace("neo4j"));
            assertEquals(1, pathResult.getRecords().size());
            assertNotNull(pathResult.getRecords().get(0).get("path"));

            TraversalQuery count = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .select(TraversalQuery.Projection.count("n", "total")).build();
            GraphResult countResult = store.query().execute(count, GraphOptions.ofSpace("neo4j"));
            assertEquals(4L, ((Number) countResult.getRecords().get(0).get("total")).longValue());

            GraphExplainResult explain = store.query().explain(ranked, GraphOptions.ofSpace("neo4j"));
            assertEquals("neo4j", explain.getBackend());
            assertFalse(explain.getPlanText().trim().isEmpty());

            GraphImportReport imported = store.writer().importData(GraphImportRequest.builder()
                .nodes(Arrays.asList(person("crud-import-1-" + suffix, label, "Imported 1", 1, true),
                    person("crud-import-2-" + suffix, label, "Imported 2", 2, true)))
                .edges(Collections.singletonList(GraphEdge.builder("crud-import-1-" + suffix, edgeType,
                    "crud-import-2-" + suffix).property("weight", 1L).build()))
                .batchSize(1).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(imported.isSuccess());
            assertEquals(2L, imported.getNodesImported());
            assertEquals(1L, imported.getEdgesImported());
            assertEquals(3, imported.getBatchesCompleted());

            GraphWriteResult deleteEdge = store.writer().mutate(GraphMutation.builder()
                .deleteEdge(aliceBob.getKey()).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(deleteEdge.isSuccess());
            GraphWriteResult deleteNode = store.writer().mutate(GraphMutation.builder()
                .deleteNode(dave.getId()).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(deleteNode.isSuccess());
            GraphResult remaining = store.query().execute(NativeGraphQuery.of(
                    "MATCH (n:" + label + ") RETURN count(n) AS total", Collections.<String, Object>emptyMap()),
                GraphOptions.ofSpace("neo4j"));
            assertEquals(5L, ((Number) remaining.getRecords().get(0).get("total")).longValue());

            try {
                store.query().execute(NativeGraphQuery.of(
                        "CREATE (n:" + label + " {__agentsflex_id: 'should-fail'})",
                        Collections.<String, Object>emptyMap(), GraphQueryKind.WRITE),
                    GraphOptions.builder().space("neo4j").readOnly(true).build());
                fail("read-only options must reject native write queries");
            } catch (GraphException expected) {
                assertEquals(com.agentsflex.graph.error.GraphErrorCode.INVALID_ARGUMENT, expected.getCode());
            }
        } finally {
            try {
                store.query().execute(NativeGraphQuery.of(
                    "MATCH (n:" + label + ") DETACH DELETE n", Collections.<String, Object>emptyMap(),
                    GraphQueryKind.WRITE), GraphOptions.ofSpace("neo4j"));
            } finally {
                store.close();
            }
        }
    }

    /**
     * 验证统一查询的 UNION、分页游标、maxRecords 截断、空结果，以及真实异步导入生命周期。
     */
    @Test
    public void shouldExercisePortableUnionPagingCursorAndAsyncImportAgainstRealNeo4j() throws Exception {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String label = "ItAdvanced" + suffix;
        final String edgeType = "ItAdvancedEdge" + suffix;
        GraphManager manager = store.manager();
        try {
            manager.applySchema("neo4j", GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(label,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                    new GraphSchema.Property("score", GraphSchema.PropertyType.INT64, false),
                    new GraphSchema.Property("ratio", GraphSchema.PropertyType.DOUBLE, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType))
                .build(), GraphManager.SchemaMode.ADDITIVE);
            GraphNode low = GraphNode.builder("advanced-low-" + suffix, label)
                .property("name", "低值").property("score", 1L).property("ratio", 0.25D).build();
            GraphNode high = GraphNode.builder("advanced-high-" + suffix, label)
                .property("name", "高值").property("score", 9L).property("ratio", 0.75D).build();
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertNodes(Arrays.asList(low, high)).build(),
                GraphOptions.ofSpace("neo4j")).isSuccess());
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertEdges(Arrays.asList(
                    GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(1).build(),
                    GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(2).build(),
                    GraphEdge.builder(high.getId(), edgeType, high.getId()).rank(3).build())).build(),
                GraphOptions.ofSpace("neo4j")).isSuccess());
            GraphResult rankedEdges = store.query().execute(NativeGraphQuery.of(
                "MATCH ()-[r:" + edgeType + "]->() RETURN count(r) AS total",
                Collections.<String, Object>emptyMap()), GraphOptions.ofSpace("neo4j"));
            assertEquals(3L, ((Number) rankedEdges.getRecords().get(0).get("total")).longValue());
            assertTrue(store.writer().mutate(GraphMutation.builder().deleteEdge(
                    new com.agentsflex.graph.data.GraphEdgeKey(low.getId(), edgeType, high.getId(), 2)).build(),
                GraphOptions.ofSpace("neo4j")).isSuccess());

            TraversalQuery lowQuery = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .where(GraphFilter.le("n", "score", 1L))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build();
            TraversalQuery highQuery = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .where(GraphFilter.ge("n", "score", 9L))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build();
            GraphResult union = store.query().execute(GraphUnionQuery.union(lowQuery, highQuery),
                GraphOptions.ofSpace("neo4j"));
            assertEquals(2, union.getRecords().size());
            GraphResult typed = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + label + ") RETURN n.name AS name, n.ratio AS ratio",
                Collections.<String, Object>emptyMap()), GraphOptions.ofSpace("neo4j"));
            assertEquals(2, typed.getRecords().size());
            assertTrue(((Number) typed.getRecords().get(0).get("ratio")).doubleValue() > 0D);

            TraversalQuery all = TraversalQuery.from(TraversalQuery.NodePattern.node("n", label))
                .select(TraversalQuery.Projection.property("n", "name", "name"))
                .orderBy(new TraversalQuery.Sort("n", "name", TraversalQuery.SortDirection.ASC)).build();
            GraphPageResult first = store.query().executePage(all, GraphPageRequest.of(0, 1), GraphOptions.ofSpace("neo4j"));
            assertEquals(1, first.getResult().getRecords().size());
            assertTrue(first.hasNext());
            assertEquals(1, first.getResult().getMetadata().getRecordCount());
            GraphPageResult second = store.query().executePage(all,
                GraphPageRequest.after(first.getNextCursor(), 1), GraphOptions.ofSpace("neo4j"));
            assertEquals(1, second.getResult().getRecords().size());
            assertFalse(second.hasNext());

            GraphResult truncated = store.query().execute(all, GraphOptions.builder().space("neo4j")
                .maxRecords(1).build());
            assertEquals(1, truncated.getRecords().size());
            assertTrue(truncated.getMetadata().isTruncated());
            GraphResult empty = store.query().execute(TraversalQuery.from(
                    TraversalQuery.NodePattern.node("n", label)).where(GraphFilter.eq("n", "name", "不存在"))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(empty.getRecords().isEmpty());
            assertFalse(empty.getMetadata().isTruncated());

            try {
                store.query().execute(NativeGraphQuery.of("MATCH (n) RETURN n", Collections.<String, Object>emptyMap()),
                    GraphOptions.ofSpace("missing_neo4j_space_" + suffix));
                fail("an unknown Neo4j database must fail explicitly");
            } catch (GraphException expected) {
                assertNotNull(expected.getCode());
            }
            try {
                store.query().execute(NativeGraphQuery.of("MATCH (", Collections.<String, Object>emptyMap()),
                    GraphOptions.ofSpace("neo4j"));
                fail("invalid Cypher must be mapped to GraphException");
            } catch (GraphException expected) {
                assertEquals(com.agentsflex.graph.error.GraphErrorCode.QUERY_FAILED, expected.getCode());
            }

            GraphResultCursor cursor = store.query().executeCursor(all, GraphOptions.ofSpace("neo4j"));
            try {
                int count = 0;
                while (cursor.hasNext()) {
                    assertNotNull(cursor.next().get("name"));
                    count++;
                }
                assertEquals(2, count);
            } finally {
                cursor.close();
            }

            GraphImportTask submitted = store.imports().submit(GraphImportRequest.builder()
                .nodes(Arrays.asList(GraphNode.builder("advanced-import-" + suffix, label)
                    .property("name", "异步").property("score", 3L).build()))
                .batchSize(1).build(), GraphOptions.ofSpace("neo4j"));
            GraphImportTask completed = awaitImport(store, submitted.getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertEquals(1L, completed.getNodesProcessed());
            assertTrue(store.imports().list().stream().anyMatch(task -> submitted.getId().equals(task.getId())));
            assertTrue(store.imports().remove(submitted.getId()));
            assertFalse(store.imports().remove(submitted.getId()));

            List<GraphNode> bulkNodes = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                bulkNodes.add(GraphNode.builder("advanced-bulk-" + suffix + "-" + i, label)
                    .property("name", "批量-" + i).property("score", (long) i)
                    .property("ratio", i / 100.0D).build());
            }
            GraphImportReport bulk = store.writer().importData(GraphImportRequest.builder()
                .nodes(bulkNodes).batchSize(32).build(), GraphOptions.ofSpace("neo4j"));
            assertTrue(bulk.isSuccess());
            assertEquals(128L, bulk.getNodesImported());
            assertEquals(4, bulk.getBatchesCompleted());
            GraphResult bulkCount = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + label + ") WHERE n.name STARTS WITH $prefix RETURN count(n) AS total",
                Collections.<String, Object>singletonMap("prefix", "批量-")), GraphOptions.ofSpace("neo4j"));
            assertEquals(128L, ((Number) bulkCount.getRecords().get(0).get("total")).longValue());

            ExecutorService concurrent = Executors.newFixedThreadPool(4);
            try {
                List<Future<GraphWriteResult>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int index = i;
                    writes.add(concurrent.submit(() -> store.writer().mutate(GraphMutation.builder()
                            .upsertNode(GraphNode.builder("advanced-concurrent-" + suffix + "-" + index, label)
                                .property("name", "并发-" + index).property("score", (long) index)
                                .property("ratio", index / 10.0D).build()).build(),
                        GraphOptions.ofSpace("neo4j"))));
                }
                for (Future<GraphWriteResult> write : writes) assertTrue(write.get(20, TimeUnit.SECONDS).isSuccess());
                GraphResult concurrentCount = store.query().execute(NativeGraphQuery.of(
                    "MATCH (n:" + label + ") WHERE n.name STARTS WITH $prefix RETURN count(n) AS total",
                    Collections.<String, Object>singletonMap("prefix", "并发-")), GraphOptions.ofSpace("neo4j"));
                assertEquals(8L, ((Number) concurrentCount.getRecords().get(0).get("total")).longValue());
            } finally {
                concurrent.shutdownNow();
            }

            // 同一业务 ID 的并发 upsert 必须收敛为一个节点，不能产生重复实体。
            ExecutorService sameKeyExecutor = Executors.newFixedThreadPool(8);
            try {
                List<Future<GraphWriteResult>> sameNodeWrites = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final int index = i;
                    sameNodeWrites.add(sameKeyExecutor.submit(() -> store.writer().mutate(GraphMutation.builder()
                        .upsertNode(person("advanced-same-key-" + suffix, label, "same-" + index, index, true))
                        .build(), GraphOptions.ofSpace("neo4j"))));
                }
                for (Future<GraphWriteResult> write : sameNodeWrites)
                    assertTrue(write.get(20, TimeUnit.SECONDS).isSuccess());
                GraphResult sameNodeCount = store.query().execute(NativeGraphQuery.of(
                        "MATCH (n:" + label + ") WHERE n.__agentsflex_id = $id RETURN count(n) AS total, collect(n.name) AS names",
                        Collections.<String, Object>singletonMap("id", "advanced-same-key-" + suffix)),
                    GraphOptions.ofSpace("neo4j"));
                assertEquals(1L, ((Number) sameNodeCount.getRecords().get(0).get("total")).longValue());
                assertEquals(1, ((List<?>) sameNodeCount.getRecords().get(0).get("names")).size());

                List<Future<GraphWriteResult>> sameEdgeWrites = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    sameEdgeWrites.add(sameKeyExecutor.submit(() -> store.writer().mutate(GraphMutation.builder()
                        .upsertEdge(GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(99)
                            .property("weight", 99L).build()).build(), GraphOptions.ofSpace("neo4j"))));
                }
                for (Future<GraphWriteResult> write : sameEdgeWrites)
                    assertTrue(write.get(20, TimeUnit.SECONDS).isSuccess());
                GraphResult sameEdgeCount = store.query().execute(NativeGraphQuery.of(
                    "MATCH (a:" + label + ")-[r:" + edgeType + "]->(b:" + label + ") "
                        + "WHERE a.__agentsflex_id = $from AND b.__agentsflex_id = $to AND r.__agentsflex_rank = 99 "
                        + "RETURN count(r) AS total",
                    new java.util.HashMap<String, Object>() {{
                        put("from", low.getId());
                        put("to", high.getId());
                    }}), GraphOptions.ofSpace("neo4j"));
                assertEquals(1L, ((Number) sameEdgeCount.getRecords().get(0).get("total")).longValue());
            } finally {
                sameKeyExecutor.shutdownNow();
            }

            String txId = "advanced-tx-" + suffix;
            GraphTransaction transaction = store.transactions().begin(GraphOptions.ofSpace("neo4j"));
            transaction.writer().mutate(GraphMutation.builder().upsertNode(GraphNode.builder(txId, label)
                    .property("name", "事务提交").property("score", 7L).property("ratio", 0.7D).build()).build(),
                GraphOptions.ofSpace("neo4j"));
            GraphResult beforeCommit = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + label + " {__agentsflex_id: $id}) RETURN count(n) AS total",
                Collections.<String, Object>singletonMap("id", txId)), GraphOptions.ofSpace("neo4j"));
            assertEquals(0L, ((Number) beforeCommit.getRecords().get(0).get("total")).longValue());
            transaction.commit();
            GraphResult afterCommit = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + label + " {__agentsflex_id: $id}) RETURN count(n) AS total",
                Collections.<String, Object>singletonMap("id", txId)), GraphOptions.ofSpace("neo4j"));
            assertEquals(1L, ((Number) afterCommit.getRecords().get(0).get("total")).longValue());
        } finally {
            try {
                store.query().execute(NativeGraphQuery.of("MATCH (n:" + label + ") DETACH DELETE n",
                    Collections.<String, Object>emptyMap(), GraphQueryKind.WRITE), GraphOptions.ofSpace("neo4j"));
            } finally {
                store.close();
            }
        }
    }

    private static GraphImportTask awaitImport(Neo4jGraphStore store, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            GraphImportTask task = store.imports().get(id);
            if (task != null && task.isTerminal()) return task;
            Thread.sleep(100L);
        }
        fail("Timed out waiting for import task " + id);
        return null;
    }

    private static GraphNode person(String id, String label, String name, long score, boolean active) {
        return GraphNode.builder(id, label).property("name", name).property("score", score)
            .property("active", active).build();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
