package com.agentsflex.graph.extractor;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.resolution.NameAliasGraphEntityResolver;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 完整文档抽取流水线的跨分段集成测试。
 */
public class GraphExtractionPipelineTest {
    /**
     * 验证分段、前文传递、实体别名归一、关系映射和不自动写库。
     */
    @Test
    public void shouldExtractValidateResolveAndMapAcrossChunks() {
        RecordingExtractor extractor = new RecordingExtractor();
        DocumentSplitter splitter = (document, idGenerator) -> Arrays.asList(
            chunk(document, "林默自称林公子。"), chunk(document, "林公子加入青云宗。"));
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor, splitter,
            new SchemaGraphCandidateValidator(), new NameAliasGraphEntityResolver(), new GraphMutationMapper());
        Document novel = Document.of("完整小说正文");
        novel.setId("novel-1");
        novel.putMetadata("chapter", 1);

        GraphExtractionResult result = pipeline.extract(novel, GraphExtractorTestSupport.schema());

        assertEquals(3, result.getEntities().size());
        assertEquals(3, result.getAllEntities().size());
        assertEquals(2, result.getResolution().getNodes().size());
        assertEquals(2, result.getMutation().getNodes().size());
        assertEquals(1, result.getMutation().getEdges().size());
        assertTrue(extractor.contexts.get(0).isEmpty());
        assertEquals("林默自称林公子。", extractor.contexts.get(1));
        assertFalse(result.hasErrors());
        assertEquals(2, result.getRawResponses().size());
    }

    /**
     * 空文档应在调用模型前失败。
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankDocument() {
        new GraphExtractionPipeline(request -> new GraphCandidateBatch(null, null, null, ""))
            .extract(Document.of(" "), GraphExtractorTestSupport.schema());
    }

    /**
     * 配置为继续执行时，单分段模型失败应转为结构化问题。
     */
    @Test
    public void shouldContinueAfterChunkFailureWhenConfigured() {
        DocumentSplitter splitter = (document, idGenerator) -> Arrays.asList(Document.of("失败段"), Document.of("成功段"));
        GraphExtractor extractor = request -> {
            if (request.getText().equals("失败段")) throw new GraphExtractionException("bad response");
            return new GraphCandidateBatch(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "{}");
        };
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor, splitter,
            new SchemaGraphCandidateValidator(), new NameAliasGraphEntityResolver(), new GraphMutationMapper());

        GraphExtractionResult result = pipeline.extract(Document.of("full"), GraphExtractorTestSupport.schema(),
            GraphExtractionOptions.builder().failOnChunkError(false).build());

        assertTrue(result.hasErrors());
        assertEquals("CHUNK_EXTRACTION_FAILED", result.getIssues().get(0).getCode());
        assertEquals(2, result.getRawResponses().size());
    }

    /**
     * 预分段入口不得再次调用分段器，并应优先使用调用方提供的 Chunk ID。
     */
    @Test
    public void shouldProcessExistingChunksWithoutSplittingAgain() {
        List<String> chunkIds = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        GraphExtractor extractor = request -> {
            chunkIds.add(request.getChunkId());
            contexts.add(request.getContext());
            return new GraphCandidateBatch(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "{}");
        };
        DocumentSplitter forbiddenSplitter = (document, idGenerator) -> {
            throw new AssertionError("extractChunks must not invoke DocumentSplitter");
        };
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor, forbiddenSplitter,
            new SchemaGraphCandidateValidator(), new NameAliasGraphEntityResolver(), new GraphMutationMapper());
        Document first = Document.of("第一段");
        first.setId("chapter-1");
        Document second = Document.of("第二段");

        GraphExtractionResult result = pipeline.extractChunks(Arrays.asList(first, second),
            GraphExtractorTestSupport.schema());

        assertEquals(Arrays.asList("chapter-1", "chunk-1"), chunkIds);
        assertEquals("第一段", contexts.get(1));
        assertEquals(2, result.getRawResponses().size());
    }

    /**
     * 重复 Chunk ID 会破坏 mentionId 作用域，因此必须在任何模型调用前拒绝。
     */
    @Test
    public void shouldRejectDuplicateChunkIdsBeforeExtractionStarts() {
        final int[] calls = {0};
        GraphExtractor extractor = request -> {
            calls[0]++;
            return new GraphCandidateBatch(null, null, null, "{}");
        };
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor);
        Document first = Document.of("第一段");
        first.setId("duplicate");
        Document second = Document.of("第二段");
        second.setId("duplicate");

        try {
            pipeline.extractChunks(Arrays.asList(first, second), GraphExtractorTestSupport.schema());
            fail("duplicate chunk ids should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("duplicate chunk id"));
        }
        assertEquals(0, calls[0]);
    }

    /**
     * 预分段入口应允许显式父文档 ID，并把它传递给每个请求和证据生成逻辑。
     */
    @Test
    public void shouldPreserveParentDocumentIdForExistingChunks() {
        List<String> documentIds = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        GraphExtractor extractor = request -> {
            documentIds.add(request.getDocumentId());
            chunkIds.add(request.getChunkId());
            return new GraphCandidateBatch(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "{}");
        };
        Document explicit = Document.of("第一段");
        explicit.setId("chapter-1");
        Document generated = Document.of("第二段");

        new GraphExtractionPipeline(extractor).extractChunks(Arrays.asList(explicit, generated),
            "novel-1", GraphExtractorTestSupport.schema());

        assertEquals(Arrays.asList("novel-1", "novel-1"), documentIds);
        assertEquals(Arrays.asList("chapter-1", "novel-1#chunk-1"), chunkIds);
    }

    /**
     * 允许继续时，自定义校验器的单 Chunk 异常应结构化记录并保留原始响应。
     */
    @Test
    public void shouldContinueAfterValidatorFailureWhenConfigured() {
        GraphExtractor extractor = request -> new GraphCandidateBatch(Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), "raw-response");
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor,
            (document, idGenerator) -> Collections.singletonList(Document.of("第一段")),
            (batch, request) -> {
                throw new GraphExtractionException("validator unavailable");
            },
            new NameAliasGraphEntityResolver(), new GraphMutationMapper());

        GraphExtractionResult result = pipeline.extract(Document.of("全文"), GraphExtractorTestSupport.schema(),
            GraphExtractionOptions.builder().failOnChunkError(false).build());

        assertTrue(result.hasErrors());
        assertEquals("CHUNK_VALIDATION_FAILED", result.getIssues().get(0).getCode());
        assertEquals("raw-response", result.getRawResponses().get(0));
    }

    private static Document chunk(Document source, String text) {
        Document result = Document.of(text);
        result.setTitle(source.getTitle());
        result.putMetadata(source.getMetadataMap());
        return result;
    }

    /**
     * 根据测试分段生成确定候选，并记录流水线提供的上下文。
     */
    private static final class RecordingExtractor implements GraphExtractor {
        private final List<String> contexts = new ArrayList<>();

        @Override
        public GraphCandidateBatch extract(GraphExtractionRequest request) {
            contexts.add(request.getContext());
            if (request.getText().startsWith("林默")) {
                GraphEntityCandidate person = entity(request, "m1", "林默", "Character",
                    Collections.singletonList("林公子"));
                return new GraphCandidateBatch(Collections.singletonList(person), Collections.emptyList(),
                    Collections.emptyList(), "chunk-1-response");
            }
            GraphEntityCandidate person = entity(request, "m1", "林公子", "Character", Collections.emptyList());
            GraphEntityCandidate organization = entity(request, "m2", "青云宗", "Organization", Collections.emptyList());
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("chapter", 1L);
            GraphRelationCandidate relation = new GraphRelationCandidate(person.getCandidateKey(), "MEMBER_OF",
                organization.getCandidateKey(), 0L, properties,
                GraphExtractorTestSupport.evidence(request.getChunkId(), "加入青云宗"), 1D,
                GraphAssertionType.EXPLICIT);
            return new GraphCandidateBatch(Arrays.asList(person, organization), Collections.singletonList(relation),
                Collections.emptyList(), "chunk-2-response");
        }

        private static GraphEntityCandidate entity(GraphExtractionRequest request, String localId, String name,
                                                   String type, List<String> aliases) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name", name);
            return new GraphEntityCandidate(request.getChunkId() + "::" + localId, name, type, aliases, properties,
                GraphExtractorTestSupport.evidence(request.getChunkId(), name), 1D);
        }
    }
}
