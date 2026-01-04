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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry 可观测性统一入口。
 * 支持 Tracing（链路追踪）和 Metrics（指标）。
 */
public final class Observability {
    private static final Logger logger = LoggerFactory.getLogger(Observability.class);


    // === Tracing 相关 ===
    private static volatile Tracer globalTracer;
    private static volatile SdkTracerProvider tracerProvider;

    // === Metrics 相关 ===
    private static volatile Meter globalMeter;
    private static volatile SdkMeterProvider meterProvider;

    // === 共享状态 ===
    private static volatile boolean initialized = false;
    private static volatile boolean shutdownHookRegistered = false;
    private static volatile Throwable initError = null;

    private static volatile Boolean observabilityEnabled;
    private static volatile java.util.Set<String> excludedTools;


    // === 自定义 Exporter 支持 ===
    private static volatile SpanExporter customSpanExporter;
    private static volatile MetricExporter customMetricExporter;


    private Observability() {
    }

    /**
     * 注入自定义 Exporter
     */
    public static void setCustomExporters(SpanExporter spanExporter, MetricExporter metricExporter) {
        customSpanExporter = spanExporter;
        customMetricExporter = metricExporter;
    }

    private static void init() {
        if (initialized) return;
        synchronized (Observability.class) {
            if (initialized) return;
            if (initError != null) {
                throw new IllegalStateException("OpenTelemetry already failed to initialize", initError);
            }

            try {

                // 检查是否已经被其他组件（如 SpringBoot）注册了 OpenTelemetry SDK
                if (GlobalOpenTelemetry.get() instanceof OpenTelemetrySdk) {
                    logger.info("OpenTelemetry SDK already registered globally. Reusing existing instance.");
                    globalTracer = GlobalOpenTelemetry.getTracer("agents-flex");
                    globalMeter = GlobalOpenTelemetry.getMeter("agents-flex");
                    initialized = true;
                    // 注意：此处不需要注册 shutdown hook，由 GlobalOpenTelemetry 初始化的组件去关闭，比如 Spring 会管理生命周期
                    return;
                }


                Resource resource = Resource.getDefault()
                    .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "agents-flex",
                        AttributeKey.stringKey("service.version"), Consts.VERSION
                    )));


                // 1. 创建 Span 相关组件
                SpanExporter spanExporter = customSpanExporter != null ? customSpanExporter : createSpanExporter();
                SpanProcessor spanProcessor = createSpanProcessor(spanExporter);
                tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(spanProcessor)
                    .setResource(resource)
                    .build();

