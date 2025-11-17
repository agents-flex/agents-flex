/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.openai;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAIChatModel extends BaseChatModel<OpenAIChatConfig> {

    private static final Tracer TRACER = Observability.getTracer(); // 初始化即触发 OpenTelemetry
    private static final Meter METER = Observability.getMeter();

    private static final LongCounter LLM_REQUEST_COUNT = METER.counterBuilder("llm.request.count")
        .setDescription("Total number of LLM requests")
        .build();

    private static final DoubleHistogram LLM_LATENCY_HISTOGRAM = METER.histogramBuilder("llm.request.latency")
        .setDescription("LLM request latency in seconds")
        .setUnit("s")
        .build();

    private static final LongCounter LLM_ERROR_COUNT = METER.counterBuilder("llm.request.error.count")
        .setDescription("Total number of LLM request errors")
        .build();

    private HttpClient httpClient = new HttpClient();
    private AiMessageParser aiMessageParser = OpenAILlmUtil.getAiMessageParser(false);
    private AiMessageParser streamMessageParser = OpenAILlmUtil.getAiMessageParser(true);

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public AiMessageParser getAiMessageParser() {
        return aiMessageParser;
    }

    public void setAiMessageParser(AiMessageParser aiMessageParser) {
        this.aiMessageParser = aiMessageParser;
    }

    public AiMessageParser getStreamMessageParser() {
        return streamMessageParser;
    }

    public void setStreamMessageParser(AiMessageParser streamMessageParser) {
        this.streamMessageParser = streamMessageParser;
    }

    public static OpenAIChatModel of(String apiKey) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        return new OpenAIChatModel(config);
    }

    public static OpenAIChatModel of(String apiKey, String endpoint) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        config.setEndpoint(endpoint);
        return new OpenAIChatModel(config);
    }

    public OpenAIChatModel(OpenAIChatConfig config) {
        super(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {

        String modelName = getConfig().getModel() != null ? getConfig().getModel() : "unknown";
        Span span = TRACER.spanBuilder("OpenAILlm.chat")
            .setAttribute("llm.model", modelName)
            .setAttribute("llm.provider", "openai")
            .setAttribute("llm.apiKey", config.getApiKey())
            .startSpan();

        long startTime = System.nanoTime();
        boolean success = true;

        try (Scope scope = span.makeCurrent()) {
            // 记录输入内容（可选，注意敏感信息脱敏）
            span.setAttribute("llm.prompt", prompt.toString().substring(0, Math.min(prompt.toString().length(), 500)));


            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", "Bearer " + getConfig().getApiKey());

            Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
            if (headersConfig != null) {
                headersConfig.accept(headers);
            }

            // 非流式返回，比如 Qwen3 等必须设置 false，否则自动流式返回了
            if (options.getEnableThinking() == null) {
                options.setEnableThinking(false);
            }

            String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, false);
            if (config.isDebug()) {
                LogUtil.println(">>>>send payload:" + payload);
            }
            String url = config.getEndpoint() + config.getChatPath();
            String response = httpClient.post(url, headers, payload);

            if (config.isDebug()) {
                LogUtil.println(">>>>receive payload:" + response);
            }

            if (StringUtil.noText(response)) {
                return AiMessageResponse.error(prompt, response, "no content for response.");
            }

            JSONObject jsonObject = JSON.parseObject(response);
            JSONObject error = jsonObject.getJSONObject("error");

            AiMessage aiMessage = aiMessageParser.parse(jsonObject);
            LocalTokenCounter.computeAndSetLocalTokens(prompt.toMessages(), aiMessage);
            AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessage);

            if (error != null && !error.isEmpty()) {
                messageResponse.setError(true);
                messageResponse.setErrorMessage(error.getString("message"));
                messageResponse.setErrorType(error.getString("type"));
                messageResponse.setErrorCode(error.getString("code"));
            } else {
                span.setAttribute("llm.total_tokens", aiMessage.getEffectiveTotalTokens());
                span.setAttribute("llm.response", aiMessage.getContent() != null ?
                    aiMessage.getContent().substring(0, Math.min(aiMessage.getContent().length(), 500)) : "");
            }
            return messageResponse;
        } catch (Exception e) {
            success = false;
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();

            double latency = (System.nanoTime() - startTime) / 1_000_000_000.0; // seconds
            Attributes attrs = Attributes.of(
                AttributeKey.stringKey("llm.model"), modelName,
                AttributeKey.stringKey("llm.operation"), "chat",
                AttributeKey.stringKey("llm.success"), String.valueOf(success)
            );

            LLM_REQUEST_COUNT.add(1, attrs);
            LLM_LATENCY_HISTOGRAM.record(latency, attrs);
            if (!success) {
                LLM_ERROR_COUNT.add(1, attrs);
            }
        }
    }


    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        String modelName = getConfig().getModel() != null ? getConfig().getModel() : "unknown";
        Span span = TRACER.spanBuilder("OpenAILlm.chatStream")
            .setAttribute("llm.model", modelName)
            .setAttribute("llm.provider", "openai")
            .startSpan();

        long startTime = System.nanoTime();
        final boolean[] success = {true}; // 使用数组以在内部类中修改

        try (Scope scope = span.makeCurrent()) {
            StreamClient streamClient = new SseClient();
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", "Bearer " + getConfig().getApiKey());

            String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, true);
            String endpoint = config.getEndpoint();

            // 包装 listener 以捕获错误并结束 span
            StreamResponseListener wrappedListener = new StreamResponseListener() {
                @Override
                public void onStart(ChatContext context) {
                    // 可选：记录上下文
                    listener.onStart(context);
                }

                @Override
                public void onMessage(ChatContext context, AiMessageResponse response) {
                    listener.onMessage(context, response);
                }

                @Override
                public void onStop(ChatContext context) {
                    span.end();
                    double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    recordMetrics("chatStream", modelName, success[0], latency);
                    listener.onStop(context);
                }

                @Override
                public void onMatchedFunction(String functionName, ChatContext context) {
                    span.setAttribute("llm.function_call", functionName);
                    listener.onMatchedFunction(functionName, context);
                }

                @Override
                public void onFailure(ChatContext context, Throwable throwable) {
                    success[0] = false;
                    if (throwable != null) {
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
                        span.recordException(throwable);
                    }
                    span.end();
                    double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    recordMetrics("chatStream", modelName, false, latency);
                    listener.onFailure(context, throwable);
                }
            };

            StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, wrappedListener, prompt, streamMessageParser);
            streamClient.start(endpoint + config.getChatPath(), headers, payload, clientListener, config);
        } catch (Exception e) {
            success[0] = false;
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            span.end();
            double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
            recordMetrics("chatStream", modelName, false, latency);
            throw e;
        }

    }



    // 公共指标记录方法
    private void recordMetrics(String operation, String model, boolean success, double latency) {
        Attributes attrs = Attributes.of(
            AttributeKey.stringKey("llm.model"), model,
            AttributeKey.stringKey("llm.operation"), operation,
            AttributeKey.stringKey("llm.success"), String.valueOf(success)
        );
        LLM_REQUEST_COUNT.add(1, attrs);
        LLM_LATENCY_HISTOGRAM.record(latency, attrs);
        if (!success) {
            LLM_ERROR_COUNT.add(1, attrs);
        }
    }

}
