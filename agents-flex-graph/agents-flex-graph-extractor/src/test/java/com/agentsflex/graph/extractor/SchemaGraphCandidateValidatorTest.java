package com.agentsflex.graph.extractor;

import com.agentsflex.graph.extractor.model.GraphAssertionType;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphEvidence;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.extractor.validation.GraphCandidateValidationResult;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SchemaGraphCandidateValidator 的类型、证据和质量策略测试。
 */
public class SchemaGraphCandidateValidatorTest {
    /**
     * 合法实体和明确关系应完整进入后续流水线。
     */
    @Test
    public void shouldAcceptSchemaCompatibleExplicitFacts() {
        GraphEntityCandidate person = entity("c::p", "林默", "Character", "林默", props("name", "林默"));
        GraphEntityCandidate organization = entity("c::o", "青云宗", "Organization", "青云宗", props("name", "青云宗"));
        GraphRelationCandidate relation = relation(person, organization, GraphAssertionType.EXPLICIT, "加入青云宗");
        GraphExtractionRequest request = request(GraphExtractionOptions.DEFAULT);

        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(
            new GraphCandidateResult(Arrays.asList(person, organization), Collections.singletonList(relation),
                Collections.emptyList(), "{}"), request);

        assertEquals(2, result.getAccepted().getEntities().size());
        assertEquals(1, result.getAccepted().getRelations().size());
        assertTrue(result.getIssues().isEmpty());
    }

    /**
     * 未知属性、伪造证据和错误端点均不得进入 GraphMutation。
     */
    @Test
    public void shouldRejectUnknownPropertiesFakeEvidenceAndWrongEndpoints() {
        GraphEntityCandidate unknownProperty = entity("c::x", "林默", "Character", "林默",
            props("unknown", "value"));
        GraphEntityCandidate fakeEvidence = entity("c::y", "苏青", "Character", "不存在的原文",
            props("name", "苏青"));
        GraphEntityCandidate organization = entity("c::o", "青云宗", "Organization", "青云宗",
            props("name", "青云宗"));
        GraphRelationCandidate wrongEndpoint = relation(organization, fakeEvidence, GraphAssertionType.EXPLICIT, "加入青云宗");

        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(
            new GraphCandidateResult(Arrays.asList(unknownProperty, fakeEvidence, organization),
                Collections.singletonList(wrongEndpoint), Collections.emptyList(), "{}"),
            request(GraphExtractionOptions.DEFAULT));

        assertEquals(1, result.getAccepted().getEntities().size());
        assertTrue(result.getAccepted().getRelations().isEmpty());
        assertEquals(3, result.getIssues().size());
    }

    /**
     * 推断关系默认拒绝，显式开启后才允许进入结果。
     */
    @Test
    public void inferredRelationsShouldRequireExplicitOptIn() {
        GraphEntityCandidate person = entity("c::p", "林默", "Character", "林默", props("name", "林默"));
        GraphEntityCandidate organization = entity("c::o", "青云宗", "Organization", "青云宗", props("name", "青云宗"));
        GraphRelationCandidate relation = relation(person, organization, GraphAssertionType.INFERRED, "加入青云宗");
        GraphCandidateResult batch = new GraphCandidateResult(Arrays.asList(person, organization),
            Collections.singletonList(relation), Collections.emptyList(), "{}");

        assertTrue(new SchemaGraphCandidateValidator().validate(batch, request(GraphExtractionOptions.DEFAULT))
            .getAccepted().getRelations().isEmpty());
        GraphExtractionOptions enabled = GraphExtractionOptions.builder().includeInferredRelations(true).build();
        assertEquals(1, new SchemaGraphCandidateValidator().validate(batch, request(enabled))
            .getAccepted().getRelations().size());
    }

    /**
     * 已知证据偏移必须在当前文本边界内，并精确对应 end-exclusive 子串。
     */
    @Test
    public void shouldValidateEvidenceOffsetsAgainstCurrentText() {
        Map<String, Object> properties = props("name", "林默");
        GraphEntityCandidate valid = new GraphEntityCandidate("c::valid", "林默", "Character",
            Collections.emptyList(), properties, evidence("林默", 0, 2), 1D);
        GraphEntityCandidate mismatched = new GraphEntityCandidate("c::mismatch", "林默", "Character",
            Collections.emptyList(), properties, evidence("林默", 0, 1), 1D);
        GraphEntityCandidate outOfBounds = new GraphEntityCandidate("c::bounds", "林默", "Character",
            Collections.emptyList(), properties, evidence("林默", 0, 99), 1D);

        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(
            new GraphCandidateResult(Arrays.asList(valid, mismatched, outOfBounds), Collections.emptyList(),
                Collections.emptyList(), "{}"), request(GraphExtractionOptions.DEFAULT));

        assertEquals(1, result.getAccepted().getEntities().size());
        assertEquals(2, result.getIssues().size());
        assertTrue(result.getIssues().get(0).getMessage().contains("offsets"));
        assertTrue(result.getIssues().get(1).getMessage().contains("offsets"));
    }

