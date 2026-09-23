package com.agentsflex.graph.extractor.incremental;

import com.agentsflex.core.document.Document;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.data.GraphEdgeKey;
import com.agentsflex.graph.extractor.GraphCandidateBatch;
import com.agentsflex.graph.extractor.GraphExtractionException;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.GraphExtractionRequest;
import com.agentsflex.graph.extractor.GraphExtractor;
import com.agentsflex.graph.extractor.GraphMutationMapper;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.mutation.GraphMutation;
import com.agentsflex.graph.mutation.GraphWriteResult;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 长期增量文档判重、版本替换、来源引用和提交边界测试。
 */
public class IncrementalGraphIngestionServiceTest {
    /**
     * 相同内容摘要再次导入时不得调用 extractor 或 GraphWriter。
     */
    @Test
    public void shouldSkipUnchangedDocumentBeforeModelCall() {
        Scenario scenario = scenario();
        Document document = Document.of("林默加入青云会");
        IncrementalGraphIngestionRequest request = request("doc-1").build();

        IncrementalGraphIngestionResult first = scenario.service.ingest(document, schema(), request, scenario.writer);
        IncrementalGraphIngestionResult second = scenario.service.ingest(document, schema(), request, scenario.writer);

        assertTrue(first.isSuccess());
        assertEquals(IncrementalGraphIngestionPlan.Status.UNCHANGED, second.getPlan().getStatus());
        assertEquals(1, scenario.extractor.calls.get());
        assertEquals(1, scenario.writer.calls);
        assertEquals(1L, scenario.states.get("knowledge", "doc-1").getRevision());
        assertEquals(1, scenario.states.get("knowledge", "doc-1").getFactProvenances().size());
        assertEquals("doc-1", scenario.states.get("knowledge", "doc-1").getFactProvenances().get(0)
            .getEvidence().getDocumentId());
    }

    /**
     * 关系仍被其他文档支持时，新版本不得误删共享事实。
     */
    @Test
    public void shouldKeepStaleRelationReferencedByAnotherDocumentAndDeleteAfterLastRetraction() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-a").build(), scenario.writer);
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-b").build(), scenario.writer);

        IncrementalGraphIngestionResult updated = scenario.service.ingest(Document.of("林默离开了山门"), schema(),
            request("doc-a").staleRelationPolicy(
                IncrementalGraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED).build(), scenario.writer);

