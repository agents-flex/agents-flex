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
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

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
}
