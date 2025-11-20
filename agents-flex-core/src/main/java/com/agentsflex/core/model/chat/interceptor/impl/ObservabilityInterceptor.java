//package com.agentsflex.core.model.chat.interceptor.impl;
//
//import com.agentsflex.core.message.AiMessage;
//import com.agentsflex.core.model.chat.*;
//import com.agentsflex.core.model.chat.interceptor.ChatInterceptor;
//import com.agentsflex.core.model.chat.interceptor.StreamChain;
//import com.agentsflex.core.model.chat.interceptor.SyncChain;
//import com.agentsflex.core.model.chat.response.AiMessageResponse;
//import com.agentsflex.core.model.client.StreamContext;
//import com.agentsflex.core.observability.Observability;
//import com.agentsflex.core.prompt.Prompt;
//import io.opentelemetry.api.common.AttributeKey;
//import io.opentelemetry.api.common.Attributes;
//import io.opentelemetry.api.metrics.DoubleHistogram;
//import io.opentelemetry.api.metrics.LongCounter;
//import io.opentelemetry.api.metrics.Meter;
//import io.opentelemetry.api.trace.Span;
//import io.opentelemetry.api.trace.StatusCode;
//import io.opentelemetry.api.trace.Tracer;
//import io.opentelemetry.context.Scope;
//
//import java.util.concurrent.atomic.AtomicBoolean;
//
//public class ObservabilityInterceptor implements ChatInterceptor {
//
//    private static final Tracer TRACER = Observability.getTracer();
//    private static final Meter METER = Observability.getMeter();
//
//    private static final LongCounter LLM_REQUEST_COUNT = METER.counterBuilder("llm.request.count")
//        .setDescription("Total number of LLM requests")
//        .build();
//
//    private static final DoubleHistogram LLM_LATENCY_HISTOGRAM = METER.histogramBuilder("llm.request.latency")
//        .setDescription("LLM request latency in seconds")
//        .setUnit("s")
//        .build();
//
//    private static final LongCounter LLM_ERROR_COUNT = METER.counterBuilder("llm.request.error.count")
//        .setDescription("Total number of LLM request errors")
//        .build();
//
//    private static final int MAX_RESPONSE_LENGTH_FOR_SPAN = 500;
//
//    @Override
//    public AiMessageResponse intercept(
//        BaseChatModel<?> chatModel,
//        Prompt prompt,
//        ChatOptions options,
//        ChatContext context,
//        SyncChain next) {
//
//        ChatConfig config = chatModel.getConfig();
//
//        if (config == null || !config.isObservabilityEnabled()) {
//            return next.proceed(chatModel, prompt, options, context);
//        }
//
//        String provider = config.getProvider();
//        String model = config.getModel();
//        String operation = "chat";
//
//        Span span = TRACER.spanBuilder(provider + "." + operation)
//            .setAttribute("llm.provider", provider)
//            .setAttribute("llm.model", model)
//            .setAttribute("llm.operation", operation)
//            .startSpan();
//
//
//        long startTimeNanos = System.nanoTime();
//
//        try (Scope ignored = span.makeCurrent()) {
//
//            AiMessageResponse response = next.proceed(chatModel, prompt, options, context);
//            boolean success = (response != null) && !response.isError();
//
//            if (success) {
//                enrichSpan(span, response);
//            }
//
//            recordMetrics(provider, model, operation, success, startTimeNanos);
//            return response;
//
//        } catch (Exception e) {
//            recordError(span, e, provider, model, operation, startTimeNanos);
//            throw e;
//        } finally {
//            span.end();
//        }
//    }
//
//    @Override
//    public void interceptStream(
//        BaseChatModel<?> chatModel,
//        Prompt prompt,
//        ChatOptions options,
//        StreamResponseListener originalListener,
//        ChatContext context,
//        StreamChain next) {
//
//        ChatConfig config = chatModel.getConfig();
//
//        if (config == null || !config.isObservabilityEnabled()) {
//            next.proceed(chatModel, prompt, options, originalListener, context);
//            return;
//        }
//
//        String provider = config.getProvider();
//        String model = config.getModel();
//        String operation = "chatStream";
//
//        Span span = TRACER.spanBuilder(provider + "." + operation)
//            .setAttribute("llm.provider", provider)
//            .setAttribute("llm.model", model)
//            .setAttribute("llm.operation", operation)
//            .startSpan();
//
//        Scope scope = span.makeCurrent();
//        long startTimeNanos = System.nanoTime();
//
//        AtomicBoolean recorded = new AtomicBoolean(false);
//
//        StreamResponseListener wrappedListener = new StreamResponseListener() {
//            private AiMessageResponse lastResponse;
//            private Throwable lastError;
//
//            @Override
//            public void onStart(StreamContext context) {
//                originalListener.onStart(context);
//            }
//
//            @Override
//            public void onMessage(StreamContext context, AiMessageResponse response) {
//                lastResponse = response;
//                originalListener.onMessage(context, response);
//            }
//
//            @Override
//            public void onMatchedFunction(String functionName, StreamContext context) {
//                span.setAttribute("llm.function_call", functionName);
//                originalListener.onMatchedFunction(functionName, context);
//            }
//
//            @Override
//            public void onFailure(StreamContext context, Throwable throwable) {
//                lastError = throwable;
//                safeRecord(false);
//                originalListener.onFailure(context, throwable);
//            }
//
//            @Override
//            public void onStop(StreamContext context) {
//                boolean success = (lastError == null) && lastResponse != null && !lastResponse.isError();
//                if (success) {
//                    enrichSpan(span, lastResponse);
//                }
//                safeRecord(success);
//                originalListener.onStop(context);
//            }
//
//            private void safeRecord(boolean success) {
//                if (recorded.compareAndSet(false, true)) {
//                    if (lastError != null) {
//                        span.setStatus(StatusCode.ERROR, lastError.getMessage());
//                        span.recordException(lastError);
//                    }
//                    span.end();
//                    scope.close();
//                    recordMetrics(provider, model, operation, success, startTimeNanos);
//                }
//            }
//        };
//
//        try {
//            next.proceed(chatModel, prompt, options, wrappedListener, context);
//        } catch (Exception e) {
//            if (recorded.compareAndSet(false, true)) {
//                recordError(span, e, provider, model, operation, startTimeNanos);
//            }
//            scope.close();
//            throw e;
//        }
//    }
//
//    private void enrichSpan(Span span, AiMessageResponse response) {
//        AiMessage msg = response.getMessage();
//        if (msg != null) {
//            span.setAttribute("llm.total_tokens", msg.getEffectiveTotalTokens());
//            String content = msg.getContent();
//            if (content != null) {
//                span.setAttribute("llm.response",
//                    content.substring(0, Math.min(content.length(), MAX_RESPONSE_LENGTH_FOR_SPAN)));
//            }
//        }
//    }
//
//    private void recordMetrics(String provider, String model, String operation, boolean success, long startTimeNanos) {
//        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
//        Attributes attrs = Attributes.of(
//            AttributeKey.stringKey("llm.provider"), provider,
//            AttributeKey.stringKey("llm.model"), model,
//            AttributeKey.stringKey("llm.operation"), operation,
//            AttributeKey.stringKey("llm.success"), String.valueOf(success)
//        );
//        LLM_REQUEST_COUNT.add(1, attrs);
//        LLM_LATENCY_HISTOGRAM.record(latencySeconds, attrs);
//        if (!success) {
//            LLM_ERROR_COUNT.add(1, attrs);
//        }
//    }
//
//    private void recordError(Span span, Exception e, String provider, String model, String operation, long startTimeNanos) {
//        span.setStatus(StatusCode.ERROR, e.getMessage());
//        span.recordException(e);
//        span.end();
//        // Scope 会在 finally 或 safeRecord 中关闭
//        recordMetrics(provider, model, operation, false, startTimeNanos);
//    }
//}
