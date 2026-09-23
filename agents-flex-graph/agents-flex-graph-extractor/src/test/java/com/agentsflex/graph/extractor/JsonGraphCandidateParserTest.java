package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.parser.JsonGraphCandidateParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JsonGraphCandidateParser 的结构化响应与作用域测试。
 */
public class JsonGraphCandidateParserTest {
    /**
     * 验证 Markdown 围栏、局部 mentionId、属性补齐和关系解析。
     */
    @Test
    public void shouldParseFencedResponseAndScopeMentionIds() {
        String response = "```json\n{\"entities\":["
            + "{\"mentionId\":\"m1\",\"name\":\"林默\",\"type\":\"Character\",\"aliases\":[\"林公子\"],"
            + "\"properties\":{\"age\":18},\"evidence\":\"林默加入青云宗\",\"confidence\":0.98},"
            + "{\"mentionId\":\"m2\",\"name\":\"青云宗\",\"type\":\"Organization\",\"properties\":{},"
            + "\"evidence\":\"青云宗\",\"confidence\":0.99}],"
            + "\"relations\":[{\"sourceMentionId\":\"m1\",\"type\":\"MEMBER_OF\",\"targetMentionId\":\"m2\","
            + "\"properties\":{\"chapter\":1},\"evidence\":\"加入青云宗\",\"confidence\":0.95,"
            + "\"assertionType\":\"EXPLICIT\"}]}\n```";
        GraphExtractionRequest request = GraphExtractionRequest.builder("林默加入青云宗", GraphExtractorTestSupport.schema(), "c1")
            .documentId("novel-1").build();

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response, request);

