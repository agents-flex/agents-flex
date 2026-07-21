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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;

/**
 * JDBC 持久化的内部仓储，负责 SQL、字段映射和事务边界。
 *
 * <p>该类不做异步调度和重试。上游 Exporter 每调用一次 write 方法，就获取一个连接并以一个事务提交整个
 * batch，保证不会出现半批成功、半批失败。</p>
 */
final class JdbcTelemetryRepository {
    /** 从 Resource 中读取服务名时使用的标准 OTel 属性键。 */
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    /** 与 core ObservabilityAttributeKeys 约定一致的 Bot ID wire key。 */
    private static final AttributeKey<String> BOT_ID = AttributeKey.stringKey("agentsflex.bot.id");

    /** 与 core ObservabilityAttributeKeys 约定一致的 Turn ID wire key。 */
    private static final AttributeKey<String> TURN_ID = AttributeKey.stringKey("agentsflex.turn.id");

    /** 与 core ObservabilityAttributeKeys 约定一致的 OpenTelemetry 会话 ID wire key。 */
    private static final AttributeKey<String> CONVERSATION_ID = AttributeKey.stringKey("gen_ai.conversation.id");

    /** 与 core ObservabilityAttributeKeys 约定一致的最终用户 ID wire key。 */
    private static final AttributeKey<String> ACCOUNT_ID = AttributeKey.stringKey("enduser.id");

    /** 为兼容旧版 Agents-Flex 数据而保留的会话 ID 属性键。 */
    private static final AttributeKey<String> LEGACY_CONVERSATION_ID =
        AttributeKey.stringKey("agentsflex.conversation.id");

    /** 为兼容旧版 Agents-Flex 数据而保留的账号 ID 属性键。 */
    private static final AttributeKey<String> LEGACY_ACCOUNT_ID = AttributeKey.stringKey("agentsflex.account.id");

    /** 宿主应用提供的 DataSource；Repository 使用但不关闭它。 */
    private final DataSource dataSource;

    /** 根据已校验 Span 表名生成的 INSERT SQL 模板。 */
    private final String spanInsertSql;

    /** 根据已校验 Metric 表名生成的 INSERT SQL 模板。 */
    private final String metricInsertSql;

    JdbcTelemetryRepository(DataSource dataSource, String spanTable, String metricTable) {
        this.dataSource = dataSource;
        this.spanInsertSql = "INSERT INTO " + spanTable + " (" +
            "trace_id,span_id,parent_span_id,trace_flags,trace_state,span_name,span_kind," +
            "start_epoch_nanos,end_epoch_nanos,duration_nanos,status_code,status_description," +
            "service_name,bot_id,conversation_id,account_id,turn_id,scope_name,scope_version,attributes_json," +
            "events_json,links_json,resource_attributes_json,total_attributes,total_events,total_links" +
            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        this.metricInsertSql = "INSERT INTO " + metricTable + " (" +
            "service_name,scope_name,scope_version,metric_name,metric_description,metric_unit,metric_type," +
            "aggregation_temporality,monotonic,start_epoch_nanos,epoch_nanos,value_long,value_double," +
            "point_count,point_sum,point_min,point_max,attributes_json,data_json,resource_attributes_json" +
            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    }

