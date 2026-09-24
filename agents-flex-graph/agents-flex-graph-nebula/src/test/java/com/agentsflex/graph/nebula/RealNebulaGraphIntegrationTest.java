package com.agentsflex.graph.nebula;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphException;
import com.agentsflex.graph.UnsupportedGraphFeatureException;
import com.agentsflex.graph.data.GraphEdge;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.error.GraphErrorCode;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.importing.GraphImportStatus;
import com.agentsflex.graph.importing.GraphImportTask;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.query.GraphQueryKind;
import com.agentsflex.graph.query.GraphExplainResult;
import com.agentsflex.graph.query.GraphPageRequest;
import com.agentsflex.graph.query.GraphPageResult;
import com.agentsflex.graph.query.GraphResultCursor;
import com.agentsflex.graph.query.GraphUnionQuery;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.graph.schema.GraphSchemaInspection;

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

    /** 连接目标不可用时 health 必须返回 DOWN，并且 close 可重复调用。 */
    @Test
    public void shouldReportDownForUnavailableNebulaEndpoint() {
        NebulaGraphStore unavailable = new NebulaGraphStore(new NebulaGraphStoreConfig()
            .setHost("127.0.0.1").setPort(1).setUsername("root").setPassword("invalid")
            .setDefaultSpace(BOOTSTRAP_SPACE));
        try {
            assertFalse(unavailable.health().isUp());
        } finally {
            unavailable.close();
            unavailable.close();
        }
    }

    @Test
    public void shouldCreateSchemaWriteQueryAndInspectAgainstRealNebula() throws Exception {
        final String tag = "ItPerson";
        final String edgeType = "ItKnows";
        GraphManager manager = store.manager();
        try {
            assertTrue(store.health().isUp());
            assertTrue(manager.listSpaces().contains(BOOTSTRAP_SPACE));
            try {
                store.transactions();
                fail("Nebula SessionPool must not expose explicit transactions");
            } catch (UnsupportedGraphFeatureException expected) {
                assertTrue(expected.getMessage().contains("transactions"));
            }
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
            // 第二次 ADDITIVE 应真正增加缺失属性，而不是仅因 IF NOT EXISTS 返回成功。
            manager.applySchema(space, GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(tag,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                    new GraphSchema.Property("age", GraphSchema.PropertyType.INT64, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType,
                    new GraphSchema.Property("weight", GraphSchema.PropertyType.INT64, false)))
                .build(), GraphManager.SchemaMode.ADDITIVE);
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
                .anyMatch(node -> tag.equals(node.getLabel()) && node.getProperties().stream()
                    .anyMatch(property -> "age".equals(property.getName()))));
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

    /**
     * 验证真实 Nebula 的 CRUD、批量导入、分页、路径、聚合、Explain 和只读保护。
     */
    @Test
    public void shouldExerciseCrudPagingPathAggregateImportAndReadOnlyProtection() throws Exception {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String tag = "ItCrud" + suffix;
        final String edgeType = "ItFollows" + suffix;
        GraphManager manager = store.manager();
        try {
            manager.createSpace(GraphSpaceDefinition.builder(space).partitionCount(1).replicaFactor(1).build(),
                GraphManager.CreateMode.IF_ABSENT);
            waitForSpace(manager, store, space);
            GraphSchema schema = GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(tag,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                    new GraphSchema.Property("score", GraphSchema.PropertyType.INT64, false),
                    new GraphSchema.Property("active", GraphSchema.PropertyType.BOOLEAN, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType,
                    new GraphSchema.Property("weight", GraphSchema.PropertyType.INT64, false)))
                .index(new GraphSchema.Index("it_crud_score_" + suffix, GraphSchema.IndexTarget.NODE,
                    tag, Collections.singletonList("score"), false))
                .build();
            manager.applySchema(space, schema, GraphManager.SchemaMode.ADDITIVE);
            manager.applySchema(space, schema, GraphManager.SchemaMode.ADDITIVE);
            waitForSchema(store, space, tag, edgeType);

            GraphNode alice = person("nebula-alice-" + suffix, tag, "Alice", 30, true);
            GraphNode bob = person("nebula-bob-" + suffix, tag, "Bob", 20, true);
            GraphNode carol = person("nebula-carol-" + suffix, tag, "Carol", 10, false);
            GraphNode dave = person("nebula-dave-" + suffix, tag, "Dave", 5, true);
            GraphEdge aliceBob = GraphEdge.builder(alice.getId(), edgeType, bob.getId()).property("weight", 2L).build();
            GraphEdge bobCarol = GraphEdge.builder(bob.getId(), edgeType, carol.getId()).property("weight", 3L).build();
            GraphEdge aliceDave = GraphEdge.builder(alice.getId(), edgeType, dave.getId()).property("weight", 4L).build();
            GraphWriteResult seed = store.writer().mutate(GraphMutation.builder()
                .upsertNodes(Arrays.asList(alice, bob, carol, dave))
                .upsertEdges(Arrays.asList(aliceBob, bobCarol, aliceDave)).build(), GraphOptions.ofSpace(space));
            assertTrue(seed.getMessage(), seed.isSuccess());

            // 无属性实体也必须满足 upsert 的幂等语义。
            GraphNode empty = GraphNode.builder("nebula-empty-" + suffix, tag).build();
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertNode(empty).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertNode(empty).build(),
                GraphOptions.ofSpace(space)).isSuccess());

            GraphWriteResult update = store.writer().mutate(GraphMutation.builder()
                .upsertNode(person(alice.getId(), tag, "Alice Updated", 42, true)).build(),
                GraphOptions.ofSpace(space));
            assertTrue(update.isSuccess());
            GraphResult updated = store.query().execute(NativeGraphQuery.of(
                "FETCH PROP ON " + tag + " \"" + alice.getId() + "\" YIELD " + tag + ".name AS name, "
                    + tag + ".score AS score, " + tag + ".active AS active", Collections.<String, Object>emptyMap()),
                GraphOptions.ofSpace(space));
            assertEquals("Alice Updated", updated.getRecords().get(0).get("name"));
            assertEquals(42L, ((Number) updated.getRecords().get(0).get("score")).longValue());
            assertEquals(true, updated.getRecords().get(0).get("active"));

            TraversalQuery ranked = TraversalQuery.from(TraversalQuery.NodePattern.node("n", tag))
                .where(GraphFilter.gt("n", "score", 10L))
                .select(TraversalQuery.Projection.property("n", "name", "name"),
                    TraversalQuery.Projection.property("n", "score", "score"))
                .orderBy(new TraversalQuery.Sort("n", "score", TraversalQuery.SortDirection.DESC))
                .limit(2).build();
            GraphResult rankedResult = store.query().execute(ranked, GraphOptions.ofSpace(space));
            assertEquals(rankedResult.getQueryText(), 2, rankedResult.getRecords().size());
            assertEquals("Alice Updated", rankedResult.getRecords().get(0).get("name"));

            TraversalQuery path = TraversalQuery.from(TraversalQuery.NodePattern.node("start", tag))
                .traverse(TraversalQuery.EdgePattern.edge("e1", edgeType, TraversalQuery.Direction.OUT),
                    TraversalQuery.NodePattern.node("middle", tag))
                .traverse(TraversalQuery.EdgePattern.edge("e2", edgeType, TraversalQuery.Direction.OUT),
                    TraversalQuery.NodePattern.node("end", tag))
                .where(GraphFilter.eq("start", "name", "Alice Updated"))
                .select(TraversalQuery.Projection.path("route")).build();
            GraphResult pathResult = store.query().execute(path, GraphOptions.ofSpace(space));
            assertEquals(1, pathResult.getRecords().size());
            assertNotNull(pathResult.getRecords().get(0).get("route"));

            TraversalQuery count = TraversalQuery.from(TraversalQuery.NodePattern.node("n", tag))
                .select(TraversalQuery.Projection.count("n", "total")).build();
            GraphResult countResult = store.query().execute(count, GraphOptions.ofSpace(space));
            assertEquals(5L, ((Number) countResult.getRecords().get(0).get("total")).longValue());

            GraphExplainResult explain = store.query().explain(ranked, GraphOptions.ofSpace(space));
            assertEquals("nebula", explain.getBackend());
            assertFalse(explain.getPlanText().trim().isEmpty());

            GraphImportReport imported = store.writer().importData(GraphImportRequest.builder()
                .nodes(Arrays.asList(person("nebula-import-1-" + suffix, tag, "Imported 1", 1, true),
                    person("nebula-import-2-" + suffix, tag, "Imported 2", 2, true)))
                .edges(Collections.singletonList(GraphEdge.builder("nebula-import-1-" + suffix, edgeType,
                    "nebula-import-2-" + suffix).property("weight", 1L).build()))
                .batchSize(1).build(), GraphOptions.ofSpace(space));
            assertTrue(imported.isSuccess());
            assertEquals(2L, imported.getNodesImported());
            assertEquals(1L, imported.getEdgesImported());
            assertEquals(3, imported.getBatchesCompleted());

            assertTrue(store.writer().mutate(GraphMutation.builder().deleteEdge(aliceBob.getKey()).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            assertTrue(store.writer().mutate(GraphMutation.builder().deleteNode(dave.getId()).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            assertTrue(store.writer().mutate(GraphMutation.builder().deleteNode("nebula-empty-" + suffix).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            GraphResult remaining = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + tag + ") RETURN count(n) AS total", Collections.<String, Object>emptyMap()),
                GraphOptions.ofSpace(space));
            assertEquals(5L, ((Number) remaining.getRecords().get(0).get("total")).longValue());

            try {
                store.query().execute(NativeGraphQuery.of("INSERT VERTEX " + tag + "() VALUES \"blocked\":()",
                    Collections.<String, Object>emptyMap(), GraphQueryKind.WRITE),
                    GraphOptions.builder().space(space).readOnly(true).build());
                fail("read-only options must reject native write queries");
            } catch (GraphException expected) {
                assertEquals(GraphErrorCode.INVALID_ARGUMENT, expected.getCode());
            }
        } finally {
            try {
                if (manager.spaceExists(space)) manager.dropSpace(space);
            } finally {
                store.close();
            }
        }
    }

    /**
     * 验证 Nebula 上的 UNION、分页游标、结果截断、空结果和异步导入任务生命周期。
     */
    @Test
    public void shouldExercisePortableUnionPagingCursorAndAsyncImportAgainstRealNebula() throws Exception {
        final String suffix = String.valueOf(System.currentTimeMillis());
        final String tag = "ItAdvanced" + suffix;
        final String edgeType = "ItAdvancedEdge" + suffix;
        GraphManager manager = store.manager();
        try {
            manager.createSpace(GraphSpaceDefinition.builder(space).partitionCount(1).replicaFactor(1).build(),
                GraphManager.CreateMode.IF_ABSENT);
            waitForSpace(manager, store, space);
            manager.applySchema(space, GraphSchema.builder()
                .nodeType(GraphSchema.NodeType.of(tag,
                    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
                    new GraphSchema.Property("score", GraphSchema.PropertyType.INT64, false),
                    new GraphSchema.Property("ratio", GraphSchema.PropertyType.DOUBLE, false)))
                .edgeType(GraphSchema.EdgeType.any(edgeType))
                .index(new GraphSchema.Index("it_advanced_score_" + suffix,
                    GraphSchema.IndexTarget.NODE, tag, Collections.singletonList("score"), false))
                .build(), GraphManager.SchemaMode.ADDITIVE);
            waitForSchema(store, space, tag, edgeType);
            GraphNode low = GraphNode.builder("nebula-advanced-low-" + suffix, tag)
                .property("name", "低值").property("score", 1L).property("ratio", 0.25D).build();
            GraphNode high = GraphNode.builder("nebula-advanced-high-" + suffix, tag)
                .property("name", "高值").property("score", 9L).property("ratio", 0.75D).build();
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertNodes(Arrays.asList(low, high)).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            assertTrue(store.writer().mutate(GraphMutation.builder().upsertEdges(Arrays.asList(
                GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(1).build(),
                GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(2).build(),
                GraphEdge.builder(high.getId(), edgeType, high.getId()).rank(3).build())).build(),
                GraphOptions.ofSpace(space)).isSuccess());
            GraphResult rankedEdges = store.query().execute(NativeGraphQuery.of(
                "MATCH ()-[e:" + edgeType + "]->() RETURN count(e) AS total",
                Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
            assertEquals(3L, ((Number) rankedEdges.getRecords().get(0).get("total")).longValue());
            assertTrue(store.writer().mutate(GraphMutation.builder().deleteEdge(
                new com.agentsflex.graph.data.GraphEdgeKey(low.getId(), edgeType, high.getId(), 2)).build(),
                GraphOptions.ofSpace(space)).isSuccess());

            TraversalQuery lowQuery = TraversalQuery.from(TraversalQuery.NodePattern.node("n", tag))
                .where(GraphFilter.le("n", "score", 1L))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build();
            TraversalQuery highQuery = TraversalQuery.from(TraversalQuery.NodePattern.node("n", tag))
                .where(GraphFilter.ge("n", "score", 9L))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build();
            GraphResult union = store.query().execute(GraphUnionQuery.union(lowQuery, highQuery),
                GraphOptions.ofSpace(space));
            assertEquals(2, union.getRecords().size());
            GraphResult typed = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + tag + ") RETURN n." + tag + ".name AS name, n." + tag + ".ratio AS ratio",
                Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
            assertEquals(2, typed.getRecords().size());
            assertTrue(((Number) typed.getRecords().get(0).get("ratio")).doubleValue() > 0D);

            TraversalQuery all = TraversalQuery.from(TraversalQuery.NodePattern.node("n", tag))
                .select(TraversalQuery.Projection.property("n", "name", "name"))
                .orderBy(new TraversalQuery.Sort("n", "name", TraversalQuery.SortDirection.ASC)).build();
            GraphPageResult first = store.query().executePage(all, GraphPageRequest.of(0, 1), GraphOptions.ofSpace(space));
            assertEquals(1, first.getResult().getRecords().size());
            assertTrue(first.hasNext());
            GraphPageResult second = store.query().executePage(all,
                GraphPageRequest.after(first.getNextCursor(), 1), GraphOptions.ofSpace(space));
            assertEquals(1, second.getResult().getRecords().size());
            assertFalse(second.hasNext());

            GraphResult truncated = store.query().execute(all, GraphOptions.builder().space(space)
                .maxRecords(1).build());
            assertEquals(1, truncated.getRecords().size());
            assertTrue(truncated.getMetadata().isTruncated());
            GraphResult empty = store.query().execute(TraversalQuery.from(
                TraversalQuery.NodePattern.node("n", tag)).where(GraphFilter.eq("n", "name", "不存在"))
                .select(TraversalQuery.Projection.property("n", "name", "name")).build(), GraphOptions.ofSpace(space));
            assertTrue(empty.getRecords().isEmpty());
            assertFalse(empty.getMetadata().isTruncated());

            try {
                store.query().execute(NativeGraphQuery.of("SHOW TAGS", Collections.<String, Object>emptyMap()),
                    GraphOptions.ofSpace("missing_nebula_space_" + suffix));
                fail("an unknown Nebula space must fail explicitly");
            } catch (GraphException expected) {
                assertNotNull(expected.getCode());
            }
            try {
                store.query().execute(NativeGraphQuery.of("MATCH (", Collections.<String, Object>emptyMap()),
                    GraphOptions.ofSpace(space));
                fail("invalid nGQL must be mapped to GraphException");
            } catch (GraphException expected) {
                assertEquals(GraphErrorCode.QUERY_FAILED, expected.getCode());
            }

            GraphResultCursor cursor = store.query().executeCursor(all, GraphOptions.ofSpace(space));
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
                .nodes(Arrays.asList(GraphNode.builder("nebula-advanced-import-" + suffix, tag)
                    .property("name", "异步").property("score", 3L).build()))
                .batchSize(1).build(), GraphOptions.ofSpace(space));
            GraphImportTask completed = awaitImport(submitted.getId());
            assertEquals(GraphImportStatus.SUCCEEDED, completed.getStatus());
            assertEquals(1L, completed.getNodesProcessed());
            assertTrue(store.imports().list().stream().anyMatch(task -> submitted.getId().equals(task.getId())));
            assertTrue(store.imports().remove(submitted.getId()));
            assertFalse(store.imports().remove(submitted.getId()));

            GraphImportReport partial = store.writer().importData(GraphImportRequest.builder()
                .edges(Arrays.asList(
                    // EDGE Schema 没有声明 bad_property，第一批应失败；第二批仍应继续执行。
                    GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(9)
                        .property("bad_property", "invalid").build(),
                    GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(10).build()))
                .batchSize(1).stopOnError(false).build(), GraphOptions.ofSpace(space));
            assertEquals(1, partial.getBatchesFailed());
            assertEquals(1, partial.getBatchesCompleted());

            List<GraphNode> bulkNodes = new ArrayList<>();
            for (int i = 0; i < 128; i++) {
                bulkNodes.add(GraphNode.builder("nebula-bulk-" + suffix + "-" + i, tag)
                    .property("name", "批量-" + i).property("score", (long) i)
                    .property("ratio", i / 100.0D).build());
            }
            GraphImportReport bulk = store.writer().importData(GraphImportRequest.builder()
                .nodes(bulkNodes).batchSize(32).build(), GraphOptions.ofSpace(space));
            assertTrue(bulk.isSuccess());
            assertEquals(128L, bulk.getNodesImported());
            assertEquals(4, bulk.getBatchesCompleted());
            GraphResult bulkRows = store.query().execute(NativeGraphQuery.of(
                "MATCH (n:" + tag + ") RETURN n." + tag + ".name AS name",
                Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
            int bulkCount = 0;
            for (com.agentsflex.graph.query.GraphRecord row : bulkRows.getRecords()) {
                if (String.valueOf(row.get("name")).startsWith("批量-")) bulkCount++;
            }
            assertEquals(128, bulkCount);

            ExecutorService concurrent = Executors.newFixedThreadPool(4);
            try {
                List<Future<GraphWriteResult>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final int index = i;
                    writes.add(concurrent.submit(() -> store.writer().mutate(GraphMutation.builder()
                        .upsertNode(GraphNode.builder("nebula-concurrent-" + suffix + "-" + index, tag)
                            .property("name", "并发-" + index).property("score", (long) index)
                            .property("ratio", index / 10.0D).build()).build(),
                        GraphOptions.ofSpace(space))));
                }
                for (Future<GraphWriteResult> write : writes) assertTrue(write.get(20, TimeUnit.SECONDS).isSuccess());
                GraphResult concurrentRows = store.query().execute(NativeGraphQuery.of(
                    "MATCH (n:" + tag + ") RETURN n." + tag + ".name AS name",
                    Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
                int concurrentCount = 0;
                for (com.agentsflex.graph.query.GraphRecord row : concurrentRows.getRecords()) {
                    if (String.valueOf(row.get("name")).startsWith("并发-")) concurrentCount++;
                }
                assertEquals(8, concurrentCount);
            } finally {
                concurrent.shutdownNow();
            }

            // 同一 VID 的并发 upsert 必须收敛为一个 TAG，不能产生重复顶点。
            ExecutorService sameKeyExecutor = Executors.newFixedThreadPool(8);
            try {
                List<Future<GraphWriteResult>> sameNodeWrites = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final int index = i;
                    sameNodeWrites.add(sameKeyExecutor.submit(() -> store.writer().mutate(GraphMutation.builder()
                        .upsertNode(GraphNode.builder("nebula-same-key-" + suffix, tag)
                            .property("name", "same-" + index).property("score", (long) index)
                            .property("ratio", index / 10.0D).build())
                        .build(), GraphOptions.ofSpace(space))));
                }
                for (Future<GraphWriteResult> write : sameNodeWrites) {
                    GraphWriteResult result = write.get(20, TimeUnit.SECONDS);
                    assertTrue(result.getMessage(), result.isSuccess());
                }
                GraphResult sameNodeRows = store.query().execute(NativeGraphQuery.of(
                    "MATCH (n:" + tag + ") RETURN n." + tag + ".name AS name",
                    Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
                int sameNodeCount = 0;
                for (com.agentsflex.graph.query.GraphRecord row : sameNodeRows.getRecords()) {
                    if (String.valueOf(row.get("name")).startsWith("same-")) sameNodeCount++;
                }
                assertEquals(1, sameNodeCount);

                List<Future<GraphWriteResult>> sameEdgeWrites = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    sameEdgeWrites.add(sameKeyExecutor.submit(() -> store.writer().mutate(GraphMutation.builder()
                        .upsertEdge(GraphEdge.builder(low.getId(), edgeType, high.getId()).rank(99).build())
                        .build(), GraphOptions.ofSpace(space))));
                }
                for (Future<GraphWriteResult> write : sameEdgeWrites) assertTrue(write.get(20, TimeUnit.SECONDS).isSuccess());
                GraphResult sameEdgeRows = store.query().execute(NativeGraphQuery.of(
                    "MATCH ()-[e:" + edgeType + "]->() RETURN count(e) AS total",
                    Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
                // 此前保留了 rank=1、rank=3 和 stopOnError=false 成功写入的 rank=10，
                // 同一 rank=99 的并发 upsert 只能额外产生一条边。
                assertEquals(4L, ((Number) sameEdgeRows.getRecords().get(0).get("total")).longValue());
            } finally {
                sameKeyExecutor.shutdownNow();
            }
        } finally {
            try {
                if (manager.spaceExists(space)) manager.dropSpace(space);
            } finally {
                store.close();
            }
        }
    }

    private static GraphNode person(String id, String tag, String name, long score, boolean active) {
        return GraphNode.builder(id, tag).property("name", name).property("score", score)
            .property("active", active).build();
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

    /** 等待 MetaD/Graphd 将新建 TAG 和 EDGE 的 Schema 传播到可写的 StorageD。 */
    private static void waitForSchema(NebulaGraphStore store, String space, String tag, String edgeType)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                com.vesoft.nebula.client.graph.data.ResultSet tagResult = store.pool(space)
                    .execute("DESCRIBE TAG " + tag);
                com.vesoft.nebula.client.graph.data.ResultSet edgeResult = store.pool(space)
                    .execute("DESCRIBE EDGE " + edgeType);
                if (tagResult.isSucceeded() && edgeResult.isSucceeded()) {
                    // DESCRIBE 只证明 MetaD 已保存 DDL；用一次真实写入确认 StorageD
                    // 已加载新 Schema，避免随后 UPSERT 命中 No schema found。
                    com.vesoft.nebula.client.graph.data.ResultSet probe = store.pool(space).execute(
                        "INSERT VERTEX IF NOT EXISTS " + tag + "() VALUES \"__agentsflex_schema_probe\":()");
                    if (probe.isSucceeded()) {
                        store.pool(space).execute("DELETE VERTEX \"__agentsflex_schema_probe\"");
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Schema DDL 在 Nebula 集群中的传播是异步的，继续轮询直到数据面就绪。
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Nebula schema was not ready: " + tag + ", " + edgeType);
    }

    /** 等待新 TAG 已在 StorageD 上可查询，供只创建节点 Schema 的高级场景使用。 */
    private static void waitForTag(NebulaGraphStore store, String space, String tag) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                com.vesoft.nebula.client.graph.data.ResultSet describe = store.pool(space)
                    .execute("DESCRIBE TAG " + tag);
                if (describe.isSucceeded()) {
                    com.vesoft.nebula.client.graph.data.ResultSet probe = store.pool(space)
                        .execute("INSERT VERTEX IF NOT EXISTS " + tag + "() VALUES \"__agentsflex_tag_probe\":()");
                    if (probe.isSucceeded()) {
                        store.pool(space).execute("DELETE VERTEX \"__agentsflex_tag_probe\"");
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Schema 传播未完成时继续轮询。
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Nebula tag was not ready: " + tag);
    }

    private GraphImportTask awaitImport(String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            GraphImportTask task = store.imports().get(id);
            if (task != null && task.isTerminal()) return task;
            Thread.sleep(100L);
        }
        fail("Timed out waiting for import task " + id);
        return null;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
