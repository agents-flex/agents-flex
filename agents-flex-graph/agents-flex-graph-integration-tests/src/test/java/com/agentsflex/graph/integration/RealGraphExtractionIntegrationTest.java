package com.agentsflex.graph.integration;

import com.agentsflex.core.document.Document;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.extractor.GraphCandidateResult;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.GraphExtractor;
import com.agentsflex.graph.extractor.GraphMutationMapper;
import com.agentsflex.graph.extractor.ingestion.GraphDocumentState;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphDocumentStateStore;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphIngestionOperationStore;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionRequest;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionService;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.importing.GraphImportReport;
import com.agentsflex.graph.importing.GraphImportRequest;
import com.agentsflex.graph.manager.GraphManager;
import com.agentsflex.graph.manager.GraphSpaceDefinition;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.nebula.NebulaGraphStore;
import com.agentsflex.graph.nebula.NebulaGraphStoreConfig;
import com.agentsflex.graph.neo4j.Neo4jGraphStore;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;
import com.agentsflex.graph.query.GraphQueryKind;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.NativeGraphQuery;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 从确定性候选抽取到真实图数据库写入、恢复、查询和撤回的跨模块集成测试。
 *
 * <p>测试通过 {@code GRAPH_INTEGRATION=true} 显式启用，连接变量与各适配器真实测试保持一致。
 * 首次写入会模拟“数据库已经提交，但客户端在收到成功响应前断开”，随后重放同一 mutation，验证
 * Neo4j 和 Nebula 的最终图都不会产生重复节点或重复关系。</p>
 */