                // 2. 创建 Metric 相关组件
                MetricExporter metricExporter = customMetricExporter != null ? customMetricExporter : createMetricExporter();
                meterProvider = SdkMeterProvider.builder()
                    .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                        .setInterval(Duration.ofSeconds(getMetricExportIntervalSeconds())) // 默认每60秒导出一次
                        .build())
                    .setResource(resource)
                    .build();

                // 3. 构建并注册全局 OpenTelemetry 实例（同时包含 Trace 和 Metrics）
                OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setMeterProvider(meterProvider)
                    .buildAndRegisterGlobal();

                // 4. 获取全局 Tracer 和 Meter
                globalTracer = GlobalOpenTelemetry.getTracer("agents-flex");
                globalMeter = GlobalOpenTelemetry.getMeter("agents-flex");

                initialized = true;

                // 5. 注册 Shutdown Hook（统一关闭）
                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(new Thread(Observability::shutdown));
                    shutdownHookRegistered = true;
                }
            } catch (Throwable e) {
                initError = e;
                throw new IllegalStateException("Failed to initialize OpenTelemetry", e);
            }
        }
    }


    /**
     * 全局可观测性开关。默认开启。
     * 可通过系统属性 {@code agentsflex.otel.enabled} 控制（true/false）。
     */
    public static boolean isEnabled() {
        if (observabilityEnabled != null) {
            return observabilityEnabled;
        }
        synchronized (Observability.class) {
            if (observabilityEnabled != null) {
                return observabilityEnabled;
            }
            // 默认 true，与现有行为一致
            String prop = System.getProperty("agentsflex.otel.enabled", "true");
            observabilityEnabled = Boolean.parseBoolean(prop);
            return observabilityEnabled;
        }
    }

    /**
     * 判断指定工具是否被排除在可观测性之外。
     * 可通过系统属性 {@code agentsflex.otel.tool.excluded} 配置（逗号分隔，如 "heartbeat,debug"）。
     */
    public static boolean isToolExcluded(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }

        java.util.Set<String> excluded = excludedTools;
        if (excluded != null) {
            return excluded.contains(toolName);
        }

        synchronized (Observability.class) {
            if (excludedTools != null) {
                return excludedTools.contains(toolName);
            }

            String prop = System.getProperty("agentsflex.otel.tool.excluded", "");
            java.util.Set<String> set = new java.util.HashSet<>();
            if (!prop.trim().isEmpty()) {
                for (String name : prop.split(",")) {
                    name = name.trim();
                    if (!name.isEmpty()) {
                        set.add(name);
                    }
                }
            }
            excludedTools = java.util.Collections.unmodifiableSet(set);
            return excludedTools.contains(toolName);
        }
    }


    private static long getMetricExportIntervalSeconds() {
        String prop = System.getProperty("agentsflex.otel.metric.export.interval", "60");
        try {
            long interval = Long.parseLong(prop);
            if (interval > 0) {
                return interval;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 60; // 默认 60 秒
    }

    private static String getExporterType() {
        return System.getProperty("agentsflex.otel.exporter.type", "logging").toLowerCase();
    }

    private static SpanExporter createSpanExporter() {
        String exporterType = getExporterType();
        switch (exporterType) {
            case "otlp":
                return OtlpGrpcSpanExporter.getDefault();
            case "logging":
                return LoggingSpanExporter.create();
            default:
                return createSpanExporterByClassName(exporterType);
        }
    }

    private static SpanExporter createSpanExporterByClassName(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (SpanExporter) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            logger.warn("Failed to create MetricExporter by className: " + className + ", use LoggingSpanExporter to replaced", e);
            return LoggingSpanExporter.create();
        }
    }

    private static SpanProcessor createSpanProcessor(SpanExporter exporter) {
        String exporterType = getExporterType();
        if ("otlp".equals(exporterType)) {
            return BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofSeconds(2))
                .setMaxQueueSize(4096)
                .setMaxExportBatchSize(512)
                .setExporterTimeout(Duration.ofSeconds(10))
                .build();
        } else {
            return SimpleSpanProcessor.create(exporter);
        }
    }

    private static MetricExporter createMetricExporter() {
        String exporterType = getExporterType();
        switch (exporterType) {
            case "otlp":
                return OtlpGrpcMetricExporter.getDefault();
            case "logging":
                return LoggingMetricExporter.create();
            default:
                return createMetricExporterByClassName(exporterType);
        }
    }

    public static MetricExporter createMetricExporterByClassName(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (MetricExporter) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            logger.warn("Failed to create MetricExporter by className: " + className + ", use LoggingMetricExporter to replaced", e);
            return LoggingMetricExporter.create();
        }
    }


    private static void shutdown() {
        // 先关闭 TracerProvider
        if (tracerProvider != null) {
            tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
        // 再关闭 MeterProvider
        if (meterProvider != null) {
            meterProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }


    /**
     * 获取全局 Tracer 实例（用于链路追踪）。
     * 首次调用时自动初始化 OpenTelemetry。
     */
    public static Tracer getTracer() {
        Tracer tracer = globalTracer;
        if (tracer != null) {
            return tracer;
        }
        synchronized (Observability.class) {
            if (globalTracer != null) {
                return globalTracer;
            }
            if (initError != null) {
                throw new IllegalStateException("OpenTelemetry initialization failed", initError);
            }
            init();
            return globalTracer;
        }
    }

    /**
     * 获取全局 Meter 实例（用于指标收集）。
     * 首次调用时自动初始化 OpenTelemetry。
     */
    public static Meter getMeter() {
        Meter meter = globalMeter;
        if (meter != null) {
            return meter;
        }
        synchronized (Observability.class) {
            if (globalMeter != null) {
                return globalMeter;
            }
            if (initError != null) {
                throw new IllegalStateException("OpenTelemetry initialization failed", initError);
            }
            init();
            return globalMeter;
        }
    }
}
