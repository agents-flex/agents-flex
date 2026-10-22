package com.agentsflex.core.model.client;

import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.OpenAICompatibleChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OpenAIResponsesChatClientTest {

    /**
     * Calls the real Responses API when OPENAI_RESPONSES_API_KEY is set.
     * OPENAI_RESPONSES_MODEL must name a model supported by the endpoint.
     */
    @Test
    public void callsRealResponsesApi() {
        String apiKey = System.getenv("OPENAI_RESPONSES_API_KEY");
        Assume.assumeTrue("OPENAI_RESPONSES_API_KEY is not set", StringUtil.hasText(apiKey));
        String model = System.getenv("OPENAI_RESPONSES_MODEL");
        assertTrue("OPENAI_RESPONSES_MODEL must be set for the live API test", StringUtil.hasText(model));

        BaseChatConfig config = new BaseChatConfig();
        config.setApiKey(apiKey);
        config.setModel(model);
        config.setLogEnabled(false);
        OpenAIResponsesChatClient client = new OpenAIResponsesChatClient(new OpenAICompatibleChatModel<>(config));
        Map<String, String> headers = Collections.singletonMap("Authorization", "Bearer " + apiKey);
        ChatRequestSpec request = new ChatRequestSpec(
            "https://open.bigmodel.cn/api/v1/responses", headers, 0, 0);
        String input = "Reply with exactly RESPONSES_OK";
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", input);
        body.put("stream", false);

        try (ChatContextHolder.ChatContextScope scope = ChatContextHolder.beginChat(
            new SimplePrompt(input), new ChatOptions(), request, config)) {
            AiMessageResponse response = client.chat(body.toJSONString());
            response.throwIfError();
            assertNotNull(response.getMessage());
            assertTrue(StringUtil.hasText(response.getMessage().getId()));
            assertEquals("RESPONSES_OK", response.getMessage().getTextContent().trim());
            assertNotNull(response.getMessage().getPromptTokens());
            assertNotNull(response.getMessage().getCompletionTokens());
        }
    }

    @Test
    public void parsesSuccessfulResponse() {
        AiMessageResponse response = chat("{\"id\":\"resp_1\",\"type\":\"response\","
            + "\"output_text\":\"Hello\",\"usage\":{\"input_tokens\":3,"
            + "\"output_tokens\":2,\"total_tokens\":5}}");

        assertFalse(response.isError());
        assertEquals("resp_1", response.getMessage().getId());
        assertEquals("Hello", response.getMessage().getContent());
        assertEquals(Integer.valueOf(5), response.getMessage().getTotalTokens());
    }

    @Test
    public void preservesErrorDetails() {
        AiMessageResponse response = chat("{\"error\":{\"message\":\"Invalid request\","
            + "\"type\":\"invalid_request_error\",\"code\":\"invalid_parameter\"}}");

        assertTrue(response.isError());
        assertEquals("Invalid request", response.getErrorMessage());
        assertEquals("invalid_request_error", response.getErrorType());
        assertEquals("invalid_parameter", response.getErrorCode());
    }

    @Test
    public void rejectsInvalidJsonResponse() {
        AiMessageResponse response = chat("not json");

        assertTrue(response.isError());
        assertEquals("invalid json response.", response.getErrorMessage());
    }

    @Test
    public void rejectsEmptyResponse() {
        AiMessageResponse response = chat("");

        assertTrue(response.isError());
        assertEquals("no content for response.", response.getErrorMessage());
    }

    private AiMessageResponse chat(String responseBody) {
        BaseChatConfig config = new BaseChatConfig();
        OpenAIResponsesChatClient client = new OpenAIResponsesChatClient(new OpenAICompatibleChatModel<>(config));
        Map<String, String> headers = Collections.singletonMap("Authorization", "Bearer test-key");
        String requestUrl = "https://example.com/v1/responses";
        String requestBody = "{\"input\":\"Hello\"}";
        client.setHttpClient(new AgentsFlexHttpClient() {
            @Override
            public String post(String url, Map<String, String> requestHeaders, String payload) {
                assertEquals(requestUrl, url);
                assertEquals(headers, requestHeaders);
                assertEquals(requestBody, payload);
                return responseBody;
            }
        });
        ChatRequestSpec request = new ChatRequestSpec(requestUrl, headers, 0, 0);

        try (ChatContextHolder.ChatContextScope scope = ChatContextHolder.beginChat(
            new SimplePrompt("Hello"), new ChatOptions(), request, config)) {
            return client.chat(requestBody);
        }
    }
}
