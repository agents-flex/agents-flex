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
 * 创建一组通过 JDBC 持久化 Span 和 Metric point 的 OpenTelemetry Exporter。
 *
 * <p>本类只负责把应用提供的 {@link DataSource}、表名和两个 Exporter 组合起来，不创建数据库连接池，
 * 不执行建表，也不提供查询接口。DataSource 的所有权始终属于宿主应用，Exporter shutdown 不会关闭它。</p>
 *
 * <p>批处理与定时调度由 OTel 的 BatchSpanProcessor 和 PeriodicMetricReader 完成；Exporter 收到的每个
 * batch 会在一个数据库事务中写入。</p>
 */
public final class JdbcTelemetryExporters {
    /** 未显式配置时写入的默认 Span 表名。 */
    public static final String DEFAULT_SPAN_TABLE = "agents_flex_otel_spans";

    /** 未显式配置时写入的默认 Metric point 表名。 */
    public static final String DEFAULT_METRIC_TABLE = "agents_flex_otel_metrics";

    /** 与共享 Repository 绑定的 Span Exporter。 */
    private final SpanExporter spanExporter;

    /** 与共享 Repository 绑定的 Metric Exporter。 */
    private final MetricExporter metricExporter;

    private JdbcTelemetryExporters(Builder builder) {
        JdbcTelemetryRepository repository = new JdbcTelemetryRepository(
            builder.dataSource, builder.spanTable, builder.metricTable);
        this.spanExporter = new JdbcSpanExporter(repository);
        this.metricExporter = new JdbcMetricExporter(repository);
    }

    /** 使用默认表名创建构建器。 */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    /** 返回写入 Span 表的 Exporter。 */
    public SpanExporter getSpanExporter() {
        return spanExporter;
    }

    /** 返回写入 Metric point 表的 Exporter。 */
    public MetricExporter getMetricExporter() {
        return metricExporter;
    }

    /** JDBC Exporter 构建器，允许使用 schema-qualified 的自定义表名。 */
    public static final class Builder {
        /** 宿主应用提供且拥有生命周期的连接池或 DataSource。 */
        private final DataSource dataSource;

        /** Span INSERT 使用的目标表名，允许包含 schema 前缀。 */
        private String spanTable = DEFAULT_SPAN_TABLE;

        /** Metric INSERT 使用的目标表名，允许包含 schema 前缀。 */
        private String metricTable = DEFAULT_METRIC_TABLE;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        /** 设置 Span 目标表。表结构必须与模块提供的参考 DDL 兼容。 */
        public Builder spanTable(String spanTable) {
            this.spanTable = validateTableName(spanTable, "spanTable");
            return this;
        }

        /** 设置 Metric point 目标表。表结构必须与模块提供的参考 DDL 兼容。 */
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
            // JDBC 不支持把表名作为 PreparedStatement 参数，只允许有限字符以阻断动态 SQL 注入。
            if (value == null || !value.matches("[A-Za-z0-9_.$]+")) {
                throw new IllegalArgumentException(name + " contains unsupported characters: " + value);
            }
            return value;
        }
    }
}
