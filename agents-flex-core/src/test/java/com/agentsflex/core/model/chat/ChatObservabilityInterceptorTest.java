package com.agentsflex.core.model.chat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
