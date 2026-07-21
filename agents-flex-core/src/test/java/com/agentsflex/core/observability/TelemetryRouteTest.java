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
package com.agentsflex.core.observability;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
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
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TelemetryRouteTest {

    @Test
    public void shouldFanOutSpansAndMetricsToEveryDestination() {
        CollectingSpanExporter spansA = new CollectingSpanExporter();
        CollectingSpanExporter spansB = new CollectingSpanExporter();
        CollectingMetricExporter metricsA = new CollectingMetricExporter();
        CollectingMetricExporter metricsB = new CollectingMetricExporter();

        try (TelemetryRoute route = TelemetryRoute.builder("fan-out")
            .addDestination(destination("backend-a", spansA, metricsA))
            .addDestination(destination("backend-b", spansB, metricsB))
            .build()) {
            try (Scope ignored = Observability.useRuntime(route, Attributes.of(
                AttributeKey.stringKey("app.subject.id"), "subject-42"))) {
                observedClient().get("https://example.test/resource");
            }
            route.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals(1, spansA.spans.size());
            assertEquals(1, spansB.spans.size());
            assertEquals(spansA.spans.get(0).getTraceId(), spansB.spans.get(0).getTraceId());
            assertEquals("subject-42", spansA.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("app.subject.id")));
            assertEquals("subject-42", spansB.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("app.subject.id")));
            assertFalse(metricsA.metrics.isEmpty());
            assertFalse(metricsB.metrics.isEmpty());
        }
    }

    @Test
    public void shouldIsolateRoutesAndRestoreNestedScope() {
        CollectingSpanExporter exporterA = new CollectingSpanExporter();
        CollectingSpanExporter exporterB = new CollectingSpanExporter();
        ObservabilityRuntime fallback = Observability.currentRuntime();

        try (TelemetryRoute routeA = route("route-a", exporterA);
             TelemetryRoute routeB = route("route-b", exporterB)) {
            try (Scope ignoredA = Observability.useRuntime(routeA,
                Attributes.of(AttributeKey.stringKey("app.subject.id"), "a"))) {
                assertSame(routeA, Observability.currentRuntime());
                observedClient().get("https://a.example.test/resource");

                try (Scope ignoredB = Observability.useRuntime(routeB,
                    Attributes.of(AttributeKey.stringKey("app.subject.id"), "b"))) {
                    assertSame(routeB, Observability.currentRuntime());
                    observedClient().get("https://b.example.test/resource");
                }
                assertSame(routeA, Observability.currentRuntime());
            }
            assertSame(fallback, Observability.currentRuntime());

            assertEquals(1, exporterA.spans.size());
            assertEquals("a", exporterA.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("app.subject.id")));
            assertEquals(1, exporterB.spans.size());
            assertEquals("b", exporterB.spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("app.subject.id")));
        }
    }

    @Test
    public void registryShouldRejectRegistrationAfterClose() {
        TelemetryRouteRegistry registry = new TelemetryRouteRegistry();
        registry.close();
        TelemetryRoute route = route("late-route", new CollectingSpanExporter());

        try (TelemetryRoute ignored = route) {
            try {
                registry.register(route);
                fail("Expected registry to reject a route after close");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("closed"));
            }
        }
    }

    private static TelemetryDestination destination(String id, SpanExporter spans, MetricExporter metrics) {
        return TelemetryDestination.builder(id)
            .spanExporter(spans)
            .spanProcessingMode(SpanProcessingMode.SIMPLE)
            .metricExporter(metrics)
            .build();
    }

    private static TelemetryRoute route(String id, SpanExporter exporter) {
        return TelemetryRoute.builder(id)
            .addDestination(TelemetryDestination.builder(id + "-backend")
                .spanExporter(exporter)
                .spanProcessingMode(SpanProcessingMode.SIMPLE)
                .build())
            .build();
    }

    private static AgentsFlexHttpClient observedClient() {
        Interceptor interceptor = chain -> new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create("ok", MediaType.parse("text/plain")))
            .build();
        return new AgentsFlexHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
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
        private static final AggregationTemporalitySelector TEMPORALITY =
            AggregationTemporalitySelector.alwaysCumulative();
        private static final DefaultAggregationSelector AGGREGATION = DefaultAggregationSelector.getDefault();
        private final List<MetricData> metrics = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            this.metrics.addAll(metrics);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return TEMPORALITY.getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return AGGREGATION.getDefaultAggregation(instrumentType);
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
