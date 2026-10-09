package com.agentsflex.graph.store.jdbc;

import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.ingestion.*;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;
import com.agentsflex.graph.extractor.review.*;
import com.agentsflex.graph.importing.*;
import com.agentsflex.graph.mutation.GraphMutation;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * JDBC Graph Store 的 H2 MySQL 兼容模式综合单元测试。
 *
 * <p>除复用 testkit 的公共 SPI 契约外，本测试还覆盖完整对象往返、审核过滤分页、导入任务、
 * 自定义序列化器、实体名称规范化、并发实体合并和数据库锁互斥等 JDBC 特有行为。</p>
 */
public class JdbcGraphStoresTest {
    private JdbcGraphStoreConfig config;

    @Before
    public void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:graph_store_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        config = JdbcGraphStoreConfig.builder(ds).tablePrefix("t" + UUID.randomUUID().toString().replace("-", "") + "_").build();
        config.schema().initialize();
        config.schema().initialize();
    }

    @Test
    public void documentStateShouldRoundTripAndProtectCas() {
        JdbcGraphDocumentStateStore store = config.documentStateStore();
        GraphEdgeKey edge = new GraphEdgeKey("n1", "KNOWS", "n2", 0);
        GraphEvidence evidence = new GraphEvidence("doc", "chunk", "quote", 1, 6, Collections.<String, Object>singletonMap("source", "unit"));
        GraphFactSource fact = new GraphFactSource("fact", "op", 1, 2, edge, evidence, .9, GraphAssertionType.EXPLICIT, Collections.<String, Object>singletonMap("weight", 2));
        GraphDocumentState first = GraphDocumentState.builder("space", "doc", "hash")
            .revision(1).operationId("op").documentVersion("v1").schemaVersion("s1")
            .sourceUpdatedAtMillis(3).committedAtMillis(4).nodeId("n1").factSource(fact).build();
        assertTrue(store.compareAndSet("space", "doc", 0, first));
        assertFalse(store.compareAndSet("space", "doc", 0, first));
        assertEquals("quote", store.findCurrent("space", "doc").getFactSources().get(0).getEvidence().getQuote());
        store.recordVersion(first);
        store.recordVersion(first);
        assertEquals(1, store.listVersions("space", "doc").size());
        assertEquals(1L, store.findByOperationId("space", "doc", "op").getRevision());
        assertTrue(store.isReferencedByOtherDocument("space", "other", edge));
        assertEquals(1, store.findCurrentFactSources("space", edge).size());
        assertFalse(store.remove("space", "doc", 2));
        assertTrue(store.remove("space", "doc", 1));
        assertEquals(1, store.findFactSourceHistory("space", edge).size());
    }

    @Test
    public void concurrentInitialDocumentCasShouldHaveOneWinner() throws Exception {
        final JdbcGraphDocumentStateStore store = config.documentStateStore();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> store.compareAndSet("space", "race", 0,
                GraphDocumentState.builder("space", "race", "a").revision(1).committedAtMillis(1).build()));
            Future<Boolean> second = executor.submit(() -> store.compareAndSet("space", "race", 0,
                GraphDocumentState.builder("space", "race", "b").revision(1).committedAtMillis(1).build()));
            assertEquals(1, (first.get(2, TimeUnit.SECONDS) ? 1 : 0) + (second.get(2, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void operationShouldPersistPlanAndUseStageCas() {
        JdbcGraphIngestionOperationStore store = config.ingestionOperationStore();
        GraphIngestionOperation op = operation("op-1", "doc-1", 10);
        GraphIngestionPlan plan = plan("op-1", "doc-1");
        assertTrue(store.createIfAbsent(op, plan));
        assertFalse(store.createIfAbsent(op, plan));
        assertEquals("doc-1", store.getPlan("op-1").getDocumentId());
        GraphIngestionOperation next = op.transition(GraphIngestionOperation.Stage.GRAPH_APPLIED, 11, "");
        assertFalse(store.compareAndSet("op-1", GraphIngestionOperation.Stage.FAILED, next));
        assertTrue(store.compareAndSet("op-1", GraphIngestionOperation.Stage.PREPARED, next));
        assertEquals(GraphIngestionOperation.Stage.GRAPH_APPLIED, store.get("op-1").getStage());
        try {
            store.createIfAbsent(operation("bad", "doc-1", 1), plan("bad", "other"));
            fail("identity mismatch expected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            store.listRecoverableOperations(0);
            fail("limit must be validated");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void reviewAndImportStoresShouldRoundTripAndFilter() {
        GraphReviewTask review = GraphReviewTask.pending("review-1", plan("op-review", "doc-a"), 10);
        JdbcGraphReviewStore reviews = config.reviewStore();
        assertSame(review, reviews.create(review));
        assertEquals("doc-a", reviews.findTask("review-1").getPlan().getDocumentId());
        assertEquals(0L, reviews.findTask("review-1").getReviewVersion());
        GraphReviewTask reviewTwo = GraphReviewTask.pending("review-2", plan("op-review-2", "doc-b"), 20);
        reviews.create(reviewTwo);
        assertEquals(2, reviews.findTasks(GraphReviewTaskQuery.builder().space("space").status(GraphReviewTaskStatus.PENDING_REVIEW).build()).size());
        assertEquals("review-1", reviews.findTasks(GraphReviewTaskQuery.builder().space("space").offset(1).limit(1).build()).get(0).getTaskId());
        GraphReviewTask updated = GraphReviewTask.restore("review-1", review.getPlan(), GraphReviewTaskStatus.COMPLETED, 1, "ok", "tester", 10, 11);
        assertSame(updated, reviews.update(updated, 0));
        try {
            reviews.update(updated, 0);
            fail("version conflict expected");
        } catch (IllegalStateException expected) {
        }

        GraphImportTask task = importTask("task-1");
        JdbcGraphImportTaskStore imports = config.importTaskStore();
        imports.save(task);
        assertEquals(GraphImportStatus.QUEUED, imports.get("task-1").getStatus());
        assertEquals(1, imports.list().size());
        assertTrue(imports.remove("task-1"));
        assertFalse(imports.remove("task-1"));
    }

    @Test
    public void registryShouldMergeAndNormalizeNames() {
        JdbcGraphEntityRegistry registry = config.entityRegistry();
        registry.saveAll("space", Arrays.asList(
            new GraphRegisteredEntity("n1", "Person", "Alice", Arrays.asList("A"), Collections.<String, Object>singletonMap("role", "dev")),
            new GraphRegisteredEntity("n1", "Person", "Alice", Collections.singletonList("Ally"), Collections.<String, Object>singletonMap("team", "core"))));
        assertEquals(1, registry.findMatches("space", "Person", Arrays.asList(" alice ", "ally")).size());
        assertEquals("core", registry.findMatches("space", "Person", Collections.singleton("ALLY")).get(0).getProperties().get("team"));
        try {
            registry.saveAll("space", Collections.singletonList(new GraphRegisteredEntity("n2", "Person", "ALICE", null, null)));
            fail("duplicate name expected");
        } catch (RuntimeException expected) {
        }
    }

    @Test
    public void registryShouldDeduplicateEquivalentNamesAndNormalizeTypes() {
        JdbcGraphEntityRegistry registry = config.entityRegistry();
        registry.saveAll("space", Collections.singletonList(
            new GraphRegisteredEntity("n1", "Person", "Alice",
                Arrays.asList(" alice ", "ALICE", "Ally"), null)));

        List<GraphRegisteredEntity> matches = registry.findMatches(
            "space", "person", Arrays.asList("ALICE", "ally"));
        assertEquals(1, matches.size());
        assertEquals("n1", matches.get(0).getNodeId());
    }

    @Test
    public void concurrentRegistryUpdatesShouldNotLoseAliases() throws Exception {
        final JdbcGraphEntityRegistry registry = config.entityRegistry();
        registry.saveAll("space", Collections.singletonList(
            new GraphRegisteredEntity("n1", "Person", "Alice", null, null)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> registry.saveAll("space", Collections.singletonList(
                new GraphRegisteredEntity("n1", "Person", "Alice", Collections.singletonList("A1"), null))));
            Future<?> second = executor.submit(() -> registry.saveAll("space", Collections.singletonList(
                new GraphRegisteredEntity("n1", "Person", "Alice", Collections.singletonList("A2"), null))));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            GraphRegisteredEntity loaded = registry.findMatches(
                "space", "Person", Collections.singleton("Alice")).get(0);
            assertTrue(loaded.getAliases().contains("A1"));
            assertTrue(loaded.getAliases().contains("A2"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentInitialRegistryWritesShouldMergeAliases() throws Exception {
        final JdbcGraphEntityRegistry registry = config.entityRegistry();
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> saveEntityAfterBarrier(registry, "A1", ready, start));
            Future<?> second = executor.submit(() -> saveEntityAfterBarrier(registry, "A2", ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            GraphRegisteredEntity loaded = registry.findMatches(
                "space", "Person", Collections.singleton("Alice")).get(0);
            assertTrue(loaded.getAliases().contains("A1"));
            assertTrue(loaded.getAliases().contains("A2"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void reviewPaginationShouldHandleIntegerBoundary() {
        JdbcGraphReviewStore reviews = config.reviewStore();
        reviews.create(GraphReviewTask.pending("review-1", plan("op-review", "doc-a"), 10));
        assertTrue(reviews.findTasks(GraphReviewTaskQuery.builder()
            .offset(Integer.MAX_VALUE).limit(1).build()).isEmpty());
    }

    @Test
    public void customSerializerShouldHandleStorePayloads() {
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final JdbcGraphStoreSerializer delegate = new FastjsonJdbcGraphStoreSerializer();
        JdbcGraphStoreSerializer serializer = new JdbcGraphStoreSerializer() {
            @Override
            public byte[] serialize(Object value) {
                writes.incrementAndGet();
                return delegate.serialize(value);
            }

            @Override
            public <T> T deserialize(byte[] value, Class<T> type) {
                reads.incrementAndGet();
                return delegate.deserialize(value, type);
            }
        };
        JdbcGraphStoreConfig custom = JdbcGraphStoreConfig.builder(config.getDataSource())
            .serializer(serializer)
            .tablePrefix("s" + UUID.randomUUID().toString().replace("-", "") + "_").build();
        custom.schema().initialize();
        GraphDocumentState state = GraphDocumentState.builder("space", "doc", "hash").revision(1).build();
        assertTrue(custom.documentStateStore().compareAndSet("space", "doc", 0, state));
        assertEquals(1L, custom.documentStateStore().findCurrent("space", "doc").getRevision());
        assertTrue(writes.get() > 0);
        assertTrue(reads.get() > 0);
    }

    @Test
    public void lockShouldBeMutualAndScoped() throws Exception {
        final JdbcGraphIngestionLockProvider locks = config.lockProvider();
        final GraphIngestionLockProvider.Lease first = locks.acquire("space", "doc");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GraphIngestionLockProvider.Lease> blocked = executor.submit(() -> locks.acquire("space", "doc"));
            Thread.sleep(100);
            assertFalse(blocked.isDone());
            first.close();
            GraphIngestionLockProvider.Lease second = blocked.get(2, TimeUnit.SECONDS);
            second.close();
            GraphIngestionLockProvider.Lease other = locks.acquire("space", "other");
            other.close();
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    private static GraphIngestionOperation operation(String id, String doc, long time) {
        return new GraphIngestionOperation(id, "space", doc, 0, "fingerprint", GraphIngestionOperation.Stage.PREPARED, time, "");
    }

    private static GraphIngestionPlan plan(String id, String doc) {
        GraphDocumentState next = GraphDocumentState.builder("space", doc, "hash").revision(1).operationId(id).build();
        return GraphIngestionPlan.restore(GraphIngestionPlan.Type.INGESTION, "space", doc, GraphOptions.ofSpace("space"), null, next, null, GraphMutation.builder().operationId(id).build(), Collections.emptySet(), Collections.emptyList());
    }

    private static GraphImportTask importTask(String id) {
        try {
            java.lang.reflect.Constructor<GraphImportTask> constructor = GraphImportTask.class.getDeclaredConstructor(String.class, GraphImportStatus.class, GraphImportReport.class, String.class, long.class, long.class, long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id, GraphImportStatus.QUEUED, new GraphImportReport(), "", 1L, -1L, -1L);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void saveEntityAfterBarrier(JdbcGraphEntityRegistry registry, String alias,
                                               CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start barrier timed out");
            registry.saveAll("space", Collections.singletonList(
                new GraphRegisteredEntity("n1", "Person", "Alice", Collections.singletonList(alias), null)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
