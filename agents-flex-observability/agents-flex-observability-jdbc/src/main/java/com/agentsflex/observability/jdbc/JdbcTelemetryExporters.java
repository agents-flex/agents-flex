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

import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * A pair of OpenTelemetry exporters that persist spans and metric points through JDBC.
 *
 * <p>The supplied {@link DataSource} remains owned by the application. OpenTelemetry provides
 * batching and scheduling; each exporter invocation is written in one database transaction.</p>
 */
public final class JdbcTelemetryExporters {
    public static final String DEFAULT_SPAN_TABLE = "agents_flex_otel_spans";
    public static final String DEFAULT_METRIC_TABLE = "agents_flex_otel_metrics";

    private final SpanExporter spanExporter;
    private final MetricExporter metricExporter;

    private JdbcTelemetryExporters(Builder builder) {
        JdbcTelemetryRepository repository = new JdbcTelemetryRepository(
            builder.dataSource, builder.spanTable, builder.metricTable);
        this.spanExporter = new JdbcSpanExporter(repository);
        this.metricExporter = new JdbcMetricExporter(repository);
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    public SpanExporter getSpanExporter() {
        return spanExporter;
    }

    public MetricExporter getMetricExporter() {
        return metricExporter;
    }

    public static final class Builder {
        private final DataSource dataSource;
        private String spanTable = DEFAULT_SPAN_TABLE;
        private String metricTable = DEFAULT_METRIC_TABLE;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        public Builder spanTable(String spanTable) {
            this.spanTable = validateTableName(spanTable, "spanTable");
            return this;
        }

        public Builder metricTable(String metricTable) {
            this.metricTable = validateTableName(metricTable, "metricTable");
            return this;
        }

        public JdbcTelemetryExporters build() {
            spanTable = validateTableName(spanTable, "spanTable");
            metricTable = validateTableName(metricTable, "metricTable");
            return new JdbcTelemetryExporters(this);
        }

        private static String validateTableName(String value, String name) {
            if (value == null || !value.matches("[A-Za-z0-9_.$]+")) {
                throw new IllegalArgumentException(name + " contains unsupported characters: " + value);
            }
            return value;
        }
    }
}
