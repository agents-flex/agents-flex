package com.agentsflex.observability.jdbc;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JdbcTelemetryExportersTest {
    private DataSource dataSource;

    @Before
    public void createSchema() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:otel_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource = h2;

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agents_flex_otel_spans (" +
                "trace_id VARCHAR(32),span_id VARCHAR(16),parent_span_id VARCHAR(16),trace_flags VARCHAR(2)," +
                "trace_state CLOB,span_name VARCHAR(255),span_kind VARCHAR(32),start_epoch_nanos BIGINT," +
                "end_epoch_nanos BIGINT,duration_nanos BIGINT,status_code VARCHAR(32),status_description VARCHAR(1024)," +
                "service_name VARCHAR(255),bot_id VARCHAR(255),conversation_id VARCHAR(255),account_id VARCHAR(255)," +
                "turn_id VARCHAR(255),scope_name VARCHAR(255)," +
                "scope_version VARCHAR(64),attributes_json CLOB,events_json CLOB,links_json CLOB," +
                "resource_attributes_json CLOB,total_attributes INT,total_events INT,total_links INT)");
            statement.execute("CREATE TABLE agents_flex_otel_metrics (" +
                "service_name VARCHAR(255),scope_name VARCHAR(255),scope_version VARCHAR(64),metric_name VARCHAR(255)," +
                "metric_description VARCHAR(1024),metric_unit VARCHAR(64),metric_type VARCHAR(64)," +
                "aggregation_temporality VARCHAR(32),monotonic BOOLEAN,start_epoch_nanos BIGINT,epoch_nanos BIGINT," +
                "value_long BIGINT,value_double DOUBLE,point_count BIGINT,point_sum DOUBLE,point_min DOUBLE," +
                "point_max DOUBLE,attributes_json CLOB,data_json CLOB,resource_attributes_json CLOB)");
        }
    }

    @Test
    public void shouldPersistSpansWithCorrelationFields() throws Exception {
        JdbcTelemetryExporters exporters = JdbcTelemetryExporters.builder(dataSource).build();
        Resource resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "order-agent"));
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(exporters.getSpanExporter()))
            .build();

        Span span = provider.get("jdbc-test").spanBuilder("test-chat").startSpan();
        span.setAttribute("agentsflex.bot.id", "bot-1");
        span.setAttribute("gen_ai.conversation.id", "conversation-42");
        span.setAttribute("enduser.id", "account-7");
        span.setAttribute("agentsflex.turn.id", "turn-3");
        span.addEvent("response.received");
        span.end();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT service_name,bot_id,conversation_id,account_id,turn_id,total_events " +
                     "FROM agents_flex_otel_spans")) {
            assertTrue(result.next());
            assertEquals("order-agent", result.getString(1));
            assertEquals("bot-1", result.getString(2));
            assertEquals("conversation-42", result.getString(3));
            assertEquals("account-7", result.getString(4));
            assertEquals("turn-3", result.getString(5));
            assertEquals(1, result.getInt(6));
        } finally {
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldPersistMetricPoints() throws Exception {
        JdbcTelemetryExporters exporters = JdbcTelemetryExporters.builder(dataSource).build();
        Resource resource = Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "order-agent"));
        PeriodicMetricReader reader = PeriodicMetricReader.builder(exporters.getMetricExporter())
            .setInterval(Duration.ofHours(1))
            .build();
        SdkMeterProvider provider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(reader)
            .build();

        LongCounter counter = provider.get("jdbc-test").counterBuilder("requests.total").build();
        counter.add(3, Attributes.of(AttributeKey.stringKey("route"), "chat"));
        assertTrue(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT service_name,metric_name,value_long,attributes_json FROM agents_flex_otel_metrics")) {
            assertTrue(result.next());
            assertEquals("order-agent", result.getString(1));
            assertEquals("requests.total", result.getString(2));
            assertEquals(3L, result.getLong(3));
            assertTrue(result.getString(4).contains("chat"));
        } finally {
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectUnsafeTableNames() {
        JdbcTelemetryExporters.builder(dataSource).spanTable("spans; DROP TABLE users").build();
    }
}
