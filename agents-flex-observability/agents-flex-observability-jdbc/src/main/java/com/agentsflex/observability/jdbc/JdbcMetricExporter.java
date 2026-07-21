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
package com.agentsflex.observability.jdbc;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

final class JdbcMetricExporter implements MetricExporter {
    private static final Logger logger = LoggerFactory.getLogger(JdbcMetricExporter.class);
    private static final AggregationTemporalitySelector TEMPORALITY =
        AggregationTemporalitySelector.alwaysCumulative();
    private static final DefaultAggregationSelector AGGREGATION = DefaultAggregationSelector.getDefault();

    private final JdbcTelemetryRepository repository;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    JdbcMetricExporter(JdbcTelemetryRepository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        if (shutdown.get()) {
            return CompletableResultCode.ofFailure();
        }
        if (metrics == null || metrics.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            repository.writeMetrics(metrics);
            return CompletableResultCode.ofSuccess();
        } catch (Throwable error) {
            logger.warn("Failed to persist {} OpenTelemetry metrics", metrics.size(), error);
            return CompletableResultCode.ofExceptionalFailure(error);
        }
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
        shutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
