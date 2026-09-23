/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.graph.extractor;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.model.GraphRelationCandidate;
import com.agentsflex.graph.schema.GraphSchema;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import org.junit.Assume;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 使用真实 DeepSeek 服务验证知识图谱抽取的端到端协议。
 *
 * <p>该测试只从 {@code DEEPSEEK_API_KEY} 环境变量读取密钥；未配置密钥时自动跳过，绝不把
 * 密钥写入源码、测试资源或断言信息。测试覆盖真实模型调用、严格 JSON 解析、Schema 校验、
 * 证据归属、实体归一以及 GraphMutation 映射，但不会把结果写入任何图数据库。</p>
 */
public class DeepseekGraphExtractorIntegrationTest {
    /**
     * 父文档 ID，用于验证真实模型候选经过流水线后仍保留来源链路。
     */
    private static final String DOCUMENT_ID = "deepseek-novel-1";
    /**
     * 当前分段 ID，用于验证 mentionId 作用域与证据分段归属。
     */
    private static final String CHUNK_ID = "deepseek-novel-1#chapter-1";
    /**
     * 完全显式且没有代词歧义的合成小说片段。
     *
     * <p>同一个组织在两句话中重复出现，可同时验证模型事实抽取和 SDK 的实体归一能力。</p>
     */
    private static final String NOVEL_TEXT = "林默是青云会成员。苏瑶也是青云会成员。";

    /**
     * 真实调用 DeepSeek，并通过完整流水线验证可提交但尚未持久化的图变更。
     */
    @Test
    public void shouldExtractSchemaConstrainedGraphWithRealDeepseek() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assume.assumeTrue("DEEPSEEK_API_KEY is required for integration test",
            apiKey != null && !apiKey.trim().isEmpty());

        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        LlmGraphExtractor extractor = new LlmGraphExtractor(new DeepseekChatModel(config))
            .setChatOptions(ChatOptions.builder()
                .temperature(0.0F)
                .maxTokens(2_000)
                .thinkingEnabled(false)
                // DeepSeek 原生支持 JSON Object 模式；提示词中也已经明确出现 JSON 协议要求。
                .responseFormatToJsonObject()
                .build());
        GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor);
        Document chunk = Document.of(NOVEL_TEXT);
        chunk.setId(CHUNK_ID);

        GraphExtractionResult result = pipeline.extractChunks(
            Arrays.asList(chunk), DOCUMENT_ID, schema(), GraphExtractionOptions.builder()
                .minConfidence(0.5D)
                .requireEvidence(true)
                .build());

        String diagnostic = "DeepSeek raw response: " + result.getRawResponses();
        assertFalse(diagnostic, result.hasErrors());
        assertEquals(diagnostic, 3, result.getResolution().getNodes().size());
        assertEquals(diagnostic, 2, result.getRelations().size());
        assertEquals(diagnostic, 3, result.getMutation().getNodes().size());
        assertEquals(diagnostic, 2, result.getMutation().getEdges().size());

        Set<String> entityNames = new HashSet<>();
        for (GraphEntityCandidate entity : result.getEntities()) {
            entityNames.add(entity.getName());
            assertEquals(DOCUMENT_ID, entity.getEvidence().getDocumentId());
            assertEquals(CHUNK_ID, entity.getEvidence().getChunkId());
            assertTrue(NOVEL_TEXT.contains(entity.getEvidence().getQuote()));
            assertTrue(entity.getCandidateKey().startsWith(CHUNK_ID + "::"));
        }
        assertTrue(diagnostic, entityNames.contains("林默"));
        assertTrue(diagnostic, entityNames.contains("苏瑶"));
        assertTrue(diagnostic, entityNames.contains("青云会"));

        for (GraphRelationCandidate relation : result.getRelations()) {
            assertEquals("MEMBER_OF", relation.getType());
            assertEquals(DOCUMENT_ID, relation.getEvidence().getDocumentId());
            assertEquals(CHUNK_ID, relation.getEvidence().getChunkId());
            assertTrue(NOVEL_TEXT.contains(relation.getEvidence().getQuote()));
        }
    }

    /**
     * 创建本次真实抽取允许使用的最小 Schema，未知节点、关系和属性都应被流水线拒绝。
     */
    private static GraphSchema schema() {
        GraphSchema.Property name = new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true);
        return GraphSchema.builder()
            .nodeType(GraphSchema.NodeType.of("Character", name))
            .nodeType(GraphSchema.NodeType.of("Organization", name))
            .edgeType(GraphSchema.EdgeType.of("MEMBER_OF", "Character", "Organization"))
            .build();
    }
}