        assertEquals(1, updated.getPlan().getStaleEdgeKeys().size());
        assertTrue(updated.getPlan().getMutation().getDeleteEdgeKeys().isEmpty());
        IncrementalGraphIngestionPlan retraction = scenario.service.planRetraction("knowledge", "doc-b",
            GraphOptions.ofSpace("knowledge"));
        assertEquals(1, retraction.getMutation().getDeleteEdgeKeys().size());
        IncrementalGraphIngestionResult retracted = scenario.service.execute(retraction, scenario.writer);
        assertTrue(retracted.isSuccess());
        assertNull(scenario.states.get("knowledge", "doc-b"));
        assertNotNull(scenario.states.get("knowledge", "doc-a"));
    }

    /**
     * 同一条关系被多个文档支持时，状态存储应聚合全部来源证据。
     */
    @Test
    public void shouldAggregateProvenanceAcrossDocuments() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-a").build(), scenario.writer);
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-b").build(), scenario.writer);

        GraphEdgeKey sharedEdge = scenario.states.get("knowledge", "doc-a").getEdgeKeys().iterator().next();
        java.util.List<GraphFactProvenance> provenance = scenario.states.findProvenance("knowledge", sharedEdge);

        assertEquals(2, provenance.size());
        assertEquals("doc-a", provenance.get(0).getEvidence().getDocumentId());
        assertEquals("doc-b", provenance.get(1).getEvidence().getDocumentId());
        assertEquals(sharedEdge, provenance.get(0).getEdgeKey());
        assertEquals(sharedEdge, provenance.get(1).getEdgeKey());
    }

    /**
     * 图写入失败时不得推进文档 revision 或提前注册模型识别的实体。
     */
    @Test
    public void shouldCommitStateAndEntityRegistryOnlyAfterSuccessfulGraphWrite() {
        Scenario scenario = scenario();
        scenario.writer.fail = true;

        IncrementalGraphIngestionResult failed = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").batchId("batch-1").schemaVersion("v1").build(), scenario.writer);

        assertFalse(failed.isSuccess());
        assertNull(scenario.states.get("knowledge", "doc-1"));
        assertEquals(0, scenario.registry.size());
        scenario.writer.fail = false;
        IncrementalGraphIngestionResult retried = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").batchId("batch-1").schemaVersion("v1").build(), scenario.writer);
        assertTrue(retried.isSuccess());
        assertEquals(2, scenario.registry.size());
        assertEquals("batch-1", scenario.states.get("knowledge", "doc-1").getBatchId());
        assertEquals("v1", scenario.states.get("knowledge", "doc-1").getSchemaVersion());
    }

    /**
     * 两个基于同一 revision 的计划中，后执行者必须在再次写库前被拒绝。
     */
    @Test
    public void shouldRejectStalePlanBeforeSecondGraphWrite() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);
        IncrementalGraphIngestionPlan first = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").build());
        IncrementalGraphIngestionPlan stale = scenario.service.plan(Document.of("林默返回青云会"), schema(),
            request("doc-1").build());
        scenario.service.execute(first, scenario.writer);
        int callsBeforeStaleExecution = scenario.writer.calls;

        try {
            scenario.service.execute(stale, scenario.writer);
            fail("stale plan should be rejected");
        } catch (GraphExtractionException expected) {
            assertTrue(expected.getMessage().contains("plan is stale"));
        }
        assertEquals(callsBeforeStaleExecution, scenario.writer.calls);
    }

    /**
     * 默认 KEEP 策略应报告过期关系，但不能生成破坏性删除。
     */
    @Test
    public void shouldKeepStaleRelationsByDefault() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(), request("doc-1").build(), scenario.writer);

        IncrementalGraphIngestionPlan plan = scenario.service.plan(Document.of("林默离开了山门"), schema(),
            request("doc-1").build());

        assertEquals(1, plan.getStaleEdgeKeys().size());
        assertTrue(plan.getMutation().getDeleteEdgeKeys().isEmpty());
    }

    /**
     * 相同正文但 Schema 版本变化时必须重新抽取，不能被内容哈希短路。
     */
    @Test
    public void shouldReextractWhenSchemaVersionChanges() {
        Scenario scenario = scenario();
        scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").schemaVersion("v1").build(), scenario.writer);
        IncrementalGraphIngestionResult second = scenario.service.ingest(Document.of("林默加入青云会"), schema(),
            request("doc-1").schemaVersion("v2").build(), scenario.writer);

        assertEquals(IncrementalGraphIngestionPlan.Status.READY, second.getPlan().getStatus());
        assertEquals(2, scenario.extractor.calls.get());
        assertEquals(2L, scenario.states.get("knowledge", "doc-1").getRevision());
    }

    /**
     * 创建包含注册表解析器、内存状态和记录写入器的测试场景。
     */
    private static Scenario scenario() {
        InMemoryGraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
        RecordingExtractor extractor = new RecordingExtractor();
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor,
            (document, idGenerator) -> Collections.singletonList(document),
            new SchemaGraphCandidateValidator(), new RegistryGraphEntityResolver(registry),
            new GraphMutationMapper());
        InMemoryGraphDocumentStateStore states = new InMemoryGraphDocumentStateStore();
        IncrementalGraphIngestionService service = new IncrementalGraphIngestionService(
            pipeline, states, registry, () -> 1_700_000_000_000L);
        return new Scenario(service, extractor, states, registry, new RecordingWriter());
    }

    /**
     * 创建目标 Space 固定为 knowledge 的请求构造器。
     */
    private static IncrementalGraphIngestionRequest.Builder request(String documentId) {
        return IncrementalGraphIngestionRequest.builder("knowledge", documentId);
    }

    /**
     * 创建人物、组织和隶属关系 Schema。
     */
    private static GraphSchema schema() {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true);
        return GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Character", name))
            .nodeType(GraphSchema.NodeType.of("Organization", name))
            .edgeType(GraphSchema.EdgeType.of("MEMBER_OF", "Character", "Organization"))
            .build();
    }

    /**
     * 根据输入文本生成确定候选，模拟模型而不掩盖流水线行为。
     */
    private static final class RecordingExtractor implements GraphExtractor {
        /**
         * 模型调用次数。
         */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public GraphCandidateBatch extract(GraphExtractionRequest request) {
            calls.incrementAndGet();
            GraphEntityCandidate person = entity(request, "p", "林默", "Character");
            if (!request.getText().contains("青云会")) {
                return new GraphCandidateBatch(Collections.singletonList(person), Collections.emptyList(),
                    Collections.emptyList(), "{}");
            }
            GraphEntityCandidate organization = entity(request, "o", "青云会", "Organization");
            GraphRelationCandidate relation = new GraphRelationCandidate(person.getCandidateKey(), "MEMBER_OF",
                organization.getCandidateKey(), 0L, Collections.<String, Object>emptyMap(),
                evidence(request, request.getText()), 1D, GraphAssertionType.EXPLICIT);
            return new GraphCandidateBatch(Arrays.asList(person, organization), Collections.singletonList(relation),
                Collections.emptyList(), "{}");
        }

        /**
         * 创建当前 Chunk 作用域的实体候选。
         */
        private static GraphEntityCandidate entity(GraphExtractionRequest request, String id, String name, String type) {
            return new GraphEntityCandidate(request.getChunkId() + "::" + id, name, type,
                Collections.<String>emptyList(), props("name", name), evidence(request, name), 1D);
        }

        /**
         * 创建与请求父文档和 Chunk 一致的证据。
         */
        private static GraphEvidence evidence(GraphExtractionRequest request, String quote) {
            return new GraphEvidence(request.getDocumentId(), request.getChunkId(), quote, -1, -1,
                request.getMetadata());
        }
    }

    /**
     * 记录调用和最近 mutation，并可返回确定失败的 GraphWriter。
     */
    private static final class RecordingWriter implements GraphWriter {
        /**
         * 写入调用次数。
         */
        private int calls;
        /**
         * 是否模拟写入失败。
         */
        private boolean fail;
        /**
         * 最近一次收到的 mutation。
         */
        private GraphMutation lastMutation;

        @Override
        public GraphWriteResult mutate(GraphMutation mutation, GraphOptions options) {
            calls++;
            lastMutation = mutation;
            if (fail) return GraphWriteResult.failure("write failed", new IllegalStateException("write failed"));
            return GraphWriteResult.success(mutation.getNodes().size(), mutation.getEdges().size()
                + mutation.getDeleteEdgeKeys().size());
        }

        @Override
        public com.agentsflex.graph.importing.GraphImportReport importData(
            com.agentsflex.graph.importing.GraphImportRequest request, GraphOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 聚合测试依赖。
     */
    private static final class Scenario {
        /**
         * 被测服务。
         */
        private final IncrementalGraphIngestionService service;
        /**
         * 记录模型调用的 extractor。
         */
        private final RecordingExtractor extractor;
        /**
         * 内存文档状态。
         */
        private final InMemoryGraphDocumentStateStore states;
        /**
         * 内存实体注册表。
         */
        private final InMemoryGraphEntityRegistry registry;
        /**
         * 记录图写入的 writer。
         */
        private final RecordingWriter writer;

        private Scenario(IncrementalGraphIngestionService service, RecordingExtractor extractor,
                         InMemoryGraphDocumentStateStore states, InMemoryGraphEntityRegistry registry,
                         RecordingWriter writer) {
            this.service = service;
            this.extractor = extractor;
            this.states = states;
            this.registry = registry;
            this.writer = writer;
        }
    }

    /**
     * 创建单属性有序映射。
     */
    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
