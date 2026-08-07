package com.agentsflex.core.model.router;

import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.breaker.DefaultCircuitBreaker;
import com.agentsflex.core.model.router.core.AbstractModelRouter;
import com.agentsflex.core.model.router.core.RouterException;
import com.agentsflex.core.model.router.endpoint.EndpointStatus;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class AbstractModelRouterTest {

    @Test
    public void downEndpointCanEnterHalfOpenAndRecover() {
        ModelEndpoint<String> endpoint = new ModelEndpoint<>("primary");
        TestRouter router = router(Collections.singletonList(endpoint), new DefaultRetryPolicy(1),
            new DefaultCircuitBreaker<>(1, 0));
        AtomicInteger attempts = new AtomicInteger();

        String result = router.call(model -> {
            if (attempts.getAndIncrement() == 0) {
                throw new ModelRateLimitException("limited", 429, null, null, null);
            }
            return "ok";
        });

        Assert.assertEquals("ok", result);
        Assert.assertEquals(2, attempts.get());
        Assert.assertEquals(EndpointStatus.UP, endpoint.getStatus());
    }

    @Test
    public void halfOpenAllowsOnlyOneConcurrentProbe() throws Exception {
        DefaultCircuitBreaker<String> breaker = new DefaultCircuitBreaker<>(1, 0);
        ModelEndpoint<String> endpoint = new ModelEndpoint<>("primary");
        endpoint.setStatus(EndpointStatus.DOWN);
        endpoint.getLastFailureTime().set(System.currentTimeMillis() - 1);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        Thread[] threads = new Thread[8];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (breaker.allowRequest(endpoint)) allowed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) thread.join();

        Assert.assertEquals(1, allowed.get());
        Assert.assertEquals(EndpointStatus.HALF_OPEN, endpoint.getStatus());
    }

    @Test
    public void constructorCopiesEndpointListAndRejectsInvalidConfiguration() {
        ArrayList<ModelEndpoint<String>> endpoints = new ArrayList<>();
        endpoints.add(new ModelEndpoint<>("primary"));
        TestRouter router = router(endpoints, new DefaultRetryPolicy(0), new AcceptingBreaker());
        endpoints.clear();

        Assert.assertEquals("primary", router.call(model -> model));
        assertIllegalArgument(() -> router(Collections.<ModelEndpoint<String>>emptyList(), new DefaultRetryPolicy(0), new AcceptingBreaker()));
        assertNullPointer(() -> router(Arrays.asList(new ModelEndpoint<String>("primary"), null), new DefaultRetryPolicy(0), new AcceptingBreaker()));
    }

    @Test
    public void duplicateEndpointIdIsOnlyOneLogicalCandidatePerRound() {
        ModelEndpoint<String> first = new ModelEndpoint<>("same", "first");
        ModelEndpoint<String> duplicate = new ModelEndpoint<>("same", "duplicate");
        TestRouter router = router(Arrays.asList(first, duplicate), new DefaultRetryPolicy(1), new AcceptingBreaker());
        AtomicInteger calls = new AtomicInteger();

        try {
            router.call(model -> {
                calls.incrementAndGet();
                throw new RuntimeException(model);
            });
            Assert.fail();
        } catch (RouterException ignored) {
        }
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void nullResultIsTreatedAsEndpointFailureAndFallsBack() {
        ModelEndpoint<String> first = new ModelEndpoint<>("first");
        ModelEndpoint<String> second = new ModelEndpoint<>("second");
        CountingBreaker breaker = new CountingBreaker();
        TestRouter router = router(Arrays.asList(first, second), new DefaultRetryPolicy(1), breaker);

        Assert.assertEquals("second", router.call(model -> "first".equals(model) ? null : model));
        Assert.assertEquals(1, breaker.failures.get());
    }

    @Test
    public void allFailuresAreRetainedOnRouterException() {
        TestRouter router = router(Arrays.asList(new ModelEndpoint<>("a"), new ModelEndpoint<>("b"), new ModelEndpoint<>("c")),
            new DefaultRetryPolicy(2), new AcceptingBreaker());
        try {
            router.call(model -> { throw new RuntimeException(model); });
            Assert.fail();
        } catch (RouterException e) {
            Assert.assertEquals("c", e.getCause().getMessage());
            Assert.assertEquals(2, e.getSuppressed().length);
            Assert.assertEquals("a", e.getSuppressed()[0].getMessage());
            Assert.assertEquals("b", e.getSuppressed()[1].getMessage());
        }
    }

    @Test
    public void wrappedTokenLimitDoesNotRetryOrOpenCircuit() {
        CountingBreaker breaker = new CountingBreaker();
        TestRouter router = router(Arrays.asList(new ModelEndpoint<>("a"), new ModelEndpoint<>("b")),
            new DefaultRetryPolicy(2), breaker);
        AtomicInteger calls = new AtomicInteger();
        try {
            router.call(model -> {
                calls.incrementAndGet();
                throw new CompletionException(new TokenLimitExceededException("too long", 400, null, null, null));
            });
            Assert.fail();
        } catch (RouterException ignored) {
        }
        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(0, breaker.failures.get());
    }

    private TestRouter router(java.util.List<ModelEndpoint<String>> endpoints, DefaultRetryPolicy policy,
                              CircuitBreaker<String> breaker) {
        return new TestRouter(endpoints, policy, breaker);
    }

    private static void assertIllegalArgument(Runnable action) {
        try { action.run(); Assert.fail(); } catch (IllegalArgumentException ignored) { }
    }

    private static void assertNullPointer(Runnable action) {
        try { action.run(); Assert.fail(); } catch (NullPointerException ignored) { }
    }

    private static class TestRouter extends AbstractModelRouter<String> {
        TestRouter(java.util.List<ModelEndpoint<String>> endpoints, DefaultRetryPolicy policy, CircuitBreaker<String> breaker) {
            super(endpoints, new LeastActiveLoadBalancer<>(), policy, breaker);
        }
        String call(com.agentsflex.core.model.router.core.ModelInvoker<String, String> invoker) {
            return execute(invoker, Collections.emptySet());
        }
    }

    private static class AcceptingBreaker implements CircuitBreaker<String> {
        public boolean allowRequest(ModelEndpoint<String> endpoint) { return true; }
        public void recordSuccess(ModelEndpoint<String> endpoint) { }
        public void recordFailure(ModelEndpoint<String> endpoint) { }
    }

    private static class CountingBreaker extends AcceptingBreaker {
        final AtomicInteger failures = new AtomicInteger();
        @Override public void recordFailure(ModelEndpoint<String> endpoint) { failures.incrementAndGet(); }
    }
}
