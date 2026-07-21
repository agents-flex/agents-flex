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

/** One independently buffered destination within a {@link TelemetryRoute}. */
public final class TelemetryDestination {
    private final String id;
    private final SpanExporter spanExporter;
    private final MetricExporter metricExporter;
    private final SpanProcessingMode spanProcessingMode;
    private final Duration spanScheduleDelay;
    private final Duration spanExportTimeout;
    private final int spanMaxQueueSize;
    private final int spanMaxExportBatchSize;
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

    public static final class Builder {
        private final String id;
        private SpanExporter spanExporter;
        private MetricExporter metricExporter;
        private SpanProcessingMode spanProcessingMode = SpanProcessingMode.BATCH;
        private Duration spanScheduleDelay = Duration.ofSeconds(2);
        private Duration spanExportTimeout = Duration.ofSeconds(10);
        private int spanMaxQueueSize = 4096;
        private int spanMaxExportBatchSize = 512;
        private Duration metricExportInterval = Duration.ofSeconds(60);

        private Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("destination id must not be blank");
            }
            this.id = id;
        }

        public Builder spanExporter(SpanExporter spanExporter) {
            this.spanExporter = spanExporter;
            return this;
        }

        public Builder metricExporter(MetricExporter metricExporter) {
            this.metricExporter = metricExporter;
            return this;
        }

        public Builder spanProcessingMode(SpanProcessingMode spanProcessingMode) {
            if (spanProcessingMode == null) {
                throw new IllegalArgumentException("spanProcessingMode must not be null");
            }
            this.spanProcessingMode = spanProcessingMode;
            return this;
        }

        public Builder spanScheduleDelay(Duration spanScheduleDelay) {
            this.spanScheduleDelay = requirePositive(spanScheduleDelay, "spanScheduleDelay");
            return this;
        }

        public Builder spanExportTimeout(Duration spanExportTimeout) {
            this.spanExportTimeout = requirePositive(spanExportTimeout, "spanExportTimeout");
            return this;
        }

        public Builder spanMaxQueueSize(int spanMaxQueueSize) {
            this.spanMaxQueueSize = requirePositive(spanMaxQueueSize, "spanMaxQueueSize");
            return this;
        }

        public Builder spanMaxExportBatchSize(int spanMaxExportBatchSize) {
            this.spanMaxExportBatchSize = requirePositive(spanMaxExportBatchSize, "spanMaxExportBatchSize");
            return this;
        }

        public Builder metricExportInterval(Duration metricExportInterval) {
            this.metricExportInterval = requirePositive(metricExportInterval, "metricExportInterval");
            return this;
        }

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
