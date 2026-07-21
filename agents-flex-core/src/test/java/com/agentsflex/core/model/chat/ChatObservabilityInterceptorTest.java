package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.observability.ObservabilityRuntime;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.junit.Test;

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
}
