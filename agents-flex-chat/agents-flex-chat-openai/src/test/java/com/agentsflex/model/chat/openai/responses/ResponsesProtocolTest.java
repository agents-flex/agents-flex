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

/**
 * Responses 协议契约测试。
 * <p>
 * 使用固定 JSON 样例和模拟传输验证请求映射、工具回传及流式生命周期，
 * 不依赖真实服务或 API Key。回归测试同时覆盖取消和异常路径。
 */
public class ResponsesProtocolTest {

    private final OpenAIResponsesChatConfig config = new OpenAIResponsesChatConfig();
    private final ResponsesRequestSpecBuilder builder = new ResponsesRequestSpecBuilder();
    private final ResponsesResponseParser parser = new ResponsesResponseParser();

    private static JSONObject completed(Object... output) {
        return JSON.parseObject(JSON.toJSONString(Maps.of("id", "resp_1")
            .set("object", "response")
            .set("status", "completed")
            .set("model", "test-model")
            .set("created_at", 123)
            .set("output", Arrays.asList(output))));
    }

    private static Object message(String text) {
        return Maps.of("type", "message")
            .set("role", "assistant")
            .set("content", Collections.singletonList(Maps.of("type", "output_text").set("text", text)));
    }

    private static Object call(String id, String args) {
        return Maps.of("type", "function_call")
            .set("id", "item_" + id)
            .set("call_id", id)
            .set("name", "weather")
            .set("arguments", args);
    }