        assertEquals(2, batch.getEntities().size());
        assertEquals("c1::m1", batch.getEntities().get(0).getCandidateKey());
        assertEquals("林默", batch.getEntities().get(0).getProperties().get("name"));
        assertEquals(Long.valueOf(18L), Long.valueOf(((Number) batch.getEntities().get(0).getProperties().get("age")).longValue()));
        assertEquals("c1::m2", batch.getRelations().get(0).getTargetCandidateKey());
        assertEquals(GraphAssertionType.EXPLICIT, batch.getRelations().get(0).getAssertionType());
        assertTrue(batch.getIssues().isEmpty());
    }

    /**
     * 模型只返回一个偏移时应安全降级为未知偏移。
     */
    @Test
    public void shouldNormalizeIncompleteOffsets() {
        String response = "{\"entities\":[{\"mentionId\":\"m1\",\"name\":\"林默\",\"type\":\"Character\","
            + "\"properties\":{},\"evidence\":\"林默\",\"startOffset\":0,\"confidence\":1}],\"relations\":[]}";
        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());
        assertEquals(-1, batch.getEntities().get(0).getEvidence().getStartOffset());
        assertEquals(-1, batch.getEntities().get(0).getEvidence().getEndOffset());
    }

    /**
     * 一个损坏实体不应导致同一响应中的合法实体和关系全部丢失。
     */
    @Test
    public void shouldKeepValidCandidatesWhenOneEntityIsMalformed() {
        String response = "{\"entities\":["
            + "{\"mentionId\":\"broken\",\"type\":\"Character\"},"
            + "{\"mentionId\":\"m1\",\"name\":\"林默\",\"type\":\"Character\","
            + "\"properties\":{},\"evidence\":\"林默\",\"confidence\":1}],"
            + "\"relations\":[]}";

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "chapter-1").build());

        assertEquals(1, batch.getEntities().size());
        assertEquals("chapter-1::m1", batch.getEntities().get(0).getCandidateKey());
        assertEquals(1, batch.getIssues().size());
        assertEquals("MALFORMED_ENTITY", batch.getIssues().get(0).getCode());
        assertEquals("chapter-1::entities[0]", batch.getIssues().get(0).getCandidateKey());
    }

    /**
     * 非法 assertionType 应只拒绝当前关系，并保留同批次中的合法关系。
     */
    @Test
    public void shouldReportInvalidAssertionTypeWithoutDroppingOtherRelations() {
        String response = "{\"entities\":[],\"relations\":["
            + "{\"sourceMentionId\":\"m1\",\"type\":\"MEMBER_OF\",\"targetMentionId\":\"m2\","
            + "\"evidence\":\"加入\",\"confidence\":1,\"assertionType\":\"UNKNOWN\"},"
            + "{\"sourceMentionId\":\"m1\",\"type\":\"MEMBER_OF\",\"targetMentionId\":\"m2\","
            + "\"evidence\":\"加入\",\"confidence\":1,\"assertionType\":\"EXPLICIT\"}]}";

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默加入青云宗", GraphExtractorTestSupport.schema(), "c1").build());

        assertEquals(1, batch.getRelations().size());
        assertEquals(GraphAssertionType.EXPLICIT, batch.getRelations().get(0).getAssertionType());
        assertEquals(1, batch.getIssues().size());
        assertEquals("MALFORMED_RELATION", batch.getIssues().get(0).getCode());
    }

    /**
     * JSON 根对象损坏时无法建立候选边界，必须让整个 Chunk 显式失败。
     */
    @Test(expected = GraphExtractionException.class)
    public void shouldRejectMalformedRootJson() {
        new JsonGraphCandidateParser().parse("prefix {\"entities\":[} suffix",
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());
    }

    /**
     * JSON 前后说明即使包含花括号，也应定位真正包含协议数组的根对象。
     */
    @Test
    public void shouldLocateProtocolObjectAmongExplanatoryBraces() {
        String response = "说明 {not-json} 前缀 {\"entities\":[],\"relations\":[]} 后缀 {done}";

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());

        assertTrue(batch.getEntities().isEmpty());
        assertTrue(batch.getRelations().isEmpty());
    }

    /**
     * 缺少协议数组不能被解释为空抽取结果，否则模型协议漂移会造成静默数据丢失。
     */
    @Test(expected = GraphExtractionException.class)
    public void shouldRejectRootWithoutProtocolArrays() {
        new JsonGraphCandidateParser().parse("{\"message\":\"no result\"}",
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());
    }

    /**
     * 缺失 confidence 的候选应记录格式问题，同时保留后续完整候选。
     */
    @Test
    public void shouldTreatMissingConfidenceAsMalformedCandidate() {
        String response = "{\"entities\":["
            + "{\"mentionId\":\"m1\",\"name\":\"坏候选\",\"type\":\"Character\",\"properties\":{}},"
            + "{\"mentionId\":\"m2\",\"name\":\"林默\",\"type\":\"Character\",\"properties\":{},"
            + "\"evidence\":\"林默\",\"confidence\":1}],\"relations\":[]}";

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());

        assertEquals(1, batch.getEntities().size());
        assertEquals("MALFORMED_ENTITY", batch.getIssues().get(0).getCode());
    }

    /**
     * 两个完整但倒置的证据偏移应拒绝当前候选，不能降级成未知偏移掩盖模型错误。
     */
    @Test
    public void shouldRejectReversedCompleteOffsets() {
        String response = "{\"entities\":[{\"mentionId\":\"m1\",\"name\":\"林默\","
            + "\"type\":\"Character\",\"properties\":{},\"evidence\":\"林默\","
            + "\"startOffset\":2,\"endOffset\":0,\"confidence\":1}],\"relations\":[]}";

        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse(response,
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1").build());

        assertTrue(batch.getEntities().isEmpty());
        assertEquals("MALFORMED_ENTITY", batch.getIssues().get(0).getCode());
    }

    /**
     * 超过配置字符上限的响应应在 JSON 扫描前立即失败。
     */
    @Test(expected = GraphExtractionException.class)
    public void shouldRejectResponseAboveCharacterLimit() {
        GraphExtractionOptions options = GraphExtractionOptions.builder().maxResponseCharacters(10).build();
        new JsonGraphCandidateParser().parse("{\"entities\":[],\"relations\":[]}",
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1")
                .options(options).build());
    }

    /**
     * 大量未闭合花括号也必须在受限扫描预算内快速失败，不能退化为平方级遍历。
     */
    @Test(timeout = 2000L, expected = GraphExtractionException.class)
    public void shouldBoundJsonObjectScanningWork() {
        StringBuilder response = new StringBuilder(20_000);
        for (int i = 0; i < 20_000; i++) response.append('{');
        GraphExtractionOptions options = GraphExtractionOptions.builder().maxResponseCharacters(25_000).build();
        new JsonGraphCandidateParser().parse(response.toString(),
            GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c1")
                .options(options).build());
    }

    /**
     * 返回集合必须只读，防止后续阶段篡改解析快照。
     */
    @Test(expected = UnsupportedOperationException.class)
    public void parsedCandidatesShouldBeImmutable() {
        GraphCandidateBatch batch = new JsonGraphCandidateParser().parse("{\"entities\":[],\"relations\":[]}",
            GraphExtractionRequest.builder("text", GraphExtractorTestSupport.schema(), "c1").build());
        batch.getEntities().clear();
    }
}