    /**
     * DATE、DATETIME 必须是合法 ISO-8601 值，Schema 枚举也必须由校验器强制执行。
     */
    @Test
    public void shouldValidateTemporalFormatsAndEnumValues() {
        GraphSchema.Property status = new GraphSchema.Property("status", GraphSchema.PropertyType.STRING, false,
            new GraphPropertyMetadata("状态", "人物当前状态", null, Arrays.asList("ACTIVE", "INACTIVE")));
        GraphSchema schema = GraphSchema.builder().nodeType(GraphSchema.NodeType.of("Character",
            new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
            new GraphSchema.Property("birthday", GraphSchema.PropertyType.DATE, false),
            new GraphSchema.Property("observedAt", GraphSchema.PropertyType.DATETIME, false), status)).build();
        GraphExtractionRequest request = GraphExtractionRequest.builder("林默、苏青和叶舟", schema, "c").build();

        Map<String, Object> validValues = props("name", "林默");
        validValues.put("birthday", "2008-03-01");
        validValues.put("observedAt", "2026-09-23T10:15:30");
        validValues.put("status", "ACTIVE");
        Map<String, Object> invalidDate = props("name", "苏青");
        invalidDate.put("birthday", "2008-99-01");
        Map<String, Object> invalidEnum = props("name", "叶舟");
        invalidEnum.put("status", "UNKNOWN");

        GraphCandidateResult batch = new GraphCandidateResult(Arrays.asList(
            entity("c::valid", "林默", "Character", "林默", validValues),
            entity("c::date", "苏青", "Character", "苏青", invalidDate),
            entity("c::enum", "叶舟", "Character", "叶舟", invalidEnum)),
            Collections.emptyList(), Collections.emptyList(), "{}");
        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(batch, request);

        assertEquals(1, result.getAccepted().getEntities().size());
        assertEquals(2, result.getIssues().size());
        assertTrue(result.getIssues().get(0).getMessage().contains("invalid value type"));
        assertTrue(result.getIssues().get(1).getMessage().contains("allowed enum"));
    }

    /**
     * 自定义抽取器不能伪造其他 Chunk 或文档的证据，也不能返回当前 Chunk 作用域外的候选键。
     */
    @Test
    public void shouldRejectForeignCandidateScopeAndEvidenceProvenance() {
        GraphExtractionRequest request = GraphExtractionRequest.builder("林默", GraphExtractorTestSupport.schema(), "c")
            .documentId("novel-1").build();
        GraphEntityCandidate foreignKey = new GraphEntityCandidate("other::m1", "林默", "Character",
            Collections.emptyList(), props("name", "林默"), evidence("林默", 0, 2), 1D);
        GraphEntityCandidate foreignChunk = new GraphEntityCandidate("c::m2", "林默", "Character",
            Collections.emptyList(), props("name", "林默"),
            new GraphEvidence("novel-1", "other", "林默", 0, 2, Collections.emptyMap()), 1D);
        GraphEntityCandidate foreignDocument = new GraphEntityCandidate("c::m3", "林默", "Character",
            Collections.emptyList(), props("name", "林默"),
            new GraphEvidence("novel-2", "c", "林默", 0, 2, Collections.emptyMap()), 1D);

        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(
            new GraphCandidateResult(Arrays.asList(foreignKey, foreignChunk, foreignDocument),
                Collections.emptyList(), Collections.emptyList(), "{}"), request);

        assertTrue(result.getAccepted().getEntities().isEmpty());
        assertEquals(3, result.getIssues().size());
        assertTrue(result.getIssues().get(0).getMessage().contains("chunk scope"));
        assertTrue(result.getIssues().get(1).getMessage().contains("chunk id"));
        assertTrue(result.getIssues().get(2).getMessage().contains("document id"));
    }

    /**
     * 图数据库属性不应接收 NaN 或无穷 DOUBLE 值。
     */
    @Test
    public void shouldRejectNonFiniteDoubleProperty() {
        GraphSchema schema = GraphSchema.builder().nodeType(GraphSchema.NodeType.of("Character",
            new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
            new GraphSchema.Property("score", GraphSchema.PropertyType.DOUBLE, false))).build();
        Map<String, Object> values = props("name", "林默");
        values.put("score", Double.NaN);
        GraphEntityCandidate candidate = new GraphEntityCandidate("c::m1", "林默", "Character",
            Collections.emptyList(), values, evidence("林默", 0, 2), 1D);

        GraphCandidateValidationResult result = new SchemaGraphCandidateValidator().validate(
            new GraphCandidateResult(Collections.singletonList(candidate), Collections.emptyList(),
                Collections.emptyList(), "{}"),
            GraphExtractionRequest.builder("林默", schema, "c").build());

        assertTrue(result.getAccepted().getEntities().isEmpty());
        assertTrue(result.getIssues().get(0).getMessage().contains("invalid value type"));
    }

    private static GraphExtractionRequest request(GraphExtractionOptions options) {
        return GraphExtractionRequest.builder("林默加入青云宗，苏青也在场", GraphExtractorTestSupport.schema(), "c")
            .options(options).build();
    }

    private static GraphEntityCandidate entity(String key, String name, String type, String quote,
                                               Map<String, Object> properties) {
        return new GraphEntityCandidate(key, name, type, Collections.emptyList(), properties,
            GraphExtractorTestSupport.evidence("c", quote), 0.95D);
    }

    private static GraphRelationCandidate relation(GraphEntityCandidate source, GraphEntityCandidate target,
                                                   GraphAssertionType assertion, String quote) {
        return new GraphRelationCandidate(source.getCandidateKey(), "MEMBER_OF", target.getCandidateKey(), 0L,
            props("chapter", 1L), GraphExtractorTestSupport.evidence("c", quote), 0.9D, assertion);
    }

    /**
     * 创建带精确字符偏移的当前 Chunk 证据。
     */
    private static GraphEvidence evidence(String quote, int start, int end) {
        return new GraphEvidence("novel-1", "c", quote, start, end,
            Collections.<String, Object>emptyMap());
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
