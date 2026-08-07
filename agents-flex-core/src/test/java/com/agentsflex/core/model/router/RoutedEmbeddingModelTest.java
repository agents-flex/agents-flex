package com.agentsflex.core.model.router;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.model.embedding.EmbeddingOptions;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.embedding.RoutedEmbeddingModel;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.core.RouterException;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import com.agentsflex.core.store.VectorData;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

public class RoutedEmbeddingModelTest {

    @Test
    public void defaultConstructorDelegatesEmbedding() {
        FakeEmbeddingModel first = new FakeEmbeddingModel(() -> vector(3));
        RoutedEmbeddingModel routed = new RoutedEmbeddingModel(Arrays.asList(first));

        VectorData result = routed.embed(Document.of("hello"));

        Assert.assertArrayEquals(new float[]{1, 2, 3}, result.getVector(), 0.001f);
        Assert.assertEquals(1, first.calls.get());
    }

    @Test
    public void nullOptionsStillUsesEmptyTagConstraint() {
        FakeEmbeddingModel model = new FakeEmbeddingModel(() -> vector(2));
        RoutedEmbeddingModel routed = new RoutedEmbeddingModel(
            Arrays.asList(new ModelEndpoint<EmbeddingModel>(model)),
            new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(0), new NoopBreaker());

        Assert.assertNotNull(routed.embed(Document.of("hello"), null));
    }

    @Test
    public void transientFailureSwitchesToAnotherEmbeddingEndpoint() {
        FakeEmbeddingModel failed = new FakeEmbeddingModel(() -> {
            throw new ModelRateLimitException("rate limited", 429, "rate_limit", null, 10L);
        });
        FakeEmbeddingModel backup = new FakeEmbeddingModel(() -> vector(3));
        RoutedEmbeddingModel routed = new RoutedEmbeddingModel(
            Arrays.asList(new ModelEndpoint<EmbeddingModel>(failed), new ModelEndpoint<EmbeddingModel>(backup)),
            new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(1), new NoopBreaker());

        Assert.assertNotNull(routed.embed(Document.of("hello"), new EmbeddingOptions()));
        Assert.assertEquals(1, failed.calls.get());
        Assert.assertEquals(1, backup.calls.get());
    }

    @Test
    public void tagConstraintExcludesIncompatibleEmbeddingEndpoint() {
        FakeEmbeddingModel model = new FakeEmbeddingModel(() -> vector(3));
        ModelEndpoint<EmbeddingModel> endpoint = new ModelEndpoint<>(model);
        endpoint.addTags(new HashSet<>(Arrays.asList("small")));
        RoutedEmbeddingModel routed = new RoutedEmbeddingModel(
            Arrays.asList(endpoint), new LeastActiveLoadBalancer<>(), new DefaultRetryPolicy(1), new NoopBreaker());
        EmbeddingOptions options = new EmbeddingOptions();
        options.putMetadata("modelTags", new HashSet<>(Arrays.asList("large-context")));

        try {
            routed.embed(Document.of("hello"), options);
            Assert.fail("Expected no matching endpoint");
        } catch (RouterException expected) {
            Assert.assertTrue(expected.getMessage().contains("No available"));
        }
        Assert.assertEquals(0, model.calls.get());
    }

    private static VectorData vector(int dimensions) {
        VectorData value = new VectorData();
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) vector[i] = i + 1;
        value.setVector(vector);
        return value;
    }

    private static class FakeEmbeddingModel implements EmbeddingModel {
        interface Action { VectorData run(); }
        final Action action;
        final AtomicInteger calls = new AtomicInteger();

        FakeEmbeddingModel(Action action) { this.action = action; }

        @Override
        public VectorData embed(Document document, EmbeddingOptions options) {
            calls.incrementAndGet();
            return action.run();
        }
    }

    private static class NoopBreaker implements CircuitBreaker<EmbeddingModel> {
        public boolean allowRequest(ModelEndpoint<EmbeddingModel> endpoint) { return true; }
        public void recordSuccess(ModelEndpoint<EmbeddingModel> endpoint) { }
        public void recordFailure(ModelEndpoint<EmbeddingModel> endpoint) { }
    }
}
