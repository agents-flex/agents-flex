package com.agentsflex.observability.otel;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OpenTelemetryAgentEventListenerTest {

    private InMemorySpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private InMemoryMetricReader metricReader;
    private SdkMeterProvider meterProvider;
    private OpenTelemetryAgentEventListener listener;

    private long sequence;

    @Before
    public void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
        metricReader = InMemoryMetricReader.create();
        meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metricReader)
            .build();
        listener = new OpenTelemetryAgentEventListener(
            tracerProvider.get("otel-agent-test"), meterProvider.get("otel-agent-test"));
        sequence = 0;
    }

    @After
    public void tearDown() {
        tracerProvider.close();
        meterProvider.close();
    }

    private AgentEvent event(String turnId, AgentEventType type, Map<String, Object> data) {
        return new AgentEvent(turnId, "agent-1", "v1", ++sequence, type, data);
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private SpanData findSpan(List<SpanData> spans, String name) {
        for (SpanData span : spans) {
            if (name.equals(span.getName())) {
                return span;
            }
        }
        return null;
    }

    @Test
    public void shouldBuildTurnModelToolSpanTree() {
        String turn = "turn-1";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.MODEL_STARTED, data("iteration", 1)));
        listener.onEvent(event(turn, AgentEventType.MODEL_COMPLETED, data("iteration", 1,
            "inputTokens", 20, "outputTokens", 5,
            "cacheReadInputTokens", 12, "cacheMissInputTokens", 8)));
        listener.onEvent(event(turn, AgentEventType.TOOL_STARTED, data("toolCallId", "t1", "toolName", "search")));
        listener.onEvent(event(turn, AgentEventType.TOOL_COMPLETED, data("toolCallId", "t1", "toolName", "search")));
        listener.onEvent(event(turn, AgentEventType.TURN_COMPLETED, null));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertEquals(3, spans.size());

        SpanData turnSpan = findSpan(spans, "agent.turn");
        SpanData modelSpan = findSpan(spans, "agent.model");
        SpanData toolSpan = findSpan(spans, "agent.tool");
        assertNotNull(turnSpan);
        assertNotNull(modelSpan);
        assertNotNull(toolSpan);

        // turn 是根，没有有效父。
        assertFalse(turnSpan.getParentSpanContext().isValid());
        // model/tool 挂在 turn 下，且同一 traceId。
        assertEquals(turnSpan.getSpanContext().getSpanId(),
            modelSpan.getParentSpanContext().getSpanId());
        assertEquals(turnSpan.getSpanContext().getSpanId(),
            toolSpan.getParentSpanContext().getSpanId());
        assertEquals(turnSpan.getSpanContext().getTraceId(),
            modelSpan.getSpanContext().getTraceId());
        assertEquals(turnSpan.getSpanContext().getTraceId(),
            toolSpan.getSpanContext().getTraceId());

        // turn span 带 agentsflex.turn.id 属性。
        assertEquals(turn,
            turnSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("agentsflex.turn.id")));
        assertEquals(Long.valueOf(12), modelSpan.getAttributes().get(
            com.agentsflex.core.observability.ObservabilityAttributeKeys.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS));
    }

    @Test
    public void shouldMarkFailureSpansAsError() {
        String turn = "turn-2";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.TOOL_STARTED, data("toolCallId", "t1", "toolName", "search")));
        listener.onEvent(event(turn, AgentEventType.TOOL_FAILED, data("toolCallId", "t1", "toolName", "search", "error", "X: boom")));
        listener.onEvent(event(turn, AgentEventType.TURN_FAILED, data("error", "Y: fatal")));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = findSpan(spans, "agent.tool");
        SpanData turnSpan = findSpan(spans, "agent.turn");
        assertNotNull(toolSpan);
        assertNotNull(turnSpan);
        assertEquals(StatusCode.ERROR, toolSpan.getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR, turnSpan.getStatus().getStatusCode());
    }

    @Test
    public void shouldNotMarkCancelledTurnAsError() {
        String turn = "turn-3";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.TURN_CANCELLED, null));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData turnSpan = findSpan(spans, "agent.turn");
        assertNotNull(turnSpan);
        assertEquals(StatusCode.UNSET, turnSpan.getStatus().getStatusCode());
    }

    @Test
    public void shouldRecordToolMetrics() {
        String turn = "turn-4";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.TOOL_STARTED, data("toolCallId", "t1", "toolName", "search")));
        listener.onEvent(event(turn, AgentEventType.TOOL_COMPLETED, data("toolCallId", "t1", "toolName", "search")));
        listener.onEvent(event(turn, AgentEventType.TOOL_STARTED, data("toolCallId", "t2", "toolName", "calc")));
        listener.onEvent(event(turn, AgentEventType.TOOL_FAILED, data("toolCallId", "t2", "toolName", "calc", "error", "E: bad")));
        listener.onEvent(event(turn, AgentEventType.TURN_COMPLETED, null));

        Collection<MetricData> metrics = metricReader.collectAllMetrics();
        assertTrue(hasMetric(metrics, "agentsflex.agent.tool.call.count"));
        assertTrue(hasMetric(metrics, "agentsflex.agent.tool.call.error.count"));
        assertTrue(hasMetric(metrics, "agentsflex.agent.tool.call.duration"));
    }

    @Test
    public void shouldDegradeGracefullyWhenRootSpanMissing() {
        // MODEL_STARTED 先于 TURN_STARTED：不抛异常，model span 以无效父结束。
        String turn = "turn-5";
        listener.onEvent(event(turn, AgentEventType.MODEL_STARTED, data("iteration", 1)));
        listener.onEvent(event(turn, AgentEventType.MODEL_COMPLETED, data("iteration", 1)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData modelSpan = findSpan(spans, "agent.model");
        assertNotNull(modelSpan);
        assertFalse(modelSpan.getParentSpanContext().isValid());
    }

    @Test
    public void shouldNotRecordTokenMetricWhenEventHasNoTokens() {
        String turn = "turn-6";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.MODEL_STARTED, data("iteration", 1)));
        listener.onEvent(event(turn, AgentEventType.MODEL_COMPLETED, data("iteration", 1)));
        listener.onEvent(event(turn, AgentEventType.TURN_COMPLETED, null));

        Collection<MetricData> metrics = metricReader.collectAllMetrics();
        assertFalse(hasMetric(metrics, "agentsflex.agent.token.usage"));
    }

    @Test
    public void shouldRecordModelAndTurnCacheUsage() {
        String turn = "turn-7";
        listener.onEvent(event(turn, AgentEventType.TURN_STARTED, null));
        listener.onEvent(event(turn, AgentEventType.MODEL_STARTED, data("iteration", 1)));
        listener.onEvent(event(turn, AgentEventType.MODEL_COMPLETED, data("iteration", 1,
            "inputTokens", 100, "outputTokens", 20,
            "cacheReadInputTokens", 75, "cacheMissInputTokens", 25)));
        listener.onEvent(event(turn, AgentEventType.TURN_COMPLETED, data(
            "inputTokens", 100, "outputTokens", 20, "totalTokens", 120,
            "cacheReadInputTokens", 75, "cacheMissInputTokens", 25)));

        SpanData turnSpan = findSpan(spanExporter.getFinishedSpanItems(), "agent.turn");
        assertEquals(Long.valueOf(75), turnSpan.getAttributes().get(
            com.agentsflex.core.observability.ObservabilityAttributeKeys.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS));
        assertEquals(Long.valueOf(25), turnSpan.getAttributes().get(
            com.agentsflex.core.observability.ObservabilityAttributeKeys.GEN_AI_USAGE_CACHE_MISS_INPUT_TOKENS));
        assertTrue(hasMetric(metricReader.collectAllMetrics(), "agentsflex.agent.token.usage"));
    }

    private static boolean hasMetric(Collection<MetricData> metrics, String name) {
        for (MetricData metric : metrics) {
            if (name.equals(metric.getName())) {
                return true;
            }
        }
        return false;
    }
}
