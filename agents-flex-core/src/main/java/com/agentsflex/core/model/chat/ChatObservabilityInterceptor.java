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

public class ChatObservabilityInterceptor implements ChatInterceptor {
    private static final int MAX_RESPONSE_LENGTH_FOR_SPAN = 500;

    private static final class Instruments {
        private static final Tracer TRACER = Observability.getTracer();
        private static final Meter METER = Observability.getMeter();
        private static final LongCounter REQUEST_COUNT = METER.counterBuilder("llm.request.count")
            .setDescription("Total number of LLM requests")
            .build();
        private static final DoubleHistogram LATENCY = METER.histogramBuilder("llm.request.latency")
            .setDescription("LLM request latency in seconds")
            .setUnit("s")
            .build();
        private static final LongCounter ERROR_COUNT = METER.counterBuilder("llm.request.error.count")
            .setDescription("Total number of LLM request errors")
            .build();
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
        Span span = startSpan(provider, model, operation);
        long startTimeNanos = System.nanoTime();

        try (Scope ignored = span.makeCurrent()) {
            AiMessageResponse response = chain.proceed(chatModel, context);
            boolean success = response != null && !response.isError();
            if (success) {
                enrichSpan(span, response.getMessage());
            } else {
                span.setStatus(StatusCode.ERROR, response == null ? "Empty model response" : response.getErrorMessage());
            }
            recordMetrics(provider, model, operation, success, startTimeNanos);
            return response;
        } catch (RuntimeException | Error error) {
            recordError(span, error, provider, model, operation, startTimeNanos);
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
        Context parentContext = Context.current();
        Span span = Instruments.TRACER.spanBuilder(provider + "." + operation)
            .setParent(parentContext)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .setAttribute("gen_ai.provider.name", provider)
            .setAttribute("gen_ai.request.model", model)
            .setAttribute("gen_ai.operation.name", "chat")
            .startSpan();
        long startTimeNanos = System.nanoTime();
        AtomicBoolean recorded = new AtomicBoolean(false);

        StreamResponseListener wrappedListener = new StreamResponseListener() {
            @Override
            public void onStart(StreamContext streamContext) {
                try (Scope ignored = span.makeCurrent()) {
                    originalListener.onStart(streamContext);
                }
            }

            @Override
            public void onMessage(StreamContext streamContext, AiMessageResponse response) {
                try (Scope ignored = span.makeCurrent()) {
                    originalListener.onMessage(streamContext, response);
                }
            }

            @Override
            public void onFailure(StreamContext streamContext, Throwable throwable) {
                try (Scope ignored = span.makeCurrent()) {
                    originalListener.onFailure(streamContext, throwable);
                } finally {
                    finish(false, throwable);
                }
            }

            @Override
            public void onStop(StreamContext streamContext) {
                boolean success = !streamContext.isError();
                Throwable callbackError = null;
                try (Scope ignored = span.makeCurrent()) {
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
                recordMetrics(provider, model, operation, success, startTimeNanos);
                span.end();
            }
        };

        try (Scope ignored = span.makeCurrent()) {
            chain.proceed(chatModel, context, wrappedListener);
        } catch (RuntimeException | Error error) {
            if (recorded.compareAndSet(false, true)) {
                recordError(span, error, provider, model, operation, startTimeNanos);
                span.end();
            }
            throw error;
        }
    }

    private static boolean isEnabled(BaseChatConfig config) {
        return config != null && config.isObservabilityEnabled() && Observability.isEnabled();
    }

    private static Span startSpan(String provider, String model, String operation) {
        return Instruments.TRACER.spanBuilder(provider + "." + operation)
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
        if (!Observability.isContentCaptureEnabled()) {
            return;
        }
        String content = message.getFullContent() != null ? message.getFullContent() : message.getContent();
        if (content != null) {
            span.setAttribute("llm.response", SensitiveDataSanitizer.truncate(content, MAX_RESPONSE_LENGTH_FOR_SPAN));
        }
    }

    private static void recordMetrics(String provider, String model, String operation,
                                      boolean success, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        Attributes attrs = baseMetricAttributes(provider, model, operation, success).build();
        Instruments.REQUEST_COUNT.add(1, attrs);
        Instruments.LATENCY.record(latencySeconds, attrs);
        if (!success) {
            Instruments.ERROR_COUNT.add(1, attrs);
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

    private static void recordError(Span span, Throwable error, String provider, String model,
                                    String operation, long startTimeNanos) {
        span.setStatus(StatusCode.ERROR, error.getMessage());
        span.recordException(error);
        recordMetrics(provider, model, operation, false, startTimeNanos);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }
}
