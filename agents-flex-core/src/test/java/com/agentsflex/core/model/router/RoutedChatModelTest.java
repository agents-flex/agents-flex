package com.agentsflex.core.model.router;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.exception.ModelOverloadedException;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.chat.RoutedChatModel;
import com.agentsflex.core.model.router.core.RouterException;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import com.agentsflex.core.prompt.SimplePrompt;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoutedChatModelTest {

    private static final SimplePrompt PROMPT = new SimplePrompt("hello");

    @Test
    public void errorResponseFallsThroughToNextEndpoint() {
        FakeModel bad = new FakeModel((p, o) -> AiMessageResponse.error(null, null, "rate limit"));
        FakeModel good = new FakeModel((p, o) -> new AiMessageResponse(null, null, new AiMessage("ok")));
        RoutedChatModel router = router(Arrays.asList(bad, good));
        Assert.assertEquals("ok", router.chat(PROMPT, new ChatOptions()).getMessage().getContent());
        Assert.assertEquals(1, bad.calls.get());
        Assert.assertEquals(1, good.calls.get());
    }

    @Test
    public void rateLimitAndOverloadAreRetryable() {
        FakeModel rate = new FakeModel((p, o) -> {
            throw new ModelRateLimitException("429", 429, "rate_limit", null, 100L);
        });
        FakeModel overload = new FakeModel((p, o) -> {
            throw new ModelOverloadedException("503", 503, "overloaded", null);
        });
        FakeModel good = new FakeModel((p, o) -> new AiMessageResponse(null, null, new AiMessage("ok")));
        Assert.assertEquals("ok", router(Arrays.asList(rate, overload, good)).chat(PROMPT, new ChatOptions()).getMessage().getContent());
    }

    @Test
    public void quotaAndTokenLimitAreNotRetriedOrSwitched() {
        for (RuntimeException error : Arrays.asList(
            new ModelQuotaExceededException("quota", 429, "insufficient_quota", null),
            new TokenLimitExceededException("too long", 400, "context_length_exceeded", null,
                TokenLimitExceededException.Phase.INPUT_CONTEXT))) {
            FakeModel first = new FakeModel((p, o) -> {
                throw error;
            });
            FakeModel second = new FakeModel((p, o) -> new AiMessageResponse(null, null, new AiMessage("ok")));
            try {
                router(Arrays.asList(first, second)).chat(PROMPT, new ChatOptions());
                Assert.fail();
            } catch (RouterException expected) {
                Assert.assertSame(error, expected.getCause());
            }
            Assert.assertEquals(1, first.calls.get());
            Assert.assertEquals(0, second.calls.get());
        }
    }

    @Test
    public void endpointsAreNotRepeatedBeforeAllCandidatesTried() {
        AtomicInteger sequence = new AtomicInteger();
        FakeModel a = new FakeModel((p, o) -> {
            sequence.incrementAndGet();
            throw new ModelRateLimitException("x", 429, null, null, null);
        });
        FakeModel b = new FakeModel((p, o) -> {
            sequence.incrementAndGet();
            throw new ModelRateLimitException("x", 429, null, null, null);
        });
        FakeModel c = new FakeModel((p, o) -> {
            sequence.incrementAndGet();
            throw new ModelRateLimitException("x", 429, null, null, null);
        });
        try {
            router(Arrays.asList(a, b, c)).chat(PROMPT, new ChatOptions());
            Assert.fail();
        } catch (RouterException ignored) {
        }
        Assert.assertEquals(3, sequence.get());
    }

    @Test
    public void tokenLimitDoesNotRecordCircuitFailure() {
        FakeModel first = new FakeModel((p, o) -> {
            throw new TokenLimitExceededException("x", 400, null, null, null);
        });
        FakeModel second = new FakeModel((p, o) -> new AiMessageResponse(null, null, new AiMessage("ok")));
        CountingBreaker breaker = new CountingBreaker();
        RoutedChatModel router = new RoutedChatModel(Arrays.asList(new ModelEndpoint<>(first), new ModelEndpoint<>(second)),
            new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(0), breaker);
        try {
            router.chat(PROMPT, new ChatOptions());
            Assert.fail();
        } catch (RouterException ignored) {
        }
        Assert.assertEquals(0, breaker.failures.get());
    }

    @Test
    public void noMatchingTagsProducesRouterException() {
        ModelEndpoint<ChatModel> endpoint = new ModelEndpoint<>(new FakeModel((p, o) ->
            new AiMessageResponse(null, null, new AiMessage("ok"))));
        endpoint.addTags(new java.util.HashSet<>(Arrays.asList("cheap")));
        RoutedChatModel router = new RoutedChatModel(Arrays.asList(endpoint), new LeastActiveLoadBalancer<>(),
            new DefaultRetryPolicy(2), new CountingBreaker());
        ChatOptions options = new ChatOptions();
        options.putMetadata("modelTags", new java.util.HashSet<>(Arrays.asList("reasoning")));
        try {
            router.chat(PROMPT, options);
            Assert.fail();
        } catch (RouterException expected) {
            Assert.assertTrue(expected.getMessage().contains("No available"));
        }
    }

    @Test
    public void nullStreamListenerIsRejected() {
        try {
            RoutedChatModel router = new RoutedChatModel(Arrays.asList(new ModelEndpoint<ChatModel>(new FakeModel((p, o) -> null))),
                new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(0), new CountingBreaker());
            router.chatStream(PROMPT, null, new ChatOptions());
            Assert.fail();
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("listener"));
        }
    }

    @Test
    public void streamFailsBeforeMessageAndSwitchesOnce() {
        FakeModel bad = new FakeModel((p, o, l) -> l.onError(new StreamContext(null, null, null),
            new ModelRateLimitException("x", 429, null, null, null)));
        FakeModel good = new FakeModel((p, o, l) -> {
            StreamContext c = new StreamContext(null, null, null);
            l.onMessage(c, new AiMessageResponse(null, null, new AiMessage("ok")));
            l.onClose(c);
        });
        AtomicInteger messages = new AtomicInteger(), opens = new AtomicInteger();
        router(Arrays.asList(bad, good)).chatStream(PROMPT, new StreamResponseListener() {
            public void onOpen(StreamContext c) {
                opens.incrementAndGet();
            }

            public void onMessage(StreamContext c, AiMessageResponse r) {
                messages.incrementAndGet();
            }
        }, new ChatOptions());
        Assert.assertEquals(1, opens.get());
        Assert.assertEquals(1, messages.get());
    }

    @Test
    public void streamFailureAfterMessageDoesNotSwitch() {
        FakeModel first = new FakeModel((p, o, l) -> {
            StreamContext c = new StreamContext(null, null, null);
            l.onMessage(c, new AiMessageResponse(null, null, new AiMessage("part")));
            l.onError(c, new RuntimeException("broken"));
            l.onClose(c);
        });
        FakeModel second = new FakeModel((p, o) -> new AiMessageResponse(null, null, new AiMessage("other")));
        AtomicInteger errors = new AtomicInteger(), closes = new AtomicInteger();
        router(Arrays.asList(first, second)).chatStream(PROMPT, new StreamResponseListener() {
            public void onMessage(StreamContext c, AiMessageResponse r) {
            }

            public void onError(StreamContext c, Throwable e) {
                errors.incrementAndGet();
            }

            public void onClose(StreamContext c) {
                closes.incrementAndGet();
            }
        }, new ChatOptions());
        Assert.assertEquals(1, errors.get());
        Assert.assertEquals(1, closes.get());
        Assert.assertEquals(0, second.streamCalls.get());
    }

    private RoutedChatModel router(List<FakeModel> models) {
        List<ModelEndpoint<ChatModel>> endpoints = new java.util.ArrayList<>(Arrays.asList(
            new ModelEndpoint<ChatModel>(models.get(0)),
            new ModelEndpoint<ChatModel>(models.get(1)),
            models.size() > 2 ? new ModelEndpoint<ChatModel>(models.get(2)) : null));
        endpoints.removeIf(java.util.Objects::isNull);
        return new RoutedChatModel(endpoints, new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(2), new CountingBreaker());
    }

    private static class FakeModel implements ChatModel {
        interface Sync {
            AiMessageResponse call(com.agentsflex.core.prompt.Prompt p, ChatOptions o);
        }

        interface Stream {
            void call(com.agentsflex.core.prompt.Prompt p, ChatOptions o, StreamResponseListener l);
        }

        final Sync sync;
        Stream stream;
        AtomicInteger calls = new AtomicInteger(), streamCalls = new AtomicInteger();

        FakeModel(Sync sync) {
            this.sync = sync;
        }

        FakeModel(Stream stream) {
            this.sync = (p, o) -> null;
            this.stream = stream;
        }

        public AiMessageResponse chat(com.agentsflex.core.prompt.Prompt p, ChatOptions o) {
            calls.incrementAndGet();
            return sync.call(p, o);
        }

        public void chatStream(com.agentsflex.core.prompt.Prompt p, StreamResponseListener l, ChatOptions o) {
            streamCalls.incrementAndGet();
            stream.call(p, o, l);
        }
    }

    private static class CountingBreaker implements CircuitBreaker<ChatModel> {
        AtomicInteger failures = new AtomicInteger();

        public boolean allowRequest(ModelEndpoint<ChatModel> e) {
            return true;
        }

        public void recordSuccess(ModelEndpoint<ChatModel> e) {
        }

        public void recordFailure(ModelEndpoint<ChatModel> e) {
            failures.incrementAndGet();
        }
    }
}