    void writeSpans(Collection<SpanData> spans) throws SQLException {
        // PreparedStatement 在一个 batch 内复用，减少数据库往返和 SQL 解析开销。
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(spanInsertSql)) {
                for (SpanData span : spans) {
                    bindSpan(statement, span);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    void writeMetrics(Collection<MetricData> metrics) throws SQLException {
        // OTel 的 MetricData 是“指标 + 多个 point”的聚合结构，先展开为与数据库行一一对应的记录。
        List<MetricPointRecord> records = MetricPointRecord.from(metrics);
        if (records.isEmpty()) {
            return;
        }
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(metricInsertSql)) {
                for (MetricPointRecord record : records) {
                    bindMetric(statement, record);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    private static void bindSpan(PreparedStatement statement, SpanData span) throws SQLException {
        // 索引顺序必须与构造函数中的 INSERT 列严格一致。常用关联字段单独落列，其余属性保留为 JSON。
        int index = 1;
        statement.setString(index++, span.getTraceId());
        statement.setString(index++, span.getSpanId());
        statement.setString(index++, span.getParentSpanId());
        statement.setString(index++, span.getSpanContext().getTraceFlags().asHex());
        statement.setString(index++, TelemetryJson.value(span.getSpanContext().getTraceState().asMap()));
        statement.setString(index++, span.getName());
        statement.setString(index++, span.getKind().name());
        statement.setLong(index++, span.getStartEpochNanos());
        statement.setLong(index++, span.getEndEpochNanos());
        statement.setLong(index++, span.getEndEpochNanos() - span.getStartEpochNanos());
        statement.setString(index++, span.getStatus().getStatusCode().name());
        setNullableString(statement, index++, span.getStatus().getDescription());
        setNullableString(statement, index++, span.getResource().getAttribute(SERVICE_NAME));
        setNullableString(statement, index++, span.getAttributes().get(BOT_ID));
        setNullableString(statement, index++, first(span.getAttributes(),
            CONVERSATION_ID, LEGACY_CONVERSATION_ID));
        setNullableString(statement, index++, first(span.getAttributes(),
            ACCOUNT_ID, LEGACY_ACCOUNT_ID));
        setNullableString(statement, index++, span.getAttributes().get(TURN_ID));
        InstrumentationScopeInfo scope = span.getInstrumentationScopeInfo();
        statement.setString(index++, scope.getName());
        setNullableString(statement, index++, scope.getVersion());
        statement.setString(index++, TelemetryJson.attributes(span.getAttributes()));
        statement.setString(index++, TelemetryJson.events(span.getEvents()));
        statement.setString(index++, TelemetryJson.links(span.getLinks()));
        statement.setString(index++, TelemetryJson.attributes(span.getResource().getAttributes()));
        statement.setInt(index++, span.getTotalAttributeCount());
        statement.setInt(index++, span.getTotalRecordedEvents());
        statement.setInt(index, span.getTotalRecordedLinks());
    }

    private static void bindMetric(PreparedStatement statement, MetricPointRecord record) throws SQLException {
        // 标量值和常用聚合统计拆成列；桶边界、桶计数、分位数等可变结构保存在 data_json。
        MetricData metric = record.metric;
        int index = 1;
        setNullableString(statement, index++, metric.getResource().getAttribute(SERVICE_NAME));
        statement.setString(index++, metric.getInstrumentationScopeInfo().getName());
        setNullableString(statement, index++, metric.getInstrumentationScopeInfo().getVersion());
        statement.setString(index++, metric.getName());
        setNullableString(statement, index++, metric.getDescription());
        setNullableString(statement, index++, metric.getUnit());
        statement.setString(index++, metric.getType().name());
        setNullableString(statement, index++, record.temporality);
        setNullableBoolean(statement, index++, record.monotonic);
        statement.setLong(index++, record.point.getStartEpochNanos());
        statement.setLong(index++, record.point.getEpochNanos());
        setNullableLong(statement, index++, record.valueLong);
        setNullableDouble(statement, index++, record.valueDouble);
        setNullableLong(statement, index++, record.count);
        setNullableDouble(statement, index++, record.sum);
        setNullableDouble(statement, index++, record.min);
        setNullableDouble(statement, index++, record.max);
        statement.setString(index++, TelemetryJson.attributes(record.point.getAttributes()));
        setNullableString(statement, index++, record.dataJson);
        statement.setString(index, TelemetryJson.attributes(metric.getResource().getAttributes()));
    }

    private void inTransaction(SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            // 兼容连接池预设的 autoCommit 状态，并在归还连接前恢复原值，避免污染后续借用者。
            boolean originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                work.execute(connection);
                connection.commit();
            } catch (SQLException error) {
                // rollback 失败作为 suppressed exception 附加，保留最初导致事务失败的 SQL 异常。
                rollback(connection, error);
                throw error;
            } finally {
                if (originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
            }
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private static String first(Attributes attributes, AttributeKey<String> first, AttributeKey<String> second) {
        String value = attributes.get(first);
        return value != null ? value : attributes.get(second);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute(Connection connection) throws SQLException;
    }
}
