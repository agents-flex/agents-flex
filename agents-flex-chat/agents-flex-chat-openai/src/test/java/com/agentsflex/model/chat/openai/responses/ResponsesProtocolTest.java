/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.*;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.client.*;
import com.agentsflex.core.prompt.*;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ResponsesProtocolTest {
    private final OpenAIResponsesChatConfig config = new OpenAIResponsesChatConfig();
    private final ResponsesRequestSpecBuilder builder = new ResponsesRequestSpecBuilder();
    private final ResponsesResponseParser parser = new ResponsesResponseParser();

    private static JSONObject completed(Object... output) {
        return JSON.parseObject(JSON.toJSONString(Maps.of("id", "resp_1").set("object", "response")
            .set("status", "completed").set("model", "test-model").set("created_at", 123)
            .set("output", Arrays.asList(output))));
    }

    private static Object message(String text) {
        return Maps.of("type", "message").set("role", "assistant").set("content",
            Collections.singletonList(Maps.of("type", "output_text").set("text", text)));
    }

    private static Object call(String id, String args) {
        return Maps.of("type", "function_call").set("id", "item_" + id).set("call_id", id)
            .set("name", "weather").set("arguments", args);
    }

    @Test
    public void mapsRequestAndDoesNotSendChatCompletionFields() {
        config.setApiKey("test-key");
        ChatOptions options = ChatOptions.builder().maxTokens(100).responseFormatToJsonObject().build();
        SimplePrompt prompt = new SimplePrompt("hello");
        JSONObject body = JSON.parseObject(builder.buildRequestBody(prompt, options, config, true));
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"));
        assertEquals(100, body.getIntValue("max_output_tokens"));
        assertTrue(body.getBooleanValue("stream"));
        assertFalse(body.getBooleanValue("store"));
        assertEquals("json_object", body.getJSONObject("text").getJSONObject("format").getString("type"));
        for (String key : Arrays.asList("messages", "max_tokens", "response_format", "stream_options")) assertFalse(body.containsKey(key));
        ChatRequestSpec spec = builder.buildRequestSpec(prompt, options, config);
        assertEquals("https://api.openai.com/v1/responses", spec.getUrl());
        assertEquals("Bearer test-key", spec.getHeaders().get("Authorization"));
        assertFalse(JSON.parseObject(builder.buildRequestBody(prompt, options, config)).containsKey("stream"));
    }

    @Test
    public void flattensJsonSchemaWithoutMutatingOptions() {
        Map<String, Object> schema = Maps.of("name", "answer").set("strict", true)
            .set("schema", Maps.of("type", "object"));
        ChatOptions options = ChatOptions.builder().responseFormatToJsonSchema(schema).build();
        JSONObject body = JSON.parseObject(builder.buildRequestBody(new SimplePrompt("hello"), options, config));
        JSONObject format = body.getJSONObject("text").getJSONObject("format");
        assertEquals("answer", format.getString("name"));
        assertEquals("json_schema", format.getString("type"));
        assertFalse(format.containsKey("json_schema"));
        assertTrue(options.getResponseFormat().containsKey("json_schema"));
    }

    @Test
    public void roundTripsFunctionCallsAndEncryptedReasoningThroughCopiedHistory() {
        Object reasoning = Maps.of("type", "reasoning").set("id", "rs_1")
            .set("encrypted_content", "opaque").set("summary", Collections.emptyList());
        AiMessage ai = parser.parse(completed(reasoning, call("call_a", "{}"), call("call_b", "{}")).toJSONString(), null).getMessage();
        assertEquals("call_a", ai.getToolCalls().get(0).getId());
        assertEquals(2, ai.getToolCalls().size());
        SimplePrompt initial = new SimplePrompt("weather?");
        initial.addTool(new Tool() {
            public String getName() { return "weather"; }
            public String getDescription() { return "Weather"; }
            public Parameter[] getParameters() { return new Parameter[0]; }
            public Object invoke(Map<String, Object> args) { return "sunny"; }
        });
        initial.setToolChoice("required");
        ChatContext context = new ChatContext();
        context.setPrompt(initial);
        List<ToolMessage> results = new AiMessageResponse(context, "", ai).executeToolCallsAndGetToolMessages();
        assertEquals("call_b", results.get(1).getToolCallId());
        List<Message> history = new ArrayList<>(initial.getMessages());
        history.add(ai.copy());
        history.addAll(results);
        Prompt next = new Prompt() { public List<Message> getMessages() { return history; } };
        next.addTools(initial.getTools());
        JSONObject body = JSON.parseObject(builder.buildRequestBody(next, new ChatOptions(), config));
        JSONArray input = body.getJSONArray("input");
        assertEquals("opaque", input.getJSONObject(1).getString("encrypted_content"));
        assertEquals("function_call", input.getJSONObject(2).getString("type"));
        assertEquals("call_a", input.getJSONObject(4).getString("call_id"));
        assertEquals("sunny", input.getJSONObject(4).getString("output"));
        JSONObject tool = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("weather", tool.getString("name"));
        assertFalse(tool.containsKey("function"));
        assertFalse(tool.getBooleanValue("strict"));
    }

    @Test
    public void parsesAllTextPartsUsageAndRefusal() {
        JSONObject response = completed(message("hello"), message(" world"));
        response.put("usage", Maps.of("input_tokens", 10).set("output_tokens", 3).set("total_tokens", 13)
            .set("input_tokens_details", Maps.of("cached_tokens", 2)));
        AiMessage ai = parser.parse(response.toJSONString(), null).getMessage();
        assertEquals("hello world", ai.getContent());
        assertEquals(Integer.valueOf(13), ai.getTotalTokens());
        assertEquals(2, ai.getPromptTokensDetails().get("cached_tokens"));
        assertEquals("resp_1", ai.getId());
        JSONObject refused = completed(Maps.of("type", "message").set("content",
            Collections.singletonList(Maps.of("type", "refusal").set("refusal", "declined"))));
        assertEquals("declined", parser.parse(refused.toJSONString(), null).getMessage().getRefusal());
    }

    @Test
    public void incompleteResponseDoesNotExecutePartialTools() {
        JSONObject response = completed(message("partial"), call("call_a", "{"));
        response.put("status", "incomplete");
        response.put("incomplete_details", Maps.of("reason", "max_output_tokens"));
        AiMessageResponse parsed = parser.parse(response.toJSONString(), null);
        assertFalse(parsed.hasToolCalls());
        assertEquals("length", parsed.getMessage().getFinishReason());
        assertEquals("partial", parsed.getMessage().getContent());
    }

    @Test
    public void returnsStructuredErrorsAndRejectsUnsupportedOutputs() {
        AiMessageResponse error = parser.parse("{\"status\":\"failed\",\"error\":{\"message\":\"failed\",\"code\":\"server_error\"}}", null);
        assertTrue(error.isError());
        assertEquals("server_error", error.getErrorCode());
        assertTrue(parser.parse(completed(Maps.of("type", "web_search_call")).toJSONString(), null).isError());
        assertTrue(parser.parse("{\"status\":\"queued\"}", null).isError());
    }

    @Test
    public void modelUsesResponsesClientEndToEnd() {
        config.setApiKey("test-key");
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        config.setRetryEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        ((ResponsesChatClient) model.getChatClient()).setHttpClient(new AgentsFlexHttpClient() {
            @Override
            public String post(String url, Map<String, String> headers, String payload) {
                assertTrue(url.endsWith("/v1/responses"));
                assertEquals("hello", JSON.parseObject(payload).getJSONArray("input").getJSONObject(0).getString("content"));
                return completed(message("answer")).toJSONString();
            }
        });
        assertEquals("answer", model.chat("hello"));
        assertNull(ChatContextHolder.currentContext());
    }

    @Test
    public void streamsTextOnceAndPublishesCompleteParallelCallsAtTerminalEvent() {
        Harness h = new Harness();
        h.start();
        h.send("response.output_text.delta", Maps.of("delta", "Hi"));
        h.send("response.function_call_arguments.delta", Maps.of("item_id", "item_a").set("delta", "{"));
        h.send("response.function_call_arguments.delta", Maps.of("item_id", "item_b").set("delta", "{"));
        assertEquals(1, h.messages.size());
        h.send("response.completed", Maps.of("response", completed(message("Hi"), call("a", "{}"), call("b", "{}"))));
        h.send("response.completed", Maps.of("response", completed(message("Hi"))));
        h.bridge.onStop(h.transport);
        assertEquals(2, h.messages.size());
        AiMessage last = h.messages.get(1).getMessage();
        assertEquals("", last.getContent());
        assertEquals("Hi", last.getFullContent());
        assertEquals(2, last.getToolCalls().size());
        assertEquals("b", last.getToolCalls().get(1).getId());
        assertEquals(1, h.closes);
        assertEquals(0, h.errors);
        assertTrue(h.stopped);
    }

    @Test
    public void prematureEofAndMalformedEventsFailWithoutFinishedMessage() {
        Harness h = new Harness();
        h.start();
        h.bridge.onStop(h.transport);
        h.bridge.onStop(h.transport);
        assertEquals(1, h.errors);
        assertEquals(1, h.closes);
        assertTrue(h.messages.isEmpty());
        Harness malformed = new Harness();
        malformed.start();
        malformed.bridge.onMessage(malformed.transport, "not json");
        assertEquals(1, malformed.errors);
        assertEquals(1, malformed.closes);
    }

    @Test
    public void failedEventAndExplicitCancellationCloseExactlyOnce() {
        Harness h = new Harness();
        h.start();
        h.send("response.failed", Maps.of("response", Maps.of("status", "failed").set("error", Maps.of("message", "failure"))));
        assertEquals(1, h.errors);
        assertEquals(1, h.closes);
        Harness cancelled = new Harness();
        cancelled.start();
        cancelled.streamContext.getClient().stop();
        cancelled.bridge.onFailure(cancelled.transport, new RuntimeException("cancelled"));
        assertEquals(0, cancelled.errors);
        assertEquals(1, cancelled.closes);
        assertTrue(cancelled.messages.isEmpty());
    }

    @Test
    public void restoresCallingThreadContext() {
        ChatContext previous = new ChatContext();
        ChatContextHolder.set(previous);
        try {
            Harness h = new Harness();
            h.start();
            h.send("response.completed", Maps.of("response", completed(message("done"))));
            assertSame(previous, ChatContextHolder.currentContext());
        } finally { ChatContextHolder.clear(); }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedServerConversation() {
        builder.buildRequestBody(new SimplePrompt("hello"), ChatOptions.builder()
            .addExtraBody("previous_response_id", "resp_1").build(), config);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedMediaInsteadOfDroppingIt() {
        SimplePrompt prompt = new SimplePrompt("describe");
        prompt.addImageUrl("https://example.com/image.png");
        builder.buildRequestBody(prompt, new ChatOptions(), config);
    }

    @Test
    public void streamingModelBuildsResponsesBodyAndCompletes() {
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        model.setChatClient(new ResponsesChatClient(model) {
            @Override
            public StreamClient getStreamClient() {
                return new StreamClient() {
                    StreamClientListener target;
                    public void start(String url, Map<String, String> headers, String body, StreamClientListener listener, BaseChatConfig cfg) {
                        target = listener;
                        assertTrue(JSON.parseObject(body).getBooleanValue("stream"));
                        assertTrue(url.endsWith("/v1/responses"));
                        listener.onStart(this);
                        listener.onMessage(this, Maps.of("type", "response.output_text.delta").set("delta", "hello").toJSON());
                        listener.onMessage(this, Maps.of("type", "response.completed").set("response", completed(message("hello"))).toJSON());
                    }
                    public void stop() { if (target != null) target.onStop(this); }
                };
            }
        });
        final int[] opened = {0};
        final int[] closed = {0};
        final List<AiMessage> output = new ArrayList<>();
        model.chatStream("hello", new StreamResponseListener() {
            public void onOpen(StreamContext ctx) { opened[0]++; }
            public void onMessage(StreamContext ctx, AiMessageResponse response) { output.add(response.getMessage()); }
            public void onError(StreamContext ctx, Throwable error) { throw new AssertionError(error); }
            public void onClose(StreamContext ctx) { closed[0]++; }
        });
        assertEquals(1, opened[0]);
        assertEquals(1, closed[0]);
        assertEquals(2, output.size());
        assertEquals("hello", output.get(1).getFullContent());
        assertTrue(output.get(1).isFinalDelta());
        assertNull(ChatContextHolder.currentContext());
    }

    @Test
    public void cancellationDuringOpenDoesNotStartNetworkRequest() {
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        model.setChatClient(new ResponsesChatClient(model) {
            @Override
            public StreamClient getStreamClient() {
                return new StreamClient() {
                    public void start(String url, Map<String, String> headers, String body, StreamClientListener listener, BaseChatConfig cfg) {
                        fail("Cancelled stream must not start transport");
                    }
                    public void stop() { }
                };
            }
        });
        final int[] closed = {0};
        model.chatStream("hello", new StreamResponseListener() {
            public void onOpen(StreamContext ctx) { ctx.getClient().stop(); }
            public void onMessage(StreamContext ctx, AiMessageResponse response) { fail("Unexpected response"); }
            public void onError(StreamContext ctx, Throwable error) { throw new AssertionError(error); }
            public void onClose(StreamContext ctx) { closed[0]++; }
        });
        assertEquals(1, closed[0]);
    }

    @Test
    public void malformedAndEmptyHttpBodiesBecomeErrorResponses() {
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        config.setRetryEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        for (String raw : Arrays.asList("", "not json", "null", "{\"error\":{\"message\":\"bad request\",\"code\":\"invalid_request_error\"}}")) {
            ((ResponsesChatClient) model.getChatClient()).setHttpClient(new AgentsFlexHttpClient() {
                public String post(String url, Map<String, String> headers, String payload) { return raw; }
            });
            assertTrue(model.chat(new SimplePrompt("hello")).isError());
        }
    }

    @Test
    public void streamedIncompleteKeepsTextButNotToolCalls() {
        Harness h = new Harness();
        h.start();
        JSONObject response = completed(message("partial"), call("a", "{"));
        response.put("status", "incomplete");
        response.put("incomplete_details", Maps.of("reason", "max_output_tokens"));
        h.send("response.incomplete", Maps.of("response", response));
        assertEquals("length", h.messages.get(0).getMessage().getFinishReason());
        assertFalse(h.messages.get(0).hasToolCalls());
        assertEquals("partial", h.messages.get(0).getMessage().getFullContent());
        assertEquals(1, h.closes);
    }
    private static class Harness implements StreamResponseListener {
        final ChatContext chat = new ChatContext();
        final List<AiMessageResponse> messages = new ArrayList<>();
        int closes;
        int errors;
        boolean stopped;
        StreamContext streamContext;
        final StreamClient transport = new StreamClient() {
            public void start(String url, Map<String, String> headers, String body, StreamClientListener listener, BaseChatConfig config) { }
            public void stop() { stopped = true; bridge.onStop(this); }
        };
        final ResponsesStreamListener bridge = new ResponsesStreamListener(null, chat, transport, this);
        void start() { bridge.onStart(transport); }
        void send(String type, Maps fields) { fields.set("type", type); bridge.onMessage(transport, fields.toJSON()); }
        public void onOpen(StreamContext context) { streamContext = context; assertSame(chat, ChatContextHolder.currentContext()); }
        public void onMessage(StreamContext context, AiMessageResponse response) { messages.add(response); assertSame(chat, ChatContextHolder.currentContext()); }
        public void onError(StreamContext context, Throwable error) { errors++; }
        public void onClose(StreamContext context) { closes++; }
    }
}
