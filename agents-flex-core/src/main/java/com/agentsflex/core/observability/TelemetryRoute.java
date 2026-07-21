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
 * 一条可在执行期间选择的遥测路由。
 *
 * <p>一个 Route 内部只有一个 {@link SdkTracerProvider} 和一个 {@link SdkMeterProvider}，所以采样决策、
 * Resource、Trace ID 以及 Span ID 在所有目的地之间保持一致。每个目的地则注册独立的 Span Processor 和
 * Metric Reader，以实现相互隔离的多后端扇出。</p>
 *
 * <p>Route 是长生命周期对象，通常在应用启动时创建并注册，在应用停止时关闭。不要为每次模型调用临时
 * 创建 Route，否则会反复创建调度线程和内存队列。</p>
 */
public final class TelemetryRoute implements ObservabilityRuntime, AutoCloseable {
    /** 该 Route 创建 Tracer/Meter 时使用的 instrumentation scope 名称。 */
    private static final String INSTRUMENTATION_SCOPE = "agents-flex";

    /** Route 的稳定唯一标识，通常由宿主业务对象以 telemetryRouteId 的形式引用。 */
    private final String id;

    /** Route 包含的不可变目的地列表；每个目的地拥有独立 Processor/Reader。 */
    private final List<TelemetryDestination> destinations;

    /** Route 私有的 Trace Provider，统一完成采样并承载所有目的地的 Span Processor。 */
    private final SdkTracerProvider tracerProvider;

    /** Route 私有的 Metric Provider，承载各目的地独立的 PeriodicMetricReader。 */
    private final SdkMeterProvider meterProvider;

    /** 由本 Route 的 Trace/Metric Provider 和 Propagator 组合出的完整 OTel SDK。 */
    private final OpenTelemetrySdk openTelemetry;

    /** 从本 Route SDK 创建并缓存的 Agents-Flex Tracer。 */
    private final Tracer tracer;

    /** 从本 Route SDK 创建并缓存的 Agents-Flex Meter。 */
    private final Meter meter;

    /** Route 是否已经关闭；使用 CAS 使并发或重复 close 保持幂等。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TelemetryRoute(Builder builder) {
        this.id = builder.id;
        this.destinations = Collections.unmodifiableList(new ArrayList<>(builder.destinations));

        // Resource 描述产生遥测数据的服务。先合并宿主环境信息，再由显式 service 配置覆盖同名属性。
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

        // 每个 destination 单独注册 Processor/Reader，不能用一个串行 CompositeExporter 共享同一队列，
        // 否则慢后端会阻塞整条扇出链路，也无法为不同后端设置独立的容量和超时。
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

    /**
     * 创建路由构建器。ID 应在应用内稳定且唯一，通常与业务对象保存的 telemetryRouteId 对应。
     */
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

    /**
     * 主动要求所有 Span Processor 和 Metric Reader 刷新数据。
     *
     * <p>返回值同时聚合 Trace 与 Metric 两部分结果。该方法适合测试、受控停机或必须尽快导出的管理操作；
     * 普通业务请求不应同步等待 flush。</p>
     */
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofAll(java.util.Arrays.asList(
            tracerProvider.forceFlush(), meterProvider.forceFlush()));
    }

    /**
     * 关闭 Route 拥有的 Provider，并间接关闭其 Processor、Reader 和 Exporter。
     *
     * <p>关闭操作幂等。外部资源（例如 JDBC {@code DataSource}）仍由创建它的应用负责关闭。</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tracerProvider.shutdown().join(10, TimeUnit.SECONDS);
        meterProvider.shutdown().join(10, TimeUnit.SECONDS);
    }

    /**
     * Route 构建器。所有 destination 共享这里配置的 Resource、Sampler 和 Context Propagator。
     */
    public static final class Builder {
        /** 待构建 Route 的稳定标识。 */
        private final String id;

        /** 按注册顺序保存的目的地配置。 */
        private final List<TelemetryDestination> destinations = new ArrayList<>();

        /** 写入 Resource 的 service.name，默认 agents-flex。 */
        private String serviceName = "agents-flex";

        /** 写入 Resource 的 service.version，默认使用当前 Agents-Flex 版本。 */
        private String serviceVersion = Consts.VERSION;

        /** 除 service name/version 外的 Resource 属性，默认空集合。 */
        private Attributes resourceAttributes = Attributes.empty();

        /** Route 共享采样器，默认尊重父 Span；无父 Span 时全部采样。 */
        private Sampler sampler = Sampler.parentBased(Sampler.alwaysOn());

        /** 跨进程 Trace Context 传播器，默认使用 W3C trace-context。 */
        private ContextPropagators propagators =
            ContextPropagators.create(W3CTraceContextPropagator.getInstance());

        private Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("route id must not be blank");
            }
            this.id = id;
        }

        /** 设置 Resource 中的 {@code service.name}。 */
        public Builder serviceName(String serviceName) {
            this.serviceName = requireText(serviceName, "serviceName");
            return this;
        }

        /** 设置 Resource 中的 {@code service.version}。 */
        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = requireText(serviceVersion, "serviceVersion");
            return this;
        }

        /**
         * 设置额外 Resource 属性，例如部署环境、服务实例或集群信息。
         * 不要把每次请求变化的业务 ID 放入 Resource，应通过 Observability.useRuntime 的执行属性传入。
         */
        public Builder resourceAttributes(Attributes resourceAttributes) {
            if (resourceAttributes == null) {
                throw new IllegalArgumentException("resourceAttributes must not be null");
            }
            this.resourceAttributes = resourceAttributes;
            return this;
        }

        /**
         * 设置整条 Route 共享的采样器。一次 Span 只做一次采样决定，所有后端得到相同的采样结果。
         */
        public Builder sampler(Sampler sampler) {
            if (sampler == null) {
                throw new IllegalArgumentException("sampler must not be null");
            }
            this.sampler = sampler;
            return this;
        }

        /** 设置 HTTP 等跨进程调用注入 Trace Context 时使用的传播器。 */
        public Builder propagators(ContextPropagators propagators) {
            if (propagators == null) {
                throw new IllegalArgumentException("propagators must not be null");
            }
            this.propagators = propagators;
            return this;
        }

        /** 添加一个独立后端。同一 Route 内 destination ID 不允许重复。 */
        public Builder addDestination(TelemetryDestination destination) {
            if (destination == null) {
                throw new IllegalArgumentException("destination must not be null");
            }
            this.destinations.add(destination);
            return this;
        }

        /** 校验至少存在一个目的地且 ID 唯一，然后创建并启动 Route 所需的 SDK 组件。 */
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
