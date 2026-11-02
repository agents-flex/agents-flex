package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class OpenAIResponsesParserTest {

    @Test
    public void separatesReasoningFromFinalAnswer() {
        JSONObject response = JSON.parseObject("{\"object\":\"response\",\"output\":["
            + "{\"type\":\"reasoning\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"Thinking.\"}],"
            + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Summary.\"}]},"
            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"RESPONSES_OK\"},"
            + "{\"type\":\"unknown\",\"text\":\"Ignore this.\"}]}]}");

        AiMessage message = new OpenAIResponsesParser().parse(response, new ChatContext());

        assertEquals("RESPONSES_OK", message.getTextContent());
        assertEquals("Thinking.", message.getReasoningContent());
    }

    @Test
    public void preservesReasoningSummaryWithTopLevelOutputText() {
        JSONObject response = JSON.parseObject("{\"type\":\"response\",\"output_text\":\"Done\",\"output\":["
            + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"First.\"}]},"
            + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Second.\"}]}]}");

        AiMessage message = new OpenAIResponsesParser().parse(response, new ChatContext());

        assertEquals("Done", message.getTextContent());
        assertEquals("First.Second.", message.getReasoningContent());
    }

    @Test
    public void keepsStructuredAnswerWithoutDuplicatingTopLevelOutputText() {
        JSONObject response = JSON.parseObject("{\"type\":\"response\",\"output_text\":\"Done\",\"output\":["
            + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done\"}]}]}");

        AiMessage message = new OpenAIResponsesParser().parse(response, new ChatContext());

        assertEquals("Done", message.getTextContent());
    }

    @Test
    public void separatesStreamingReasoningFromAnswer() {
        for (String type : new String[]{"response.reasoning_text.delta", "response.reasoning_summary_text.delta"}) {
            OpenAIResponsesParser parser = new OpenAIResponsesParser();
            ChatContext context = new ChatContext();
            AiMessage reasoning = parser.parse(JSON.parseObject("{\"type\":\"" + type
                + "\",\"delta\":\"Thinking.\"}"), context);
            assertNull(reasoning.getTextContent());
            assertEquals("Thinking.", reasoning.getReasoningContent());

            AiMessage full = new AiMessage();
            full.merge(reasoning);
            full.merge(parser.parse(JSON.parseObject("{\"type\":\"response.output_text.delta\","
                + "\"delta\":\"Done\"}"), context));
            assertEquals("Done", full.getTextContent());
            assertEquals("Thinking.", full.getReasoningContent());
        }
    }

    @Test
    public void parsesResponseOutputAndUsageDetails() {
        JSONObject response = JSON.parseObject("{"
            + "\"id\":\"resp_1\",\"type\":\"response\","
            + "\"output_text\":\"已完成\","
            + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\"}],"
            + "\"usage\":{\"input_tokens\":20,\"output_tokens\":5,\"total_tokens\":25,"
            + "\"input_tokens_details\":{\"cached_tokens\":12}}}");

        AiMessage message = new OpenAIResponsesParser().parse(response, new ChatContext());
        assertEquals("resp_1", message.getId());
        assertEquals("已完成", message.getContent());
        assertEquals(Integer.valueOf(20), message.getPromptTokens());
        assertEquals(Integer.valueOf(5), message.getCompletionTokens());
        assertEquals(12, ((Number) message.getPromptTokensDetails().get("cached_tokens")).intValue());
    }

    @Test
    public void parsesStreamingTextAndFunctionArguments() {
        OpenAIResponsesParser parser = new OpenAIResponsesParser();
        ChatContext context = new ChatContext();
        AiMessage text = parser.parse(JSON.parseObject("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}"), context);
        AiMessage call = parser.parse(JSON.parseObject("{\"type\":\"response.function_call_arguments.delta\","
            + "\"item_id\":\"call_1\",\"delta\":\"{\\\"id\\\":\"}"), context);

        assertEquals("Hi", text.getContent());
        assertEquals("call_1", call.getToolCalls().get(0).getId());
        assertEquals("{\"id\":", call.getToolCalls().get(0).getArguments());
    }

    @Test
    public void completedEventCarriesUsageWithoutRepeatingOutput() {
        AiMessage message = new OpenAIResponsesParser().parse(JSON.parseObject("{"
            + "\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\","
            + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"must not repeat\"}]}],\"usage\":{\"input_tokens\":3,"
            + "\"output_tokens\":2,\"total_tokens\":5}}}"), new ChatContext());

        assertNull(message.getContent());
        assertTrue(message.isFinalDelta());
        assertEquals(Integer.valueOf(5), message.getTotalTokens());
    }
}
