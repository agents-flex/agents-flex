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

import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.Duration;

/**
 * {@link TelemetryRoute} 中的一个遥测目的地，例如某个 APM、OTel Collector 或 JDBC 数据库。
 *
 * <p>每个目的地独立配置 Span Processor 和 Metric Reader。这样同一路由向多个后端扇出时，各后端拥有
 * 自己的队列、批大小、超时和导出周期；一个后端变慢或失败不会占用另一个后端的批处理队列。</p>
 *
 * <p>该对象只保存配置，真正的 Processor 和 Reader 由 {@link TelemetryRoute} 构造并管理。Exporter 的
 * 生命周期也随 Route 结束，因此同一个 Exporter 实例不应重复交给多个目的地或多个 Route。</p>
 */
public final class TelemetryDestination {
    /** 目的地在所属 Route 内的唯一标识，仅用于配置管理，不会上报。 */
    private final String id;

    /** Span 导出器；为 null 表示该目的地不接收 Trace 数据。 */
    private final SpanExporter spanExporter;

    /** Metric 导出器；为 null 表示该目的地不接收指标数据。 */
    private final MetricExporter metricExporter;

    /** Span 的处理方式，决定使用 BatchSpanProcessor 还是 SimpleSpanProcessor。 */
    private final SpanProcessingMode spanProcessingMode;

    /** 批处理器的调度间隔，仅在 BATCH 模式使用。 */
    private final Duration spanScheduleDelay;

    /** 单次 Span Exporter 调用允许等待的最长时间，仅在 BATCH 模式使用。 */
    private final Duration spanExportTimeout;

    /** Span 内存队列容量，仅在 BATCH 模式使用。 */
    private final int spanMaxQueueSize;

    /** 单次从队列提交给 Span Exporter 的最大 Span 数，仅在 BATCH 模式使用。 */
    private final int spanMaxExportBatchSize;

    /** PeriodicMetricReader 触发 Metric 导出的时间间隔。 */
    private final Duration metricExportInterval;

    private TelemetryDestination(Builder builder) {
        this.id = builder.id;
        this.spanExporter = builder.spanExporter;
        this.metricExporter = builder.metricExporter;
        this.spanProcessingMode = builder.spanProcessingMode;
        this.spanScheduleDelay = builder.spanScheduleDelay;
        this.spanExportTimeout = builder.spanExportTimeout;
        this.spanMaxQueueSize = builder.spanMaxQueueSize;
        this.spanMaxExportBatchSize = builder.spanMaxExportBatchSize;
        this.metricExportInterval = builder.metricExportInterval;
    }

    /**
     * 创建目的地配置。ID 只用于配置识别和重复校验，不会作为 Span 或 Metric 属性上报。
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() {
        return id;
    }

    SpanExporter getSpanExporter() {
        return spanExporter;
    }

    MetricExporter getMetricExporter() {
        return metricExporter;
    }

    SpanProcessingMode getSpanProcessingMode() {
        return spanProcessingMode;
    }

    Duration getSpanScheduleDelay() {
        return spanScheduleDelay;
    }

    Duration getSpanExportTimeout() {
        return spanExportTimeout;
    }

    int getSpanMaxQueueSize() {
        return spanMaxQueueSize;
    }

    int getSpanMaxExportBatchSize() {
        return spanMaxExportBatchSize;
    }

    Duration getMetricExportInterval() {
        return metricExportInterval;
    }

    /**
     * 目的地构建器。Span 与 Metric Exporter 可以只配置其中一个，但不能同时为空。
     */
    public static final class Builder {
        /** 待构建目的地的唯一标识。 */
        private final String id;

        /** 可选 Span Exporter；与 metricExporter 至少配置一个。 */
        private SpanExporter spanExporter;

        /** 可选 Metric Exporter；与 spanExporter 至少配置一个。 */
        private MetricExporter metricExporter;

        /** Span 处理模式，默认异步批处理。 */
        private SpanProcessingMode spanProcessingMode = SpanProcessingMode.BATCH;

        /** 批处理调度间隔，默认 2 秒。 */
        private Duration spanScheduleDelay = Duration.ofSeconds(2);

        /** Span 导出超时，默认 10 秒。 */
        private Duration spanExportTimeout = Duration.ofSeconds(10);

        /** Span 最大排队数量，默认 4096。 */
        private int spanMaxQueueSize = 4096;

        /** Span 单批最大导出数量，默认 512。 */
        private int spanMaxExportBatchSize = 512;

        /** Metric 周期导出间隔，默认 60 秒。 */
        private Duration metricExportInterval = Duration.ofSeconds(60);

        private Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("destination id must not be blank");
            }
            this.id = id;
        }

        /** 设置该后端接收 Span 的 Exporter。 */
        public Builder spanExporter(SpanExporter spanExporter) {
            this.spanExporter = spanExporter;
            return this;
        }

        /** 设置该后端接收 Metrics 的 Exporter。 */
        public Builder metricExporter(MetricExporter metricExporter) {
            this.metricExporter = metricExporter;
            return this;
        }

        /** 设置 Span 使用异步批处理还是同步简单处理，默认使用 {@link SpanProcessingMode#BATCH}。 */
        public Builder spanProcessingMode(SpanProcessingMode spanProcessingMode) {
            if (spanProcessingMode == null) {
                throw new IllegalArgumentException("spanProcessingMode must not be null");
            }
            this.spanProcessingMode = spanProcessingMode;
            return this;
        }

        /** 设置批处理器检查并导出队列的调度间隔。仅对 BATCH 模式生效。 */
        public Builder spanScheduleDelay(Duration spanScheduleDelay) {
            this.spanScheduleDelay = requirePositive(spanScheduleDelay, "spanScheduleDelay");
            return this;
        }

        /** 设置单次 Span 导出的最长等待时间。仅对 BATCH 模式生效。 */
        public Builder spanExportTimeout(Duration spanExportTimeout) {
            this.spanExportTimeout = requirePositive(spanExportTimeout, "spanExportTimeout");
            return this;
        }

        /** 设置 Span 内存队列最大容量。队列满时 OTel SDK 可能丢弃新 Span。 */
        public Builder spanMaxQueueSize(int spanMaxQueueSize) {
            this.spanMaxQueueSize = requirePositive(spanMaxQueueSize, "spanMaxQueueSize");
            return this;
        }

        /** 设置单次提交给 Span Exporter 的最大 Span 数量，不能大于队列容量。 */
        public Builder spanMaxExportBatchSize(int spanMaxExportBatchSize) {
            this.spanMaxExportBatchSize = requirePositive(spanMaxExportBatchSize, "spanMaxExportBatchSize");
            return this;
        }

        /** 设置 Metric Reader 的周期导出间隔。 */
        public Builder metricExportInterval(Duration metricExportInterval) {
            this.metricExportInterval = requirePositive(metricExportInterval, "metricExportInterval");
            return this;
        }

        /**
         * 校验配置并生成不可变目的地。
         */
        public TelemetryDestination build() {
            if (spanExporter == null && metricExporter == null) {
                throw new IllegalStateException("At least one exporter is required for destination " + id);
            }
            if (spanMaxExportBatchSize > spanMaxQueueSize) {
                throw new IllegalStateException("spanMaxExportBatchSize must not exceed spanMaxQueueSize");
            }
            return new TelemetryDestination(this);
        }

        private static Duration requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
