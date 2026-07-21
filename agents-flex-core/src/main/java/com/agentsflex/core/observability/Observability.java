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
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry access point used by Agents-Flex instrumentation.
 *
 * <p>By default this class reuses the application's global OpenTelemetry instance. It only
 * creates an SDK when an exporter is explicitly configured. A privately created SDK is never
 * registered globally, so a library cannot replace telemetry owned by the host application.</p>
 */
public final class Observability {
    private static final Logger logger = LoggerFactory.getLogger(Observability.class);
    private static final String INSTRUMENTATION_SCOPE = "agents-flex";
    private static final OpenTelemetry NOOP = OpenTelemetry.noop();

    private static volatile OpenTelemetry configuredOpenTelemetry;
    private static volatile OpenTelemetry activeOpenTelemetry;
    private static volatile Tracer globalTracer;
    private static volatile Meter globalMeter;
    private static volatile SdkTracerProvider ownedTracerProvider;
    private static volatile SdkMeterProvider ownedMeterProvider;
    private static volatile boolean initialized;
    private static volatile boolean shutdownHookRegistered;

    private static volatile SpanExporter customSpanExporter;
    private static volatile MetricExporter customMetricExporter;

    private Observability() {
    }

    /**
     * Uses an application-managed OpenTelemetry instance. Must be called before first use.
     */
    public static synchronized void setOpenTelemetry(OpenTelemetry openTelemetry) {
        ensureNotInitialized();
        configuredOpenTelemetry = requireNonNull(openTelemetry, "openTelemetry");
    }

