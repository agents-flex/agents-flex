/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatOptions;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DefaultAiMessageParserTest {

    private static final String RESPONSE = "{\n" +
        "  \"id\": \"chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT\",\n" +
        "  \"object\": \"chat.completion\",\n" +
        "  \"created\": 1741569952,\n" +
        "  \"model\": \"gpt-5.4\",\n" +
        "  \"choices\": [{\n" +
        "    \"index\": 0,\n" +
        "    \"message\": {\"role\": \"assistant\", \"content\": \"Hello! How can I assist you today?\",\n" +
        "      \"refusal\": null, \"annotations\": [{\"type\": \"url_citation\", \"url\": \"https://example.com\"}]},\n" +
        "    \"logprobs\": {\"content\": [{\"token\": \"Hello\", \"logprob\": -0.1}]},\n" +
        "    \"finish_reason\": \"stop\"\n" +
        "  }],\n" +
        "  \"usage\": {\n" +
        "    \"prompt_tokens\": 19, \"completion_tokens\": 10, \"total_tokens\": 29,\n" +
        "    \"prompt_tokens_details\": {\"cached_tokens\": 0, \"audio_tokens\": 0},\n" +
        "    \"completion_tokens_details\": {\"reasoning_tokens\": 0, \"audio_tokens\": 0,\n" +
        "      \"accepted_prediction_tokens\": 0, \"rejected_prediction_tokens\": 0}\n" +
        "  },\n" +
        "  \"service_tier\": \"default\", \"system_fingerprint\": \"fp_test\"\n" +
        "}";

    @Test
    public void parseOpenAIResponseRetainsResponseMetadata() {
        AiMessage message = DefaultAiMessageParser.getOpenAIMessageParser()
            .parse(JSON.parseObject(RESPONSE), context(false));

        assertEquals("chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT", message.getId());
        assertEquals("chat.completion", message.getObject());
        assertEquals(Long.valueOf(1741569952L), message.getCreated());
        assertEquals("gpt-5.4", message.getModel());
        assertEquals("default", message.getServiceTier());
        assertEquals("fp_test", message.getSystemFingerprint());
        assertEquals("assistant", message.getRole());
        assertNull(message.getRefusal());
        assertEquals("Hello! How can I assist you today?", message.getContent());
        assertEquals(message.getContent(), message.getFullContent());
        assertEquals(message.getContent(), message.getTextContent());
        assertEquals("stop", message.getFinishReason());
        assertEquals(Integer.valueOf(19), message.getPromptTokens());
        assertEquals(Integer.valueOf(10), message.getCompletionTokens());
        assertEquals(Integer.valueOf(29), message.getTotalTokens());

        Map<?, ?> annotation = (Map<?, ?>) message.getAnnotations().get(0);
        assertEquals("url_citation", annotation.get("type"));
        assertEquals("Hello", ((Map<?, ?>) ((List<?>) message.getLogprobs().get("content")).get(0)).get("token"));
        assertEquals(0, ((Number) message.getPromptTokensDetails().get("cached_tokens")).intValue());
        assertEquals(0, ((Number) message.getCompletionTokensDetails().get("reasoning_tokens")).intValue());
        assertEquals(0, ((Number) message.getCompletionTokensDetails().get("accepted_prediction_tokens")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mergeAndCopyRetainIndependentResponseMetadata() {
        DefaultAiMessageParser parser = DefaultAiMessageParser.getOpenAIMessageParser();
        AiMessage full = new AiMessage();
        full.merge(parser.parse(streamChunk("Hel", "assistant", "A"), context(true)));
        full.merge(parser.parse(streamChunk("lo", null, "B"), context(true)));

        assertEquals("Hello", full.getFullContent());
        assertEquals("assistant", full.getRole());
        assertEquals(2, ((List<?>) full.getLogprobs().get("content")).size());

        AiMessage copy = full.copy();
        ((Map<String, Object>) ((List<?>) copy.getLogprobs().get("content")).get(0)).put("token", "changed");
        assertEquals("A", ((Map<?, ?>) ((List<?>) full.getLogprobs().get("content")).get(0)).get("token"));
    }

    @Test
    public void contentConstructorInitializesCurrentAndFullContent() {
        AiMessage message = new AiMessage("hello");

        assertEquals("hello", message.getContent());
        assertEquals("hello", message.getFullContent());
        assertEquals("hello", message.getTextContent());
    }

    @Test
    public void responseMetadataSurvivesJsonRoundTrip() {
        AiMessage parsed = DefaultAiMessageParser.getOpenAIMessageParser()
            .parse(JSON.parseObject(RESPONSE), context(false));

        AiMessage restored = JSON.parseObject(JSON.toJSONString(parsed), AiMessage.class);

        assertEquals(parsed.getId(), restored.getId());
        assertEquals(parsed.getCreated(), restored.getCreated());
        assertEquals(parsed.getModel(), restored.getModel());
        assertEquals(parsed.getAnnotations(), restored.getAnnotations());
        assertEquals(parsed.getLogprobs(), restored.getLogprobs());
        assertEquals(parsed.getPromptTokensDetails(), restored.getPromptTokensDetails());
        assertEquals(parsed.getCompletionTokensDetails(), restored.getCompletionTokensDetails());
    }

    private static JSONObject streamChunk(String content, String role, String token) {
        JSONObject delta = new JSONObject();
        delta.put("content", content);
        if (role != null) delta.put("role", role);

        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("logprobs", JSON.parseObject("{\"content\":[{\"token\":\"" + token + "\"}]}"));

        JSONObject chunk = new JSONObject();
        chunk.put("id", "chatcmpl-stream");
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", 1741569952);
        chunk.put("model", "gpt-5.4");
        chunk.put("choices", JSON.parseArray("[]"));
        chunk.getJSONArray("choices").add(choice);
        return chunk;
    }

    private static ChatContext context(boolean streaming) {
        ChatOptions options = new ChatOptions();
        options.setStreaming(streaming);
        ChatContext context = new ChatContext();
        context.setOptions(options);
        return context;
    }
}
