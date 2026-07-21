package com.agentsflex.core.model.chat;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.observability.ObservabilityAttributeKeys;
import com.agentsflex.core.observability.ObservabilityRuntime;
import com.agentsflex.core.observability.SpanProcessingMode;
import com.agentsflex.core.observability.TelemetryDestination;
import com.agentsflex.core.observability.TelemetryRoute;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChatObservabilityInterceptorTest {

    @Test
    public void shouldRestoreCallerContextBeforeAsyncCallbacks() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test");
        config.setModel("test-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        Span parent = Span.wrap(SpanContext.create(
            "0123456789abcdef0123456789abcdef",
            "0123456789abcdef",
            TraceFlags.getSampled(),
            TraceState.getDefault()
        ));

        try (Scope ignored = parent.makeCurrent()) {
            new ChatObservabilityInterceptor().interceptStream(
                model,
                new ChatContext(),
                (streamContext, response) -> {
                },
                (chatModel, context, listener) -> {
                    // Simulate an asynchronous client: return before invoking terminal callbacks.
                }
            );

            assertEquals(parent.getSpanContext(), Span.current().getSpanContext());
        }
    }

    @Test
    public void shouldRestoreRuntimeInsideAsyncCallbacks() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test");
        config.setModel("test-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        TestRuntime runtime = new TestRuntime();
        AtomicReference<StreamResponseListener> wrapped = new AtomicReference<>();
        AtomicReference<ObservabilityRuntime> callbackRuntime = new AtomicReference<>();

        try (Scope ignored = Observability.useRuntime(runtime)) {
            new ChatObservabilityInterceptor().interceptStream(
                model,
                new ChatContext(),
                new StreamResponseListener() {
                    @Override
                    public void onMessage(StreamContext context,
                                          com.agentsflex.core.model.chat.response.AiMessageResponse response) {
                    }

                    @Override
                    public void onStop(StreamContext context) {
                        callbackRuntime.set(Observability.currentRuntime());
                    }
                },
                (chatModel, context, listener) -> wrapped.set(listener)
            );
        }

        wrapped.get().onStop(new StreamContext(model, new ChatContext(), null));
        assertSame(runtime, callbackRuntime.get());
    }

    @Test
    public void shouldCopyBotConversationAccountAndTurnToChatContext() {
        ChatOptions options = ChatOptions.builder()
            .contextBotId("bot-1")
            .contextConversationId("conversation-2")
            .contextAccountId("account-3")
            .contextTurnId("turn-4")
            .build();

        try (ChatContextHolder.ChatContextScope ignored =
                 ChatContextHolder.beginChat(null, options, null, null)) {
            ChatContext context = ChatContextHolder.currentContext();
            assertEquals("bot-1", context.getBotId());
            assertEquals("conversation-2", context.getConversationId());
            assertEquals("account-3", context.getAccountId());
            assertEquals("turn-4", context.getTurnId());
        }
    }

    @Test
    public void shouldAddUnifiedCorrelationAttributesToChatSpan() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test");
        config.setModel("test-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        ChatContext context = new ChatContext();
        context.setBotId("bot-1");
        context.setConversationId("conversation-2");
        context.setAccountId("account-3");
        context.setTurnId("turn-4");
        CollectingSpanExporter exporter = new CollectingSpanExporter();

        try (TelemetryRoute route = TelemetryRoute.builder("chat-correlation")
            .addDestination(TelemetryDestination.builder("test")
                .spanExporter(exporter)
                .spanProcessingMode(SpanProcessingMode.SIMPLE)
                .build())
            .build();
             Scope ignored = Observability.useRuntime(route)) {
            new ChatObservabilityInterceptor().intercept(model, context,
                (chatModel, chatContext) -> new AiMessageResponse(
                    chatContext, "ok", new AiMessage("ok")));
        }

        assertEquals(1, exporter.spans.size());
        SpanData span = exporter.spans.get(0);
        assertEquals("bot-1", span.getAttributes().get(ObservabilityAttributeKeys.BOT_ID));
        assertEquals("conversation-2", span.getAttributes().get(ObservabilityAttributeKeys.CONVERSATION_ID));
        assertEquals("account-3", span.getAttributes().get(ObservabilityAttributeKeys.ACCOUNT_ID));
        assertEquals("turn-4", span.getAttributes().get(ObservabilityAttributeKeys.TURN_ID));
    }

    @Test
    public void shouldUseCanonicalGenAiAttributesWithoutLegacyAliases() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test-provider");
        config.setModel("default-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        ChatOptions options = ChatOptions.builder()
            .model("request-model")
            .maxTokens(512)
            .temperature(0.2f)
            .topP(0.8f)
            .topK(20)
            .stop(Arrays.asList("END", "STOP"))
            .build();
        ChatContext context = new ChatContext();
        context.setOptions(options);
        AiMessage message = new AiMessage("ok");
        message.setPromptTokens(12);
        message.setLocalPromptTokens(99);
        message.setCompletionTokens(7);
        message.setLocalCompletionTokens(88);
        message.setFinishReason("stop");
        CollectingSpanExporter exporter = new CollectingSpanExporter();

        try (TelemetryRoute route = TelemetryRoute.builder("canonical-gen-ai")
            .addDestination(TelemetryDestination.builder("test")
                .spanExporter(exporter)
                .spanProcessingMode(SpanProcessingMode.SIMPLE)
                .build())
            .build();
             Scope ignored = Observability.useRuntime(route)) {
            new ChatObservabilityInterceptor().intercept(model, context,
                (chatModel, chatContext) -> new AiMessageResponse(chatContext, "ok", message));
        }

        assertEquals(1, exporter.spans.size());
        SpanData span = exporter.spans.get(0);
        assertEquals("test-provider", span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_PROVIDER_NAME));
        assertEquals("request-model", span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_MODEL));
        assertEquals("chat", span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_OPERATION_NAME));
        assertEquals(Long.valueOf(12),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(Long.valueOf(7),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(Collections.singletonList("stop"),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_RESPONSE_FINISH_REASONS));
        assertEquals(Long.valueOf(512),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_MAX_TOKENS));
        assertEquals(Double.valueOf(0.2f),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_TEMPERATURE));
        assertEquals(Double.valueOf(0.8f),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_TOP_P));
        assertEquals(Long.valueOf(20), span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_TOP_K));
        assertEquals(Arrays.asList("END", "STOP"),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_REQUEST_STOP_SEQUENCES));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("llm.provider")));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("llm.model")));
        assertNull(span.getAttributes().get(AttributeKey.longKey("llm.total_tokens")));
        assertNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens")));
    }

    @Test
    public void shouldFallBackToLocalTokenCountsAndStopReason() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test");
        config.setModel("test-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        AiMessage message = new AiMessage("ok");
        message.setLocalPromptTokens(5);
        message.setLocalCompletionTokens(3);
        message.setStopReason("end_turn");
        CollectingSpanExporter exporter = new CollectingSpanExporter();

        try (TelemetryRoute route = TelemetryRoute.builder("local-token-fallback")
            .addDestination(TelemetryDestination.builder("test")
                .spanExporter(exporter)
                .spanProcessingMode(SpanProcessingMode.SIMPLE)
                .build())
            .build();
             Scope ignored = Observability.useRuntime(route)) {
            new ChatObservabilityInterceptor().intercept(model, new ChatContext(),
                (chatModel, chatContext) -> new AiMessageResponse(chatContext, "ok", message));
        }

        SpanData span = exporter.spans.get(0);
        assertEquals(Long.valueOf(5),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(Long.valueOf(3),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(Collections.singletonList("end_turn"),
            span.getAttributes().get(ObservabilityAttributeKeys.GEN_AI_RESPONSE_FINISH_REASONS));
    }

    @Test
    public void shouldExportCanonicalGenAiMetricsWithoutLegacyMetrics() {
        BaseChatConfig config = new BaseChatConfig();
        config.setProvider("test");
        config.setModel("test-model");
        BaseChatModel<BaseChatConfig> model = new BaseChatModel<BaseChatConfig>(config) {
        };
        AiMessage message = new AiMessage("ok");
        message.setPromptTokens(10);
        message.setCompletionTokens(4);
        CollectingMetricExporter metricExporter = new CollectingMetricExporter();

        try (TelemetryRoute route = TelemetryRoute.builder("canonical-gen-ai-metrics")
            .addDestination(TelemetryDestination.builder("test")
                .metricExporter(metricExporter)
                .build())
            .build()) {
            try (Scope ignored = Observability.useRuntime(route)) {
                new ChatObservabilityInterceptor().intercept(model, new ChatContext(),
                    (chatModel, chatContext) -> new AiMessageResponse(chatContext, "ok", message));
            }
            CompletableResultCode flushResult = route.forceFlush();
            flushResult.join(10, TimeUnit.SECONDS);
            assertTrue(flushResult.isSuccess());
        }

        Set<String> metricNames = metricExporter.metrics.stream()
            .map(MetricData::getName)
            .collect(Collectors.toSet());
        assertTrue(metricNames.contains("agentsflex.gen_ai.request.count"));
        assertTrue(metricNames.contains("gen_ai.client.operation.duration"));
        assertTrue(metricNames.contains("gen_ai.client.token.usage"));
        assertFalse(metricNames.contains("llm.request.count"));
        assertFalse(metricNames.contains("llm.request.latency"));
    }

    private static final class TestRuntime implements ObservabilityRuntime {
        private final OpenTelemetry openTelemetry = OpenTelemetry.noop();

        @Override
        public String getId() {
            return "test-runtime";
        }

        @Override
        public OpenTelemetry getOpenTelemetry() {
            return openTelemetry;
        }

        @Override
        public Tracer getTracer() {
            return openTelemetry.getTracer("test");
        }

        @Override
        public Meter getMeter() {
            return openTelemetry.getMeter("test");
        }
    }

    private static final class CollectingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class CollectingMetricExporter implements MetricExporter {
        private final List<MetricData> metrics = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            this.metrics.addAll(metrics);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporalitySelector.alwaysCumulative()
                .getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return DefaultAggregationSelector.getDefault().getDefaultAggregation(instrumentType);
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