    /**
     * Configures exporters for an Agents-Flex-owned SDK. Must be called before first use.
     */
    public static synchronized void setCustomExporters(SpanExporter spanExporter, MetricExporter metricExporter) {
        ensureNotInitialized();
        customSpanExporter = requireNonNull(spanExporter, "spanExporter");
        customMetricExporter = requireNonNull(metricExporter, "metricExporter");
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static void ensureNotInitialized() {
        if (initialized) {
            throw new IllegalStateException("OpenTelemetry has already been initialized");
        }
    }

    private static void init() {
        if (initialized) {
            return;
        }
        synchronized (Observability.class) {
            if (initialized) {
                return;
            }

            try {
                OpenTelemetry selected = configuredOpenTelemetry;
                if (selected == null) {
                    String exporterType = getExporterType();
                    if (customSpanExporter != null || !"none".equals(exporterType)) {
                        selected = createOwnedSdk(exporterType);
                        registerShutdownHook();
                    } else {
                        selected = GlobalOpenTelemetry.get();
                    }
                }

                activeOpenTelemetry = selected;
                globalTracer = selected.getTracer(INSTRUMENTATION_SCOPE, Consts.VERSION);
                globalMeter = selected.getMeter(INSTRUMENTATION_SCOPE);
            } catch (Throwable error) {
                // Observability must never prevent a model or tool call from running.
                logger.warn("Failed to initialize OpenTelemetry; falling back to no-op telemetry", error);
                closeOwnedProviders();
                activeOpenTelemetry = NOOP;
                globalTracer = NOOP.getTracer(INSTRUMENTATION_SCOPE);
                globalMeter = NOOP.getMeter(INSTRUMENTATION_SCOPE);
            } finally {
                initialized = true;
            }
        }
    }

    private static OpenTelemetrySdk createOwnedSdk(String exporterType) {
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"), getServiceName(),
            AttributeKey.stringKey("service.version"), Consts.VERSION
        )));

        SpanExporter spanExporter = customSpanExporter != null
            ? customSpanExporter : createSpanExporter(exporterType);
        MetricExporter metricExporter = customMetricExporter != null
            ? customMetricExporter : createMetricExporter(exporterType);

        ownedTracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(createSpanProcessor(spanExporter, exporterType))
            .setResource(resource)
            .build();
        ownedMeterProvider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofSeconds(getMetricExportIntervalSeconds()))
                .build())
            .setResource(resource)
            .build();

        return OpenTelemetrySdk.builder()
            .setTracerProvider(ownedTracerProvider)
            .setMeterProvider(ownedMeterProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    }

    /** Global switch. The value is read on every call so runtime property changes take effect. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("agentsflex.otel.enabled", "true"));
    }

    /**
     * Content capture is opt-in because prompts, responses and tool data can contain secrets or PII.
     */
    public static boolean isContentCaptureEnabled() {
        return Boolean.parseBoolean(System.getProperty("agentsflex.otel.capture.content", "false"));
    }

    public static boolean isToolExcluded(String toolName) {
        return toolName != null && !toolName.isEmpty() && getExcludedTools().contains(toolName);
    }

    private static Set<String> getExcludedTools() {
        String property = System.getProperty("agentsflex.otel.tool.excluded", "").trim();
        if (property.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (String name : property.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private static long getMetricExportIntervalSeconds() {
        String value = System.getProperty("agentsflex.otel.metric.export.interval", "60");
        try {
            long interval = Long.parseLong(value);
            return interval > 0 ? interval : 60;
        } catch (NumberFormatException ignored) {
            return 60;
        }
    }

    private static String getServiceName() {
        return System.getProperty("agentsflex.otel.service.name", "agents-flex");
    }

    private static String getExporterType() {
        return System.getProperty("agentsflex.otel.exporter.type", "none")
            .trim().toLowerCase(Locale.ROOT);
    }

    private static SpanExporter createSpanExporter(String exporterType) {
        if ("otlp".equals(exporterType)) {
            return OtlpGrpcSpanExporter.getDefault();
        }
        if ("logging".equals(exporterType)) {
            return LoggingSpanExporter.create();
        }
        throw new IllegalArgumentException("Unsupported OpenTelemetry exporter type: " + exporterType);
    }

    private static MetricExporter createMetricExporter(String exporterType) {
        if ("otlp".equals(exporterType)) {
            return OtlpGrpcMetricExporter.getDefault();
        }
        if ("logging".equals(exporterType)) {
            return LoggingMetricExporter.create();
        }
        throw new IllegalArgumentException("Unsupported OpenTelemetry exporter type: " + exporterType);
    }

    private static SpanProcessor createSpanProcessor(SpanExporter exporter, String exporterType) {
        if ("logging".equals(exporterType) && customSpanExporter == null) {
            return SimpleSpanProcessor.create(exporter);
        }
        return BatchSpanProcessor.builder(exporter)
            .setScheduleDelay(Duration.ofSeconds(2))
            .setMaxQueueSize(4096)
            .setMaxExportBatchSize(512)
            .setExporterTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(Observability::shutdown, "agents-flex-otel-shutdown"));
            shutdownHookRegistered = true;
        }
    }

    /** Closes only providers created and owned by Agents-Flex. */
    public static synchronized void shutdown() {
        closeOwnedProviders();
    }

    private static void closeOwnedProviders() {
        if (ownedTracerProvider != null) {
            ownedTracerProvider.shutdown().join(10, TimeUnit.SECONDS);
            ownedTracerProvider = null;
        }
        if (ownedMeterProvider != null) {
            ownedMeterProvider.shutdown().join(10, TimeUnit.SECONDS);
            ownedMeterProvider = null;
        }
    }

    public static OpenTelemetry getOpenTelemetry() {
        if (!isEnabled()) {
            return NOOP;
        }
        if (!initialized) {
            init();
        }
        return activeOpenTelemetry;
    }

    public static Tracer getTracer() {
        if (!isEnabled()) {
            return NOOP.getTracer(INSTRUMENTATION_SCOPE);
        }
        Tracer tracer = globalTracer;
        if (tracer == null) {
            init();
            tracer = globalTracer;
        }
        return tracer;
    }

    public static Meter getMeter() {
        if (!isEnabled()) {
            return NOOP.getMeter(INSTRUMENTATION_SCOPE);
        }
        Meter meter = globalMeter;
        if (meter == null) {
            init();
            meter = globalMeter;
        }
        return meter;
    }
}
