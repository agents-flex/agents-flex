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

package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.observability.Observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 支持 OpenTelemetry 监控（可开关）、线程上下文管理与拦截器链的聊天模型基类（JDK 8 兼容）。
 * <p>
 * 该类为所有具体的 LLM 实现（如 OpenAI、Qwen、Ollama）提供统一入口，并集成：
 * <ul>
 *   <li>分布式追踪（Span）—— 可通过 {@link ChatConfig#setObservabilityEnabled(boolean)} 关闭</li>
 *   <li>指标上报（QPS、延迟、错误率）—— 同上</li>
 *   <li>线程上下文（通过 {@link ChatContextHolder}）</li>
 *   <li>拦截器链（支持全局和实例级）</li>
 * </ul>
 *
 * @param <T> 具体的配置类型，必须是 {@link ChatConfig} 的子类
 */
public abstract class BaseChatModel<T extends ChatConfig> implements ChatModel {

    // ===== OpenTelemetry 全局监控指标（所有 LLM 共享）=====
    private static final Tracer TRACER = Observability.getTracer();
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

    // ===== 实例字段 =====
    protected final T config;
    private List<ChatInterceptor> interceptors;

    /**
     * 构造一个聊天模型实例，不使用实例级拦截器。
     */
    public BaseChatModel(T config) {
        this(config, Collections.emptyList());
    }

    /**
     * 构造一个聊天模型实例，并指定实例级拦截器。
     * 实例级拦截器会与全局拦截器（通过 {@link GlobalChatInterceptors} 注册）合并，
     * 执行顺序为：全局拦截器 → 实例拦截器。
     */
    public BaseChatModel(T config, List<ChatInterceptor> interceptors) {
        this.config = config;
        this.interceptors = interceptors != null ? new ArrayList<>(interceptors) : new ArrayList<>();
        // 合并全局拦截器
        if (GlobalChatInterceptors.size() > 0) {
            this.interceptors.addAll(0, GlobalChatInterceptors.getInterceptors());
        }
    }

    // ===== 拦截器管理方法 =====
    public void addInterceptor(ChatInterceptor interceptor) {
        if (interceptors == null) interceptors = new ArrayList<>();
        interceptors.add(interceptor);
    }

    public void addInterceptors(List<ChatInterceptor> interceptors) {
        if (this.interceptors == null) this.interceptors = new ArrayList<>();
        this.interceptors.addAll(interceptors);
    }

    public void removeInterceptor(ChatInterceptor interceptor) {
        if (interceptors != null) interceptors.remove(interceptor);
    }

    public void clearInterceptors() {
        if (interceptors != null) interceptors.clear();
    }

    public T getConfig() {
        return config;
    }

    private boolean isObservabilityEnabled() {
        return config != null && config.isObservabilityEnabled();
    }

    // ===== 拦截器执行方法=====
    private ChatInterceptor.PreHandleResult firePreHandle(Prompt prompt, ChatOptions options) {
        Prompt currentPrompt = prompt;
        ChatOptions currentOptions = options;
        for (ChatInterceptor interceptor : interceptors) {
            try {
                ChatInterceptor.PreHandleResult result = interceptor.preHandle(currentPrompt, currentOptions, this);
                if (result != null) {
                    currentPrompt = result.getPrompt() != null ? result.getPrompt() : currentPrompt;
                    currentOptions = result.getOptions() != null ? result.getOptions() : currentOptions;
                }
            } catch (Exception e) {
                handleInterceptorError(e, "preHandle", interceptor);
            }
        }
        return new ChatInterceptor.PreHandleResult(currentPrompt, currentOptions);
    }

    private AiMessageResponse firePostHandle(Prompt originalPrompt, ChatOptions originalOptions,
                                             AiMessageResponse response, boolean success) {
        AiMessageResponse finalResponse = response;
        for (ChatInterceptor interceptor : interceptors) {
            try {
                AiMessageResponse modified = interceptor.postHandle(originalPrompt, originalOptions, finalResponse, success);
                if (modified != null) finalResponse = modified;
            } catch (Exception e) {
                handleInterceptorError(e, "postHandle", interceptor);
            }
        }
        return finalResponse;
    }

    private void fireAfterCompletion(Prompt prompt, ChatOptions options,
                                     AiMessageResponse response, Throwable ex) {
        for (ChatInterceptor interceptor : interceptors) {
            try {
                interceptor.afterCompletion(prompt, options, response, ex);
            } catch (Exception e) {
                handleInterceptorError(e, "afterCompletion", interceptor);
            }
        }
    }


    protected void handleInterceptorError(Exception e, String methodName, ChatInterceptor interceptor) {
        // 静默处理，避免影响主流程
    }

    // ===== 非流式聊天 =====
    @Override
    public AiMessageResponse chat(Prompt originalPrompt, ChatOptions originalOptions) {
        ChatInterceptor.PreHandleResult preResult = firePreHandle(originalPrompt, originalOptions);
        Prompt currentPrompt = preResult.getPrompt();
        ChatOptions currentOptions = preResult.getOptions();

        final boolean observabilityEnabled = isObservabilityEnabled();
        final String provider = config.getProvider();
        final String model = config.getModel();
        final String operation = "chat";

        final Span span = observabilityEnabled ?
            TRACER.spanBuilder(provider + "." + operation)
                .setAttribute("llm.provider", provider)
                .setAttribute("llm.model", model)
                .setAttribute("llm.operation", operation)
                .startSpan() : null;

        final Scope scope = observabilityEnabled ? span.makeCurrent() : null;
        final long startTimeNanos = observabilityEnabled ? System.nanoTime() : 0;
        boolean success = true;

        try (ChatContextHolder.ChatContextScope contextScope = ChatContextHolder.beginChat(config, currentOptions, currentPrompt, span)) {
            AiMessageResponse response = doChat(currentPrompt, currentOptions);
            boolean callSuccess = (response != null) && !response.isError();
            AiMessageResponse finalResponse = firePostHandle(originalPrompt, originalOptions, response, callSuccess);

            if (span != null && finalResponse != null && !finalResponse.isError()) {
                AiMessage aiMessage = finalResponse.getMessage();
                if (aiMessage != null) {
                    span.setAttribute("llm.total_tokens", aiMessage.getEffectiveTotalTokens());
                    String content = aiMessage.getContent();
                    if (content != null) {
                        span.setAttribute("llm.response",
                            content.substring(0, Math.min(content.length(), 500)));
                    }
                }
            }
            return finalResponse;
        } catch (Exception e) {
            success = false;
            if (span != null) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
            fireAfterCompletion(currentPrompt, currentOptions, null, e);
            throw e;
        } finally {
            if (span != null) {
                span.end();
                if (scope != null) scope.close();
                recordMetrics(provider, model, operation, success, startTimeNanos);
            }
        }
    }

    @Override
    public void chatStream(Prompt originalPrompt, StreamResponseListener originalListener, ChatOptions originalOptions) {
        ChatInterceptor.PreHandleResult preResult = firePreHandle(originalPrompt, originalOptions);
        Prompt currentPrompt = preResult.getPrompt();
        ChatOptions currentOptions = preResult.getOptions();

        final boolean observabilityEnabled = isObservabilityEnabled();
        final String provider = config.getProvider();
        final String model = config.getModel();
        final String operation = "chatStream";

        final Span span = observabilityEnabled ?
            TRACER.spanBuilder(provider + "." + operation)
                .setAttribute("llm.provider", provider)
                .setAttribute("llm.model", model)
                .setAttribute("llm.operation", operation)
                .startSpan() : null;

        final Scope scope = observabilityEnabled ? span.makeCurrent() : null;
        final long startTimeNanos = observabilityEnabled ? System.nanoTime() : 0;
        final AtomicBoolean success = new AtomicBoolean(true);

        StreamResponseListener wrappedListener = new StreamResponseListener() {
            private AiMessageResponse response;
            private Throwable exception;

            @Override
            public void onStop(StreamContext context) {
                if (span != null) {
                    span.end();
                    if (scope != null) scope.close();
                    recordMetrics(provider, model, operation, success.get(), startTimeNanos);
                }
                fireAfterCompletion(currentPrompt, currentOptions, response, exception);
                originalListener.onStop(context);
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                this.exception = throwable;
                success.set(false);
                if (span != null) {
                    if (throwable != null) {
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
                        span.recordException(throwable);
                    }
                    span.end();
                    if (scope != null) scope.close();
                    recordMetrics(provider, model, operation, false, startTimeNanos);
                }
                originalListener.onFailure(context, throwable);
            }

            @Override
            public void onStart(StreamContext context) {
                originalListener.onStart(context);
            }

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                this.response = response;
                originalListener.onMessage(context, response);
            }

            @Override
            public void onMatchedFunction(String functionName, StreamContext context) {
                if (span != null) {
                    span.setAttribute("llm.function_call", functionName);
                }
                originalListener.onMatchedFunction(functionName, context);
            }
        };

        try (ChatContextHolder.ChatContextScope contextScope = ChatContextHolder.beginChat(config, currentOptions, currentPrompt, span)) {
            doChatStream(currentPrompt, wrappedListener, currentOptions);
        } catch (Exception e) {
            success.set(false);
            if (span != null) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                span.recordException(e);
                span.end();
                if (scope != null) scope.close();
                recordMetrics(provider, model, operation, false, startTimeNanos);
            }
            fireAfterCompletion(currentPrompt, currentOptions, null, e);
            throw e;
        }
    }

    /**
     * 仅在监控行为启用时调用。
     */
    private void recordMetrics(String provider, String model, String operation, boolean success, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        Attributes attrs = Attributes.of(
            AttributeKey.stringKey("llm.provider"), provider,
            AttributeKey.stringKey("llm.model"), model,
            AttributeKey.stringKey("llm.operation"), operation,
            AttributeKey.stringKey("llm.success"), String.valueOf(success)
        );
        LLM_REQUEST_COUNT.add(1, attrs);
        LLM_LATENCY_HISTOGRAM.record(latencySeconds, attrs);
        if (!success) {
            LLM_ERROR_COUNT.add(1, attrs);
        }
    }

    // ===== 子类必须实现 =====
    public abstract AiMessageResponse doChat(Prompt prompt, ChatOptions options);

    public abstract void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options);
}
