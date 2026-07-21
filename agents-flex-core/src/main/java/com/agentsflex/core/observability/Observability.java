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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
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
 * Agents-Flex 所有自动埋点访问 OpenTelemetry 的统一入口。
 *
 * <p>该类同时支持两层配置：</p>
 * <ul>
 *     <li>全局兜底：默认复用宿主应用的 {@link GlobalOpenTelemetry}；显式配置 Exporter 时，才创建由
 *     Agents-Flex 管理的私有 SDK。</li>
 *     <li>执行级路由：通过 {@link #useRuntime(ObservabilityRuntime, Attributes)} 把某个 runtime 放入
 *     当前 OTel {@link Context}，使本次执行可以选择不同后端或同时发送到多个后端。</li>
 * </ul>
 *
 * <p>私有 SDK 不会注册为全局实例，避免作为库的 Agents-Flex 覆盖宿主应用已经装配的遥测系统。任何初始化
 * 或导出异常也必须与模型、工具等业务调用隔离。</p>
 */
public final class Observability {
    /** 当前类的日志记录器，主要记录初始化和关闭阶段的可观测异常。 */
    private static final Logger logger = LoggerFactory.getLogger(Observability.class);

    /** Agents-Flex 创建 Tracer/Meter 时使用的 instrumentation scope 名称。 */
    private static final String INSTRUMENTATION_SCOPE = "agents-flex";

    /** 初始化失败或全局开关关闭时使用的无操作实现，保证可观测逻辑不会影响业务。 */
    private static final OpenTelemetry NOOP = OpenTelemetry.noop();

    /**
     * 当前执行选择的 runtime 在 OTel Context 中使用的键。
     * OTel Context 能随 Span、线程包装和流式回调一起传播，比 ThreadLocal 更适合异步调用链。
     */
    private static final ContextKey<ObservabilityRuntime> RUNTIME_KEY =
        ContextKey.named("agents-flex-observability-runtime");

    /**
     * 当前执行固定 Span 属性在 OTel Context 中使用的键。
     * 这些属性不进入内置 Metrics，避免业务 ID 形成高基数时间序列。
     */
    private static final ContextKey<Attributes> EXECUTION_ATTRIBUTES_KEY =
        ContextKey.named("agents-flex-observability-attributes");

    /**
     * 没有显式调用 useRuntime 时使用的全局兜底适配器。
     * 其 getter 采用延迟初始化，避免类加载阶段提前锁定宿主应用的 OpenTelemetry 配置。
     */
    private static final ObservabilityRuntime DEFAULT_RUNTIME = new ObservabilityRuntime() {
        @Override
        public String getId() {
            return "agents-flex-global";
        }

        @Override
        public OpenTelemetry getOpenTelemetry() {
            return getDefaultOpenTelemetry();
        }

        @Override
        public Tracer getTracer() {
            return getDefaultTracer();
        }

        @Override
        public Meter getMeter() {
            return getDefaultMeter();
        }
    };

    /** 由宿主通过 setOpenTelemetry 显式注入的实例；所有权仍属于宿主，Agents-Flex 不负责关闭。 */
    private static volatile OpenTelemetry configuredOpenTelemetry;

    /** 初始化选择完成后实际生效的全局兜底实例，可能来自宿主、全局注册表或框架私有 SDK。 */
    private static volatile OpenTelemetry activeOpenTelemetry;

    /** 从 activeOpenTelemetry 获取并缓存的全局兜底 Tracer。 */
    private static volatile Tracer globalTracer;

    /** 从 activeOpenTelemetry 获取并缓存的全局兜底 Meter。 */
    private static volatile Meter globalMeter;

    /** Agents-Flex 创建并拥有的 Trace Provider；仅私有 SDK 模式下非空。 */
    private static volatile SdkTracerProvider ownedTracerProvider;

    /** Agents-Flex 创建并拥有的 Metric Provider；仅私有 SDK 模式下非空。 */
    private static volatile SdkMeterProvider ownedMeterProvider;

    /** 是否已经完成首次初始化尝试；成功和降级为 NOOP 都会置为 true。 */
    private static volatile boolean initialized;

    /** 是否已注册 JVM shutdown hook，防止重复添加关闭线程。 */
    private static volatile boolean shutdownHookRegistered;

    /** 通过 setCustomExporters 配置的全局 Span Exporter，由框架私有 Provider 管理生命周期。 */
    private static volatile SpanExporter customSpanExporter;

    /** 通过 setCustomExporters 配置的全局 Metric Exporter，由框架私有 Provider 管理生命周期。 */
    private static volatile MetricExporter customMetricExporter;

    private Observability() {
    }

    /**
     * 注入由宿主应用管理的 OpenTelemetry 实例。
     *
     * <p>必须在第一次使用可观测能力之前调用。Agents-Flex 不会关闭该实例及其 Provider、Processor、
     * Reader 或 Exporter。</p>
     */
    public static synchronized void setOpenTelemetry(OpenTelemetry openTelemetry) {
        ensureNotInitialized();
        configuredOpenTelemetry = requireNonNull(openTelemetry, "openTelemetry");
    }

    /**
     * 为 Agents-Flex 管理的全局兜底 SDK 设置自定义 Span 和 Metric Exporter。
     *
     * <p>必须在第一次使用之前调用。该方式适合只有一组全局后端的应用；需要按业务执行选择后端或向多个
     * 后端扇出时，应使用 {@link TelemetryRoute}。</p>
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
                // 优先使用宿主显式注入的实例；其次按配置创建私有 SDK；最后复用全局实例。
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
                // 可观测属于旁路能力：初始化失败必须降级为 no-op，不能阻止模型、工具或 HTTP 请求执行。
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
        // Resource 在 SDK 级别共享，用来描述产生数据的服务，而不是描述某一次业务请求。
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("service.name"), getServiceName(),
            AttributeKey.stringKey("service.version"), Consts.VERSION
        )));

        SpanExporter spanExporter = customSpanExporter != null
            ? customSpanExporter : createSpanExporter(exporterType);
        MetricExporter metricExporter = customMetricExporter != null
            ? customMetricExporter : createMetricExporter(exporterType);

        // Span 与 Metric 分别由 Provider 管理；Metric Reader 自带周期调度线程。
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

    /**
     * 返回全局可观测开关。每次调用都会读取系统属性，因此运行期间修改属性可以立即生效。
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("agentsflex.otel.enabled", "true"));
    }

    /**
     * 是否采集模型响应、工具参数和结果等内容。
     *
     * <p>内容可能包含密钥、个人信息或业务数据，因此默认关闭；即使开启，调用方仍需承担数据授权、访问控制
     * 和保留周期管理责任。</p>
     */
    public static boolean isContentCaptureEnabled() {
        return Boolean.parseBoolean(System.getProperty("agentsflex.otel.capture.content", "false"));
    }

    /** 判断指定工具是否被全局排除在可观测采集之外。 */
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
        // 日志导出用于本地观察，立即输出更直观；远程和自定义后端默认使用异步批处理，避免阻塞业务线程。
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

    /**
     * 只关闭 Agents-Flex 自己创建的全局兜底 Provider，不会关闭宿主注入或 GlobalOpenTelemetry 的组件。
     */
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

    /**
     * 返回当前执行上下文选择的 runtime；未选择时返回全局兜底 runtime。
     */
    public static ObservabilityRuntime currentRuntime() {
        ObservabilityRuntime runtime = Context.current().get(RUNTIME_KEY);
        return runtime == null ? DEFAULT_RUNTIME : runtime;
    }

    /**
     * 在当前同步作用域选择一个可观测 runtime。
     *
     * <p>返回的 {@link Scope} 必须在创建它的同一线程关闭。任务跨线程执行时，应捕获
     * {@code Context.current()} 并使用 {@code Context.wrap(...)} 或在目标线程调用 {@code makeCurrent()}，
     * 不能直接把 Scope 传到另一个线程。</p>
     */
    public static Scope useRuntime(ObservabilityRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        return Context.current().with(RUNTIME_KEY, runtime).makeCurrent();
    }

    /**
     * 在当前作用域选择 runtime，并为作用域内由 Agents-Flex 创建的 Span 添加固定执行属性。
     *
     * <p>嵌套作用域会继承外层属性，同名属性由内层值覆盖。属性不会自动加入 Metrics，避免智能体 ID、租户
     * ID 等高基数值扩大指标时间序列数量。</p>
     */
    public static Scope useRuntime(ObservabilityRuntime runtime, Attributes attributes) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("attributes must not be null");
        }
        Attributes inherited = Context.current().get(EXECUTION_ATTRIBUTES_KEY);
        Attributes merged = Attributes.builder()
            .putAll(inherited == null ? Attributes.empty() : inherited)
            .putAll(attributes)
            .build();
        return Context.current()
            .with(RUNTIME_KEY, runtime)
            .with(EXECUTION_ATTRIBUTES_KEY, merged)
            .makeCurrent();
    }

    /**
     * 把 {@link #useRuntime(ObservabilityRuntime, Attributes)} 绑定的执行属性追加到指定 Span。
     * 埋点应在 Span 创建后立即调用，以确保异步回调发生前属性已经固化到 Span 中。
     */
    public static void enrichSpan(Span span) {
        Attributes attributes = Context.current().get(EXECUTION_ATTRIBUTES_KEY);
        if (span == null || attributes == null || attributes.isEmpty()) {
            return;
        }
        span.setAllAttributes(attributes);
    }

    /** 返回当前 runtime 的 OpenTelemetry 门面，用于传播器等完整 SDK 能力。 */
    public static OpenTelemetry getOpenTelemetry() {
        return currentRuntime().getOpenTelemetry();
    }

    /** 返回当前 runtime 的 Tracer。 */
    public static Tracer getTracer() {
        return currentRuntime().getTracer();
    }

    /** 返回当前 runtime 的 Meter。 */
    public static Meter getMeter() {
        return currentRuntime().getMeter();
    }

    private static OpenTelemetry getDefaultOpenTelemetry() {
        if (!isEnabled()) {
            return NOOP;
        }
        if (!initialized) {
            init();
        }
        return activeOpenTelemetry;
    }

    private static Tracer getDefaultTracer() {
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

    private static Meter getDefaultMeter() {
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
