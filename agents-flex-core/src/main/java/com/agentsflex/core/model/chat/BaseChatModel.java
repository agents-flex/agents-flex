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
import com.agentsflex.core.model.client.StreamResponseListener;
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
 * 支持 OpenTelemetry 监控、线程上下文管理与拦截器链的聊天模型基类（JDK 8 兼容）。
 * <p>
 * 该类为所有具体的 LLM 实现（如 OpenAI、Qwen、Ollama）提供统一入口，并集成：
 * <ul>
 *   <li>分布式追踪（Span）</li>
 *   <li>指标上报（QPS、延迟、错误率）</li>
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
    private final List<ChatInterceptor> allInterceptors; // 全局拦截器 + 实例拦截器的合并列表

    /**
     * 构造一个聊天模型实例，不使用实例级拦截器。
     *
     * @param config LLM 的配置对象
     */
    public BaseChatModel(T config) {
        this(config, Collections.<ChatInterceptor>emptyList());
    }

    /**
     * 构造一个聊天模型实例，并指定实例级拦截器。
     * <p>
     * 实例级拦截器会与全局拦截器（通过 {@link GlobalChatInterceptors} 注册）合并，
     * 执行顺序为：全局拦截器 → 实例拦截器。
     *
     * @param config       LLM 的配置对象
     * @param interceptors 实例级拦截器列表（可为 null 或空）
     */
    public BaseChatModel(T config, List<ChatInterceptor> interceptors) {
        this.config = config;
        // 合并全局拦截器和实例拦截器
        List<ChatInterceptor> combined = new ArrayList<>();
        combined.addAll(GlobalChatInterceptors.getInterceptors()); // 全局拦截器
        if (interceptors != null) {
            combined.addAll(interceptors); // 实例拦截器
        }
        this.allInterceptors = Collections.unmodifiableList(combined);
    }

    /**
     * 获取当前模型的配置对象。
     *
     * @return 配置对象
     */
    public T getConfig() {
        return config;
    }

    // ===== 拦截器执行方法（fire* 模式）=====

    /**
     * 执行所有拦截器的 preHandle 方法，返回最终的 Prompt 和 ChatOptions。
     */
    private ChatInterceptor.PreHandleResult firePreHandle(Prompt prompt, ChatOptions options) {
        Prompt currentPrompt = prompt;
        ChatOptions currentOptions = options;

        for (ChatInterceptor interceptor : allInterceptors) {
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

    /**
     * 执行所有拦截器的 postHandle 方法，返回最终的响应对象。
     */
    private AiMessageResponse firePostHandle(Prompt originalPrompt, ChatOptions originalOptions,
                                             AiMessageResponse response, boolean success) {
        AiMessageResponse finalResponse = response;
        for (ChatInterceptor interceptor : allInterceptors) {
            try {
                AiMessageResponse modified = interceptor.postHandle(originalPrompt, originalOptions, finalResponse, success);
                if (modified != null) {
                    finalResponse = modified;
                }
            } catch (Exception e) {
                handleInterceptorError(e, "postHandle", interceptor);
            }
        }
        return finalResponse;
    }

    /**
     * 执行所有拦截器的 afterCompletion 方法。
     */
    private void fireAfterCompletion(Prompt prompt, ChatOptions options,
                                     AiMessageResponse response, Throwable ex) {
        for (ChatInterceptor interceptor : allInterceptors) {
            try {
                interceptor.afterCompletion(prompt, options, response, ex);
            } catch (Exception e) {
                handleInterceptorError(e, "afterCompletion", interceptor);
            }
        }
    }

    /**
     * 执行所有拦截器的 wrapStreamListener 方法，返回最终包装的监听器。
     */
    private StreamResponseListener fireWrapStreamListener(StreamResponseListener original,
                                                          Prompt prompt, ChatOptions options) {
        StreamResponseListener current = original;
        for (ChatInterceptor interceptor : allInterceptors) {
            try {
                current = interceptor.wrapStreamListener(current, prompt, options);
            } catch (Exception e) {
                handleInterceptorError(e, "wrapStreamListener", interceptor);
            }
        }
        return current;
    }

    /**
     * 统一处理拦截器执行过程中抛出的异常。
     * <p>
     * 默认实现静默忽略异常，避免拦截器故障影响主流程。
     * 子类可重写此方法以记录日志或上报监控。
     *
     * @param e           拦截器抛出的异常
     * @param methodName  出错的拦截器方法名
     * @param interceptor 出错的拦截器实例
     */
    protected void handleInterceptorError(Exception e, String methodName, ChatInterceptor interceptor) {
        // 可在此添加日志记录，例如：
        // LogUtil.warn("ChatInterceptor error in " + methodName + ": " + e.getMessage(), e);
        // 当前保持静默，确保主流程不受影响
    }

    // ===== 非流式聊天：带监控 + 上下文 + 拦截器 =====
    @Override
    public AiMessageResponse chat(Prompt originalPrompt, ChatOptions originalOptions) {
        String provider = config.getProvider();
        String model = config.getModel();
        String operation = "chat";

        Span span = TRACER.spanBuilder(provider + "." + operation)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .startSpan();

        long startTimeNanos = System.nanoTime();
        boolean success = true;

        // 执行 preHandle 拦截器链
        ChatInterceptor.PreHandleResult preResult = firePreHandle(originalPrompt, originalOptions);
        Prompt currentPrompt = preResult.getPrompt();
        ChatOptions currentOptions = preResult.getOptions();

        try (ChatContextHolder.ChatContextScope ignored = ChatContextHolder.beginChat(config, currentOptions, currentPrompt, span);
             Scope ignored1 = span.makeCurrent()) {

            AiMessageResponse response = doChat(currentPrompt, currentOptions);
            boolean callSuccess = (response != null) && !response.isError();

            // 执行 postHandle 拦截器链
            AiMessageResponse finalResponse = firePostHandle(originalPrompt, originalOptions, response, callSuccess);

            // 设置 Span 属性（使用最终响应）
            if (finalResponse != null && !finalResponse.isError()) {
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
            // 执行 afterCompletion 拦截器链（异常场景）
            fireAfterCompletion(currentPrompt, currentOptions, null, e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
            recordMetrics(provider, model, operation, success, startTimeNanos);
        }
    }

    // ===== 流式聊天：带监控 + 上下文 + 拦截器 =====
    @Override
    public void chatStream(Prompt originalPrompt, StreamResponseListener originalListener, ChatOptions originalOptions) {
        String provider = config.getProvider();
        String model = config.getModel();
        String operation = "chatStream";

        Span span = TRACER.spanBuilder(provider + "." + operation)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .startSpan();

        long startTimeNanos = System.nanoTime();
        final AtomicBoolean success = new AtomicBoolean(true);

        // 执行 preHandle 拦截器链
        ChatInterceptor.PreHandleResult preResult = firePreHandle(originalPrompt, originalOptions);
        Prompt currentPrompt = preResult.getPrompt();
        ChatOptions currentOptions = preResult.getOptions();

        // 执行流式监听器拦截器链
        StreamResponseListener listenerAfterInterceptors = fireWrapStreamListener(originalListener, currentPrompt, currentOptions);

        // 包装 listener 以处理 Span 结束、指标上报和 afterCompletion
        StreamResponseListener wrappedListener = new StreamResponseListener() {
            @Override
            public void onStop(StreamContext context) {
                span.end();
                recordMetrics(provider, model, operation, success.get(), startTimeNanos);
                // 执行 afterCompletion（成功场景）
                fireAfterCompletion(currentPrompt, currentOptions, null, null);
                listenerAfterInterceptors.onStop(context);
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                success.set(false);
                if (throwable != null) {
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
                    span.recordException(throwable);
                }
                span.end();
                recordMetrics(provider, model, operation, false, startTimeNanos);
                // 执行 afterCompletion（失败场景）
                fireAfterCompletion(currentPrompt, currentOptions, null, throwable);
                listenerAfterInterceptors.onFailure(context, throwable);
            }

            @Override
            public void onStart(StreamContext context) {
                listenerAfterInterceptors.onStart(context);
            }

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                listenerAfterInterceptors.onMessage(context, response);
            }

            @Override
            public void onMatchedFunction(String functionName, StreamContext context) {
                span.setAttribute("llm.function_call", functionName);
                listenerAfterInterceptors.onMatchedFunction(functionName, context);
            }
        };

        try (ChatContextHolder.ChatContextScope ignored = ChatContextHolder.beginChat(config, currentOptions, currentPrompt, span);
             Scope ignored1 = span.makeCurrent()) {
            doChatStream(currentPrompt, wrappedListener, currentOptions);
        } catch (Exception e) {
            success.set(false);
            // 执行 afterCompletion（异常被捕获）
            fireAfterCompletion(currentPrompt, currentOptions, null, e);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            span.end();
            recordMetrics(provider, model, operation, false, startTimeNanos);
            throw e;
        }
    }

    /**
     * 统一记录 OpenTelemetry 指标。
     *
     * @param provider       LLM 提供商（如 "openai"）
     * @param model          模型名称（如 "gpt-4o"）
     * @param operation      操作类型（"chat" 或 "chatStream"）
     * @param success        是否成功
     * @param startTimeNanos 调用开始时间（纳秒）
     */
    private void recordMetrics(String provider, String model, String operation, boolean success, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0; // 转为秒
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

    // ===== 子类必须实现的核心业务逻辑（无监控、无上下文、无拦截器）=====

    /**
     * 子类实现具体的非流式聊天逻辑。
     * <p>
     * 此方法在拦截器链和监控上下文内部调用，不应包含任何监控或拦截逻辑。
     *
     * @param prompt  经过 preHandle 拦截器处理后的 Prompt
     * @param options 经过 preHandle 拦截器处理后的 ChatOptions
     * @return LLM 响应对象
     */
    public abstract AiMessageResponse doChat(Prompt prompt, ChatOptions options);

    /**
     * 子类实现具体的流式聊天逻辑。
     * <p>
     * 此方法在拦截器链和监控上下文内部调用，不应包含任何监控或拦截逻辑。
     *
     * @param prompt   经过 preHandle 拦截器处理后的 Prompt
     * @param listener 经过 wrapStreamListener 拦截器链包装后的监听器
     * @param options  经过 preHandle 拦截器处理后的 ChatOptions
     */
    public abstract void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options);
}
