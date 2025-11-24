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
import com.agentsflex.core.observability.Observability;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.concurrent.atomic.AtomicBoolean;

public class ChatObservabilityInterceptor implements ChatInterceptor {

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

    private static final int MAX_RESPONSE_LENGTH_FOR_SPAN = 500;

    @Override
    public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {

        ChatConfig config = chatModel.getConfig();

        if (config == null || !config.isObservabilityEnabled() || !Observability.isEnabled()) {
            return chain.proceed(chatModel, context);
        }

        String provider = config.getProvider();
        String model = config.getModel();
        String operation = "chat";

        Span span = TRACER.spanBuilder(provider + "." + operation)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .startSpan();


        long startTimeNanos = System.nanoTime();

        try (Scope ignored = span.makeCurrent()) {

            AiMessageResponse response = chain.proceed(chatModel, context);
            boolean success = (response != null) && !response.isError();

            if (success) {
                enrichSpan(span, response.getMessage());
            }

            recordMetrics(provider, model, operation, success, startTimeNanos);
            return response;

        } catch (Exception e) {
            recordError(span, e, provider, model, operation, startTimeNanos);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void interceptStream(
        BaseChatModel<?> chatModel,
        ChatContext context,
        StreamResponseListener originalListener,
        StreamChain chain) {

        ChatConfig config = chatModel.getConfig();

        if (config == null || !config.isObservabilityEnabled()) {
            chain.proceed(chatModel, context, originalListener);
            return;
        }

        String provider = config.getProvider();
        String model = config.getModel();
        String operation = "chatStream";

        Span span = TRACER.spanBuilder(provider + "." + operation)
            .setAttribute("llm.provider", provider)
            .setAttribute("llm.model", model)
            .setAttribute("llm.operation", operation)
            .startSpan();

        Scope scope = span.makeCurrent();
        long startTimeNanos = System.nanoTime();

        AtomicBoolean recorded = new AtomicBoolean(false);

        StreamResponseListener wrappedListener = new StreamResponseListener() {
            @Override
            public void onStart(StreamContext context) {
                originalListener.onStart(context);
            }

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                originalListener.onMessage(context, response);
            }


            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                safeRecord(false, throwable);
                originalListener.onFailure(context, throwable);
            }

            @Override
            public void onStop(StreamContext context) {
                boolean success = !context.isError();
                if (success) {
                    enrichSpan(span, context.getAiMessage());
                }
                safeRecord(success, null);
                originalListener.onStop(context);
            }

            private void safeRecord(boolean success, Throwable throwable) {
                if (recorded.compareAndSet(false, true)) {
                    if (throwable != null) {
                        span.setStatus(StatusCode.ERROR, throwable.getMessage());
                        span.recordException(throwable);
                    }
                    span.end();
                    scope.close();
                    recordMetrics(provider, model, operation, success, startTimeNanos);
                }
            }
        };

        try {
            chain.proceed(chatModel, context, wrappedListener);
        } catch (Exception e) {
            if (recorded.compareAndSet(false, true)) {
                recordError(span, e, provider, model, operation, startTimeNanos);
            }
            scope.close();
            throw e;
        }
    }

    private void enrichSpan(Span span, AiMessage msg) {
        if (msg != null) {
            span.setAttribute("llm.total_tokens", msg.getEffectiveTotalTokens());
            String content = msg.getContent();
            if (content != null) {
                span.setAttribute("llm.response",
                    content.substring(0, Math.min(content.length(), MAX_RESPONSE_LENGTH_FOR_SPAN)));
            }
        }
    }

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

    private void recordError(Span span, Exception e, String provider, String model, String operation, long startTimeNanos) {
        span.setStatus(StatusCode.ERROR, e.getMessage());
        span.recordException(e);
        span.end();
        // Scope 会在 finally 或 safeRecord 中关闭
        recordMetrics(provider, model, operation, false, startTimeNanos);
    }
}