public class RealGraphExtractionIntegrationTest {
    /**
     * 在真实 Neo4j 上验证完整增量抽取生命周期。
     */
    @Test
    public void shouldIngestRecoverQueryAndRetractAgainstNeo4j() {
        requireIntegration();
        String suffix = Long.toString(System.currentTimeMillis());
        String character = "ItCharacter" + suffix;
        String organization = "ItOrganization" + suffix;
        String relation = "ItMemberOf" + suffix;
        Neo4jGraphStore store = new Neo4jGraphStore(new Neo4jGraphStoreConfig()
            .setUri(value("GRAPH_NEO4J_URI", "bolt://localhost:17687"))
            .setUsername(value("GRAPH_NEO4J_USER", "neo4j"))
            .setPassword(value("GRAPH_NEO4J_PASSWORD", "agentsflex"))
            .setDefaultSpace("neo4j"));
        try {
            assertTrue(store.health().isUp());
            GraphSchema schema = schema(character, organization, relation);
            store.manager().applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);
            runLifecycle(store, "neo4j", schema, character, organization, relation,
                "MATCH (n:" + character + ") RETURN count(n) AS total",
                "MATCH (n:" + organization + ") RETURN count(n) AS total",
                "MATCH ()-[r:" + relation + "]->() RETURN count(r) AS total");
        } finally {
            try {
                store.query().execute(NativeGraphQuery.of(
                    "MATCH (n) WHERE any(label IN labels(n) WHERE label IN $labels) DETACH DELETE n",
                    Collections.<String, Object>singletonMap("labels", Arrays.asList(character, organization)),
                    GraphQueryKind.WRITE), GraphOptions.ofSpace("neo4j"));
            } finally {
                store.close();
            }
        }
    }

    /**
     * 在真实 Nebula Graph 上验证与 Neo4j 相同的完整增量抽取生命周期。
     */
    @Test
    public void shouldIngestRecoverQueryAndRetractAgainstNebula() throws Exception {
        requireIntegration();
        String space = "agents_flex_extract_" + System.currentTimeMillis();
        String character = "ItCharacter";
        String organization = "ItOrganization";
        String relation = "ItMemberOf";
        NebulaGraphStore store = new NebulaGraphStore(new NebulaGraphStoreConfig()
            .setHost(value("GRAPH_NEBULA_HOST", "127.0.0.1"))
            .setPort(Integer.parseInt(value("GRAPH_NEBULA_PORT", "19670")))
            .setUsername(value("GRAPH_NEBULA_USER", "root"))
            .setPassword(value("GRAPH_NEBULA_PASSWORD", "nebula"))
            .setDefaultSpace(value("GRAPH_NEBULA_BOOTSTRAP_SPACE", "agents_flex_bootstrap")));
        GraphManager manager = store.manager();
        try {
            assertTrue(store.health().isUp());
            manager.createSpace(GraphSpaceDefinition.builder(space).partitionCount(1).replicaFactor(1).build(),
                GraphManager.CreateMode.IF_ABSENT);
            waitForSpace(manager, store, space);
            GraphSchema schema = schema(character, organization, relation);
            manager.applySchema(space, schema, GraphManager.SchemaMode.ADDITIVE);
            waitForSchema(store, space, character, organization, relation);
            runLifecycle(store, space, schema, character, organization, relation,
                "MATCH (n:" + character + ") RETURN count(n) AS total",
                "MATCH (n:" + organization + ") RETURN count(n) AS total",
                "MATCH ()-[r:" + relation + "]->() RETURN count(r) AS total");
        } finally {
            try {
                if (manager.spaceExists(space)) manager.dropSpace(space);
            } finally {
                store.close();
            }
        }
    }

    /**
     * 执行后端无关的首次失败重放、第二来源导入和逐来源撤回场景。
     */
    private static void runLifecycle(GraphStore store, String space, GraphSchema schema, String character,
                                     String organization, String relation, String characterCountQuery,
                                     String organizationCountQuery, String relationCountQuery) {
        DeterministicExtractor extractor = new DeterministicExtractor(character, organization, relation);
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor,
            (document, idGenerator) -> Collections.singletonList(document), new SchemaGraphCandidateValidator(),
            new RegistryGraphEntityResolver(registry), new GraphMutationMapper());
        InMemoryGraphDocumentStateStore states = new InMemoryGraphDocumentStateStore();
        InMemoryGraphIngestionOperationStore operations = new InMemoryGraphIngestionOperationStore();
        GraphIngestionService service = new GraphIngestionService(pipeline, states, registry,
            operations);
        CommitThenFailWriter uncertainWriter = new CommitThenFailWriter(store.writer());
        GraphIngestionRequest firstRequest = GraphIngestionRequest.builder(space, "doc-a")
            .operationId("extract-first-" + System.nanoTime())
            .staleRelationPolicy(GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED).build();
        try {
            service.ingest(Document.of("林默加入青云会"), schema, firstRequest, uncertainWriter);
            fail("the first client response should be interrupted after backend commit");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("after backend commit"));
        }

        assertTrue(service.resume(firstRequest.getOperationId(), store.writer()).isSuccess());
        assertEquals(2L, count(store, space, characterCountQuery) + count(store, space, organizationCountQuery));
        assertEquals(1L, count(store, space, relationCountQuery));
        assertEquals(1, extractor.calls.get());

        GraphIngestionRequest secondRequest = GraphIngestionRequest.builder(space, "doc-b")
            .operationId("extract-second-" + System.nanoTime())
            .staleRelationPolicy(GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED).build();
        assertTrue(service.ingest(Document.of("林默加入青云会"), schema, secondRequest, store.writer()).isSuccess());
        assertEquals(1L, count(store, space, relationCountQuery));

        assertTrue(service.execute(service.planRetraction(space, "doc-a", GraphOptions.ofSpace(space)),
            store.writer()).isSuccess());
        assertEquals(1L, count(store, space, relationCountQuery));
        assertEquals(GraphDocumentState.Status.RETRACTED, states.get(space, "doc-a").getStatus());

        assertTrue(service.execute(service.planRetraction(space, "doc-b", GraphOptions.ofSpace(space)),
            store.writer()).isSuccess());
        assertEquals(0L, count(store, space, relationCountQuery));
        assertEquals(1L, count(store, space, characterCountQuery));
        assertEquals(1L, count(store, space, organizationCountQuery));
        assertEquals(GraphDocumentState.Status.RETRACTED, states.get(space, "doc-b").getStatus());
    }

    /**
     * 读取原生 count 查询的唯一数值。
     */
    private static long count(GraphStore store, String space, String query) {
        GraphResult result = store.query().execute(NativeGraphQuery.of(query,
            Collections.<String, Object>emptyMap()), GraphOptions.ofSpace(space));
        assertEquals(1, result.getRecords().size());
        Object total = result.getRecords().get(0).get("total");
        assertNotNull(total);
        return ((Number) total).longValue();
    }

    /**
     * 创建人物、组织和隶属关系 Schema。
     */
    private static GraphSchema schema(String character, String organization, String relation) {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false);
        return GraphSchema.builder().nodeType(GraphSchema.NodeType.of(character, name))
            .nodeType(GraphSchema.NodeType.of(organization, name))
            .edgeType(GraphSchema.EdgeType.of(relation, character, organization)).build();
    }

    /**
     * 等待 Nebula Space 不仅可见，而且已经能够绑定会话执行语句。
     */
    private static void waitForSpace(GraphManager manager, NebulaGraphStore store, String space) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (manager.spaceExists(space)) {
                try {
                    if (store.pool(space).execute("SHOW TAGS").isSucceeded()) return;
                } catch (Exception ignored) {
                    // MetaD 到 GraphD/StorageD 的传播尚未完成，继续有界轮询。
                }
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Nebula space was not ready: " + space);
    }

    /**
     * 等待两个 TAG 和 EDGE 真正传播到 StorageD。
     */
    private static void waitForSchema(NebulaGraphStore store, String space, String character,
                                      String organization, String relation) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                boolean ready = store.pool(space).execute("DESCRIBE TAG " + character).isSucceeded()
                    && store.pool(space).execute("DESCRIBE TAG " + organization).isSucceeded()
                    && store.pool(space).execute("DESCRIBE EDGE " + relation).isSucceeded();
                if (ready) {
                    Thread.sleep(1_000L);
                    return;
                }
            } catch (Exception ignored) {
                // Schema 传播窗口内继续轮询。
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Nebula schema was not ready in space: " + space);
    }

    /**
     * 要求调用方显式启用真实数据库测试。
     */
    private static void requireIntegration() {
        Assume.assumeTrue("set GRAPH_INTEGRATION=true to run Docker integration tests",
            "true".equalsIgnoreCase(System.getenv("GRAPH_INTEGRATION")));
    }

    /**
     * 获取可覆盖的环境变量。
     */
    private static String value(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    /**
     * 模拟真实模型已完成候选抽取，同时保持集成测试完全确定。
     */
    private static final class DeterministicExtractor implements GraphExtractor {
        private final String characterType;
        private final String organizationType;
        private final String relationType;
        private final AtomicInteger calls = new AtomicInteger();

        private DeterministicExtractor(String characterType, String organizationType, String relationType) {
            this.characterType = characterType;
            this.organizationType = organizationType;
            this.relationType = relationType;
        }

        @Override
        public GraphCandidateResult extract(GraphExtractionRequest request) {
            calls.incrementAndGet();
            GraphEvidence evidence = new GraphEvidence(request.getDocumentId(), request.getChunkId(), request.getText(),
                -1, -1, Collections.<String, Object>emptyMap());
            GraphEntityCandidate person = new GraphEntityCandidate(request.getChunkId() + "::person", "林默",
                characterType, Collections.<String>emptyList(),
                Collections.<String, Object>singletonMap("name", "林默"), evidence, 1D);
            GraphEntityCandidate group = new GraphEntityCandidate(request.getChunkId() + "::organization", "青云会",
                organizationType, Collections.<String>emptyList(),
                Collections.<String, Object>singletonMap("name", "青云会"), evidence, 1D);
            GraphRelationCandidate relation = new GraphRelationCandidate(person.getCandidateKey(), relationType,
                group.getCandidateKey(), 0L, Collections.<String, Object>emptyMap(), evidence, 1D,
                GraphAssertionType.EXPLICIT);
            return new GraphCandidateResult(Arrays.asList(person, group), Collections.singletonList(relation),
                Collections.emptyList(), "{}");
        }
    }

    /**
     * 首次调用先真实提交，再抛出连接异常，模拟无法判断数据库是否提交的网络故障窗口。
     */
    private static final class CommitThenFailWriter implements GraphWriter {
        private final GraphWriter delegate;
        private boolean first = true;

        private CommitThenFailWriter(GraphWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            GraphWriteResult result = delegate.mutate(mutation, options);
            assertTrue(result.getMessage(), result.isSuccess());
            if (first) {
                first = false;
                throw new IllegalStateException("synthetic disconnect after backend commit");
            }
            return result;
        }

        @Override
        public GraphImportReport importData(GraphImportRequest request, GraphOptions options) {
            return delegate.importData(request, options);
        }
    }
}
