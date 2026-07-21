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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.observability.ObservabilityRuntime;
import com.agentsflex.core.observability.SensitiveDataSanitizer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ChatModel 的 OpenTelemetry 拦截器，负责同步与流式模型调用的 Span、请求计数、耗时和错误指标。
 *
 * <p>拦截器不固定绑定全局 Tracer/Meter，而是在每次请求开始时读取当前 {@link ObservabilityRuntime}。
 * 因此宿主系统可以在调用外层使用 {@link Observability#useRuntime(ObservabilityRuntime)}
 * 选择本次执行的遥测后端。</p>
 */
public class ChatObservabilityInterceptor implements ChatInterceptor {
    /** 模型响应正文写入 Span 属性时允许保留的最大字符数。 */
    private static final int MAX_RESPONSE_LENGTH_FOR_SPAN = 500;

    /**
     * runtime 到模型埋点 instrument 的弱键缓存。
     * OTel instrument 创建后可安全复用；按 runtime 缓存保证不同 Route 使用各自 Provider，弱键则使已经
     * 下线且无其他引用的 Route 不会仅因静态缓存而长期滞留。
     */
    private static final Map<ObservabilityRuntime, Instruments> INSTRUMENTS = new WeakHashMap<>();

    /** 某个 ObservabilityRuntime 专属的一组模型 Tracer 和 Metrics instrument。 */
    private static final class Instruments {
        /** 创建同步和流式模型 Span 的 Tracer。 */
        private final Tracer tracer;

        /** 记录全部模型请求次数的 Counter，指标名为 llm.request.count。 */
        private final LongCounter requestCount;

        /** 记录端到端模型请求耗时的 Histogram，单位为秒。 */
        private final DoubleHistogram latency;

        /** 只记录失败模型请求次数的 Counter。 */
        private final LongCounter errorCount;

        private Instruments(ObservabilityRuntime runtime) {
            this.tracer = runtime.getTracer();
            Meter meter = runtime.getMeter();
            this.requestCount = meter.counterBuilder("llm.request.count")
            .setDescription("Total number of LLM requests")
            .build();
            this.latency = meter.histogramBuilder("llm.request.latency")
                .setDescription("LLM request latency in seconds")
                .setUnit("s")
                .build();
            this.errorCount = meter.counterBuilder("llm.request.error.count")
                .setDescription("Total number of LLM request errors")
                .build();
        }
    }

    @Override
    public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
        BaseChatConfig config = chatModel.getConfig();
        if (!isEnabled(config)) {
            return chain.proceed(chatModel, context);
        }

        String provider = valueOrUnknown(config.getProvider());
        String model = valueOrUnknown(config.getModel());
        String operation = "chat";
        Instruments instruments = instruments();
        Span span = startSpan(instruments, provider, model, operation);
        Observability.enrichSpan(span);
        enrichCorrelation(span, context);
        long startTimeNanos = System.nanoTime();

        // 让下游模型客户端创建的 HTTP Span 自动成为当前模型 Span 的子节点。
        try (Scope ignored = span.makeCurrent()) {
            AiMessageResponse response = chain.proceed(chatModel, context);
            boolean success = response != null && !response.isError();
            if (success) {
                enrichSpan(span, response.getMessage());
            } else {
                span.setStatus(StatusCode.ERROR, response == null ? "Empty model response" : response.getErrorMessage());
            }
            recordMetrics(instruments, provider, model, operation, success, startTimeNanos);
            return response;
        } catch (RuntimeException | Error error) {
            recordError(instruments, span, error, provider, model, operation, startTimeNanos);
            throw error;
        } finally {
            span.end();
        }
    }

    @Override
    public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                StreamResponseListener originalListener, StreamChain chain) {
        BaseChatConfig config = chatModel.getConfig();
        if (!isEnabled(config)) {
            chain.proceed(chatModel, context, originalListener);
            return;
        }

        String provider = valueOrUnknown(config.getProvider());
        String model = valueOrUnknown(config.getModel());
        String operation = "chatStream";
        // 流式回调可能在另一个线程且晚于本方法返回，因此必须捕获完整 Context，而不只是捕获 Span。
        // 完整 Context 还包含执行级 TelemetryRoute 和由宿主应用绑定的固定属性。
        Context parentContext = Context.current();
        Instruments instruments = instruments();
        Span span = instruments.tracer.spanBuilder(provider + "." + operation)
            .setParent(parentContext)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .setAttribute("gen_ai.provider.name", provider)
            .setAttribute("gen_ai.request.model", model)
            .setAttribute("gen_ai.operation.name", "chat")
            .startSpan();
        Observability.enrichSpan(span);
        enrichCorrelation(span, context);
        Context spanContext = parentContext.with(span);
        long startTimeNanos = System.nanoTime();
        // 某些客户端可能重复触发 onFailure/onStop，CAS 保证指标与 span.end() 只执行一次。
        AtomicBoolean recorded = new AtomicBoolean(false);

        StreamResponseListener wrappedListener = new StreamResponseListener() {
            @Override
            public void onStart(StreamContext streamContext) {
                try (Scope ignored = spanContext.makeCurrent()) {
                    originalListener.onStart(streamContext);
                }
            }

            @Override
            public void onMessage(StreamContext streamContext, AiMessageResponse response) {
                try (Scope ignored = spanContext.makeCurrent()) {
                    originalListener.onMessage(streamContext, response);
                }
            }

            @Override
            public void onFailure(StreamContext streamContext, Throwable throwable) {
                try (Scope ignored = spanContext.makeCurrent()) {
                    originalListener.onFailure(streamContext, throwable);
                } finally {
                    finish(false, throwable);
                }
            }

            @Override
            public void onStop(StreamContext streamContext) {
                boolean success = !streamContext.isError();
                Throwable callbackError = null;
                try (Scope ignored = spanContext.makeCurrent()) {
                    if (success) {
                        enrichSpan(span, streamContext.getFullMessage());
                    }
                    originalListener.onStop(streamContext);
                } catch (RuntimeException | Error error) {
                    callbackError = error;
                    throw error;
                } finally {
                    finish(success && callbackError == null, callbackError);
                }
            }

            private void finish(boolean success, Throwable error) {
                if (!recorded.compareAndSet(false, true)) {
                    return;
                }
                if (error != null) {
                    span.setStatus(StatusCode.ERROR, error.getMessage());
                    span.recordException(error);
                } else if (!success) {
                    span.setStatus(StatusCode.ERROR, "Streaming model request failed");
                }
                recordMetrics(instruments, provider, model, operation, success, startTimeNanos);
                span.end();
            }
        };

        // 启动流式请求时也恢复 spanContext，使请求建立阶段产生的 HTTP Span 保持正确父子关系。
        try (Scope ignored = spanContext.makeCurrent()) {
            chain.proceed(chatModel, context, wrappedListener);
        } catch (RuntimeException | Error error) {
            if (recorded.compareAndSet(false, true)) {
                recordError(instruments, span, error, provider, model, operation, startTimeNanos);
                span.end();
            }
            throw error;
        }
    }

    private static boolean isEnabled(BaseChatConfig config) {
        return config != null && config.isObservabilityEnabled() && Observability.isEnabled();
    }

    private static Span startSpan(Instruments instruments, String provider, String model, String operation) {
        return instruments.tracer.spanBuilder(provider + "." + operation)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .setAttribute("gen_ai.provider.name", provider)
            .setAttribute("gen_ai.request.model", model)
            .setAttribute("gen_ai.operation.name", "chat")
            .startSpan();
    }

    private static void enrichSpan(Span span, AiMessage message) {
        if (message == null) {
            return;
        }
        span.setAttribute("llm.total_tokens", message.getEffectiveTotalTokens());
        span.setAttribute("gen_ai.usage.total_tokens", message.getEffectiveTotalTokens());
        // Token 数属于运行元数据，可默认记录；模型正文可能包含敏感数据，只能在显式开启后采集。
        if (!Observability.isContentCaptureEnabled()) {
            return;
        }
        String content = message.getFullContent() != null ? message.getFullContent() : message.getContent();
        if (content != null) {
            span.setAttribute("llm.response", SensitiveDataSanitizer.truncate(content, MAX_RESPONSE_LENGTH_FOR_SPAN));
        }
    }

    private static void enrichCorrelation(Span span, ChatContext context) {
        if (context == null) {
            return;
        }
        if (context.getConversationId() != null) {
            span.setAttribute("gen_ai.conversation.id", String.valueOf(context.getConversationId()));
        }
        if (context.getAccountId() != null) {
            span.setAttribute("enduser.id", String.valueOf(context.getAccountId()));
        }
    }

    private static void recordMetrics(Instruments instruments, String provider, String model, String operation,
                                      boolean success, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        Attributes attrs = baseMetricAttributes(provider, model, operation, success).build();
        instruments.requestCount.add(1, attrs);
        instruments.latency.record(latencySeconds, attrs);
        if (!success) {
            instruments.errorCount.add(1, attrs);
        }
    }

    private static AttributesBuilder baseMetricAttributes(String provider, String model,
                                                           String operation, boolean success) {
        return Attributes.builder()
            .put("llm.provider", provider)
            .put("llm.model", model)
            .put("llm.operation", operation)
            .put("llm.success", success)
            .put("gen_ai.provider.name", provider)
            .put("gen_ai.request.model", model)
            .put("gen_ai.operation.name", "chat");
    }

    private static void recordError(Instruments instruments, Span span, Throwable error, String provider, String model,
                                    String operation, long startTimeNanos) {
        span.setStatus(StatusCode.ERROR, error.getMessage());
        span.recordException(error);
        recordMetrics(instruments, provider, model, operation, false, startTimeNanos);
    }

    private static Instruments instruments() {
        ObservabilityRuntime runtime = Observability.currentRuntime();
        // WeakHashMap 不是线程安全容器，查找和创建必须在同一临界区，避免并发重复注册 instrument。
        synchronized (INSTRUMENTS) {
            Instruments instruments = INSTRUMENTS.get(runtime);
            if (instruments == null) {
                instruments = new Instruments(runtime);
                INSTRUMENTS.put(runtime, instruments);
            }
            return instruments;
        }
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }
}
