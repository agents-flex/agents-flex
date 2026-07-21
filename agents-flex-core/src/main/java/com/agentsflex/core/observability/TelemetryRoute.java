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

import com.agentsflex.core.Consts;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One selectable telemetry pipeline. Every destination has an independent span processor and
 * metric reader, so a slow backend does not share an export queue with the other backends.
 */
public final class TelemetryRoute implements ObservabilityRuntime, AutoCloseable {
    private static final String INSTRUMENTATION_SCOPE = "agents-flex";

    private final String id;
    private final List<TelemetryDestination> destinations;
    private final SdkTracerProvider tracerProvider;
    private final SdkMeterProvider meterProvider;
    private final OpenTelemetrySdk openTelemetry;
    private final Tracer tracer;
    private final Meter meter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TelemetryRoute(Builder builder) {
        this.id = builder.id;
        this.destinations = Collections.unmodifiableList(new ArrayList<>(builder.destinations));

        Resource resource = Resource.getDefault()
            .merge(Resource.create(builder.resourceAttributes))
            .merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), builder.serviceName,
                AttributeKey.stringKey("service.version"), builder.serviceVersion
            )));

        SdkTracerProviderBuilder tracerBuilder = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(builder.sampler);
        SdkMeterProviderBuilder meterBuilder = SdkMeterProvider.builder().setResource(resource);

        for (TelemetryDestination destination : destinations) {
            if (destination.getSpanExporter() != null) {
                if (destination.getSpanProcessingMode() == SpanProcessingMode.SIMPLE) {
                    tracerBuilder.addSpanProcessor(SimpleSpanProcessor.create(destination.getSpanExporter()));
                } else {
                    tracerBuilder.addSpanProcessor(BatchSpanProcessor.builder(destination.getSpanExporter())
                        .setScheduleDelay(destination.getSpanScheduleDelay())
                        .setExporterTimeout(destination.getSpanExportTimeout())
                        .setMaxQueueSize(destination.getSpanMaxQueueSize())
                        .setMaxExportBatchSize(destination.getSpanMaxExportBatchSize())
                        .build());
                }
            }
            if (destination.getMetricExporter() != null) {
                meterBuilder.registerMetricReader(PeriodicMetricReader.builder(destination.getMetricExporter())
                    .setInterval(destination.getMetricExportInterval())
                    .build());
            }
        }

        this.tracerProvider = tracerBuilder.build();
        this.meterProvider = meterBuilder.build();
        this.openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setPropagators(builder.propagators)
            .build();
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE, Consts.VERSION);
        this.meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    @Override
    public String getId() {
        return id;
    }

    public List<TelemetryDestination> getDestinations() {
        return destinations;
    }

    @Override
    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    @Override
    public Tracer getTracer() {
        return tracer;
    }

    @Override
    public Meter getMeter() {
        return meter;
    }

    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofAll(java.util.Arrays.asList(
            tracerProvider.forceFlush(), meterProvider.forceFlush()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        meterProvider.shutdown().join(10, TimeUnit.SECONDS);
    }

    public static final class Builder {
        private final String id;
        private final List<TelemetryDestination> destinations = new ArrayList<>();
        private String serviceName = "agents-flex";
        private String serviceVersion = Consts.VERSION;
        private Attributes resourceAttributes = Attributes.empty();
        private Sampler sampler = Sampler.parentBased(Sampler.alwaysOn());
        private ContextPropagators propagators =
            ContextPropagators.create(W3CTraceContextPropagator.getInstance());

        private Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("route id must not be blank");
            }
            this.id = id;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = requireText(serviceName, "serviceName");
            return this;
        }

        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = requireText(serviceVersion, "serviceVersion");
            return this;
        }

        public Builder resourceAttributes(Attributes resourceAttributes) {
            if (resourceAttributes == null) {
                throw new IllegalArgumentException("resourceAttributes must not be null");
            }
            this.resourceAttributes = resourceAttributes;
            return this;
        }

        public Builder sampler(Sampler sampler) {
            if (sampler == null) {
                throw new IllegalArgumentException("sampler must not be null");
            }
            this.sampler = sampler;
            return this;
        }

        public Builder propagators(ContextPropagators propagators) {
            if (propagators == null) {
                throw new IllegalArgumentException("propagators must not be null");
            }
            this.propagators = propagators;
            return this;
        }

        public Builder addDestination(TelemetryDestination destination) {
            if (destination == null) {
                throw new IllegalArgumentException("destination must not be null");
            }
            this.destinations.add(destination);
            return this;
        }

        public TelemetryRoute build() {
            if (destinations.isEmpty()) {
                throw new IllegalStateException("At least one telemetry destination is required");
            }
            Set<String> ids = new HashSet<>();
            for (TelemetryDestination destination : destinations) {
                if (!ids.add(destination.getId())) {
                    throw new IllegalStateException("Duplicate telemetry destination: " + destination.getId());
                }
            }
            return new TelemetryRoute(this);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