    /** 验证 Responses 字段映射，并确保请求中不混入 Chat Completions 专有字段。 */
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
        for (String key : Arrays.asList("messages", "max_tokens", "response_format", "stream_options"))
            assertFalse(body.containsKey(key));
        ChatRequestSpec spec = builder.buildRequestSpec(prompt, options, config);
        assertEquals("https://api.openai.com/v1/responses", spec.getUrl());
        assertEquals("Bearer test-key", spec.getHeaders().get("Authorization"));
        assertFalse(JSON.parseObject(builder.buildRequestBody(prompt, options, config)).containsKey("stream"));
    }

    /** 转换 Schema 层级时保留调用方原始 Options，避免并发复用受到影响。 */
    @Test
    public void flattensJsonSchemaWithoutMutatingOptions() {
        Map<String, Object> schema =
            Maps.of("name", "answer").set("strict", true).set("schema", Maps.of("type", "object"));
        ChatOptions options = ChatOptions.builder().responseFormatToJsonSchema(schema).build();
        JSONObject body = JSON.parseObject(builder.buildRequestBody(new SimplePrompt("hello"), options, config));
        JSONObject format = body.getJSONObject("text").getJSONObject("format");
        assertEquals("answer", format.getString("name"));
        assertEquals("json_schema", format.getString("type"));
        assertFalse(format.containsKey("json_schema"));
        assertTrue(options.getResponseFormat().containsKey("json_schema"));
    }

    /** 验证多工具执行结果按 call_id 回传，且复制消息后加密推理项仍可恢复。 */
    @Test
    public void roundTripsFunctionCallsAndEncryptedReasoningThroughCopiedHistory() {
        Object reasoning = Maps.of("type", "reasoning")
            .set("id", "rs_1")
            .set("encrypted_content", "opaque")
            .set("summary", Collections.emptyList());
        AiMessage ai =
            parser.parse(completed(reasoning, call("call_a", "{}"), call("call_b", "{}")).toJSONString(), null)
                .getMessage();
        assertEquals("call_a", ai.getToolCalls().get(0).getId());
        assertEquals(2, ai.getToolCalls().size());
        SimplePrompt initial = new SimplePrompt("weather?");
        initial.addTool(new Tool() {
            public String getName() {
                return "weather";
            }
            public String getDescription() {
                return "Weather";
            }
            public Parameter[] getParameters() {
                return new Parameter[0];
            }
            public Object invoke(Map<String, Object> args) {
                return "sunny";
            }
        });
        initial.setToolChoice("required");
        ChatContext context = new ChatContext();
        context.setPrompt(initial);
        List<ToolMessage> results = new AiMessageResponse(context, "", ai).executeToolCallsAndGetToolMessages();
        assertEquals("call_b", results.get(1).getToolCallId());
        List<Message> history = new ArrayList<>(initial.getMessages());
        history.add(ai.copy());
        history.addAll(results);
        Prompt next = new Prompt() {
            public List<Message> getMessages() {
                return history;
            }
        };
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

    /** 解析多个文本项、Token 明细和独立的拒答内容。 */
    @Test
    public void parsesAllTextPartsUsageAndRefusal() {
        JSONObject response = completed(message("hello"), message(" world"));
        response.put("usage",
            Maps.of("input_tokens", 10)
                .set("output_tokens", 3)
                .set("total_tokens", 13)
                .set("input_tokens_details", Maps.of("cached_tokens", 2)));
        AiMessage ai = parser.parse(response.toJSONString(), null).getMessage();
        assertEquals("hello world", ai.getContent());
        assertEquals(Integer.valueOf(13), ai.getTotalTokens());
        assertEquals(2, ai.getPromptTokensDetails().get("cached_tokens"));
        assertEquals("resp_1", ai.getId());
        JSONObject refused = completed(Maps.of("type", "message")
            .set("content", Collections.singletonList(Maps.of("type", "refusal").set("refusal", "declined"))));
        assertEquals("declined", parser.parse(refused.toJSONString(), null).getMessage().getRefusal());
    }

    /** 响应被截断时保留正文，但不允许执行不完整的工具参数。 */
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

    /** 保留服务端错误码，并明确拒绝尚不支持的输出项和非终止状态。 */
    @Test
    public void returnsStructuredErrorsAndRejectsUnsupportedOutputs() {
        AiMessageResponse error =
            parser.parse("{\"status\":\"failed\",\"error\":{\"message\":\"failed\",\"code\":\"server_error\"}}", null);
        assertTrue(error.isError());
        assertEquals("server_error", error.getErrorCode());
        assertTrue(parser.parse(completed(Maps.of("type", "web_search_call")).toJSONString(), null).isError());
        assertTrue(parser.parse("{\"status\":\"queued\"}", null).isError());
    }

    /** 通过模型公开入口验证 URL、请求体、响应解析和上下文清理。 */
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
                assertEquals(
                    "hello", JSON.parseObject(payload).getJSONArray("input").getJSONObject(0).getString("content"));
                return completed(message("answer")).toJSONString();
            }
        });
        assertEquals("answer", model.chat("hello"));
        assertNull(ChatContextHolder.currentContext());
    }

    /** 交错工具分片不提前下发，最终快照包含完整工具列表且不重复输出正文。 */
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

    /** 异常断流和非法事件只通知一次失败，不生成伪成功消息。 */
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

    /** 区分服务端失败与业务取消，并验证关闭回调去重。 */
    @Test
    public void failedEventAndExplicitCancellationCloseExactlyOnce() {
        Harness h = new Harness();
        h.start();
        h.send("response.failed",
            Maps.of("response", Maps.of("status", "failed").set("error", Maps.of("message", "failure"))));
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

    /** 回调结束后恢复外层线程上下文，避免嵌套调用污染。 */
    @Test
    public void restoresCallingThreadContext() {
        ChatContext previous = new ChatContext();
        ChatContextHolder.set(previous);
        try {
            Harness h = new Harness();
            h.start();
            h.send("response.completed", Maps.of("response", completed(message("done"))));
            assertSame(previous, ChatContextHolder.currentContext());
        } finally {
            ChatContextHolder.clear();
        }
    }

    /** 禁止将服务端会话参数与本地历史回传混用。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedServerConversation() {
        builder.buildRequestBody(new SimplePrompt("hello"),
            ChatOptions.builder().addExtraBody("previous_response_id", "resp_1").build(), config);
    }

    /** 不支持的媒体输入必须显式报错，不能静默丢弃。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedMediaInsteadOfDroppingIt() {
        SimplePrompt prompt = new SimplePrompt("describe");
        prompt.addImageUrl("https://example.com/image.png");
        builder.buildRequestBody(prompt, new ChatOptions(), config);
    }

    /** 通过模型流式入口验证请求构建和完整的打开、消息、关闭回调。 */
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
                    public void start(String url, Map<String, String> headers, String body,
                        StreamClientListener listener, BaseChatConfig cfg) {
                        target = listener;
                        assertTrue(JSON.parseObject(body).getBooleanValue("stream"));
                        assertTrue(url.endsWith("/v1/responses"));
                        listener.onStart(this);
                        listener.onMessage(
                            this, Maps.of("type", "response.output_text.delta").set("delta", "hello").toJSON());
                        listener.onMessage(this,
                            Maps.of("type", "response.completed")
                                .set("response", completed(message("hello")))
                                .toJSON());
                    }
                    public void stop() {
                        if (target != null)
                            target.onStop(this);
                    }
                };
            }
        });
        final int[] opened = {0};
        final int[] closed = {0};
        final List<AiMessage> output = new ArrayList<>();
        model.chatStream("hello", new StreamResponseListener() {
            public void onOpen(StreamContext ctx) {
                opened[0]++;
            }
            public void onMessage(StreamContext ctx, AiMessageResponse response) {
                output.add(response.getMessage());
            }
            public void onError(StreamContext ctx, Throwable error) {
                throw new AssertionError(error);
            }
            public void onClose(StreamContext ctx) {
                closed[0]++;
            }
        });
        assertEquals(1, opened[0]);
        assertEquals(1, closed[0]);
        assertEquals(2, output.size());
        assertEquals("hello", output.get(1).getFullContent());
        assertTrue(output.get(1).isFinalDelta());
        assertNull(ChatContextHolder.currentContext());
    }

    /** 业务在 onOpen 中取消时，不应继续发起网络请求。 */
    @Test
    public void cancellationDuringOpenDoesNotStartNetworkRequest() {
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        model.setChatClient(new ResponsesChatClient(model) {
            @Override
            public StreamClient getStreamClient() {
                return new StreamClient() {
                    public void start(String url, Map<String, String> headers, String body,
                        StreamClientListener listener, BaseChatConfig cfg) {
                        fail("Cancelled stream must not start transport");
                    }
                    public void stop() {
                    }
                };
            }
        });
        final int[] closed = {0};
        model.chatStream("hello", new StreamResponseListener() {
            public void onOpen(StreamContext ctx) {
                ctx.getClient().stop();
            }
            public void onMessage(StreamContext ctx, AiMessageResponse response) {
                fail("Unexpected response");
            }
            public void onError(StreamContext ctx, Throwable error) {
                throw new AssertionError(error);
            }
            public void onClose(StreamContext ctx) {
                closed[0]++;
            }
        });
        assertEquals(1, closed[0]);
    }

    /** 空响应、非法 JSON 及服务端错误统一转换为错误响应。 */
    @Test
    public void malformedAndEmptyHttpBodiesBecomeErrorResponses() {
        config.setLogEnabled(false);
        config.setObservabilityEnabled(false);
        config.setRetryEnabled(false);
        OpenAIResponsesChatModel model = config.toChatModel();
        for (String raw : Arrays.asList("", "not json", "null",
                 "{\"error\":{\"message\":\"bad request\",\"code\":\"invalid_request_error\"}}")) {
            ((ResponsesChatClient) model.getChatClient()).setHttpClient(new AgentsFlexHttpClient() {
                public String post(String url, Map<String, String> headers, String payload) {
                    return raw;
                }
            });
            assertTrue(model.chat(new SimplePrompt("hello")).isError());
        }
    }

    /** 流式未完成响应保留部分正文和结束原因，不下发工具调用。 */
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
    /** 模拟可重入的传输关闭回调，并记录业务监听器收到的事件。 */
    private static class Harness implements StreamResponseListener {

        final ChatContext chat = new ChatContext();
        final List<AiMessageResponse> messages = new ArrayList<>();
        int closes;
        int errors;
        boolean stopped;
        StreamContext streamContext;
        final StreamClient transport = new StreamClient() {
            public void start(String url, Map<String, String> headers, String body, StreamClientListener listener,
                BaseChatConfig config) {
            }
            public void stop() {
                stopped = true;
                bridge.onStop(this);
            }
        };
        final ResponsesStreamListener bridge = new ResponsesStreamListener(null, chat, transport, this);
        void start() {
            bridge.onStart(transport);
        }
        void send(String type, Maps fields) {
            fields.set("type", type);
            bridge.onMessage(transport, fields.toJSON());
        }
        public void onOpen(StreamContext context) {
            streamContext = context;
            assertSame(chat, ChatContextHolder.currentContext());
        }
        public void onMessage(StreamContext context, AiMessageResponse response) {
            messages.add(response);
            assertSame(chat, ChatContextHolder.currentContext());
        }
        public void onError(StreamContext context, Throwable error) {
            errors++;
        }
        public void onClose(StreamContext context) {
            closes++;
        }
    }
}
