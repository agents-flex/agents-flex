/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.observability.otel;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.core.observability.ObservabilityAttributeKeys;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 Agent 编排层发布的离散 {@link AgentEvent} 翻译成一棵 OpenTelemetry trace：
 * 一个 Turn 对应一个根 span（{@code agent.turn}），其内的模型调用、工具调用分别对应
 * {@code agent.model}、{@code agent.tool} 子 span，并记录工具相关 metric。
 *
 * <p>父子关系不依赖 OTel 的 thread-local {@link Context}：{@code AgentEventPublisher.publish}
 * 把监听器回调包进可配置的 {@code Executor}（默认同线程、也可配线程池），两种情形发布线程都不保证
 * 携带监听器期望的父 Context。因此根 span 显式存入 {@code turnId -> Span} 的
 * {@link ConcurrentHashMap}，子 span 构建时用
 * {@code spanBuilder(...).setParent(Context.root().with(parentSpan))} 显式挂父。</p>
 *
 * <p>该监听器只观察、不改执行决策，所有异常都在内部被吞掉并以 WARN 记录，绝不抛回 Runner。</p>
 */
public final class OpenTelemetryAgentEventListener implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryAgentEventListener.class);

    private static final String SPAN_TURN = "agent.turn";
    private static final String SPAN_MODEL = "agent.model";
    private static final String SPAN_TOOL = "agent.tool";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_ERROR = "error";

    private final Tracer tracer;

    /**
     * turnId -> 根 span。
     */
    private final ConcurrentHashMap<String, Span> turnSpans = new ConcurrentHashMap<>();
    /**
     * modelKey -> 在途 LLM 子 span。
     */
    private final ConcurrentHashMap<String, Span> modelSpans = new ConcurrentHashMap<>();
    /**
     * toolKey -> 在途工具子 span 及其计时起点。
     */
    private final ConcurrentHashMap<String, ToolSpanState> toolSpans = new ConcurrentHashMap<>();

    /**
     * Agent 模型 Token 用量直方图，维度 {@code agentsflex.agent.token.type}。
     */
    private final DoubleHistogram tokenUsage;
    /**
     * 工具调用计数，维度 {@code gen_ai.tool.name} + {@code agentsflex.outcome}。
     */
    private final LongCounter toolCallCount;
    /**
     * 工具失败计数，维度 {@code gen_ai.tool.name}。
     */
    private final LongCounter toolCallErrorCount;
    /**
     * 工具耗时（秒）直方图，维度 {@code gen_ai.tool.name} + {@code agentsflex.outcome}。
     */
    private final DoubleHistogram toolCallDuration;

    /**
     * @param tracer 用于创建 span 的 Tracer，不能为 {@code null}
     * @param meter  用于创建 metric instrument 的 Meter，不能为 {@code null}
     */
    public OpenTelemetryAgentEventListener(Tracer tracer, Meter meter) {
        if (tracer == null || meter == null) {
            throw new IllegalArgumentException("tracer and meter must not be null");
        }
        this.tracer = tracer;
        this.tokenUsage = meter.histogramBuilder("agentsflex.agent.model.token.usage")
            .setUnit("{token}")
            .setDescription("Number of tokens consumed by agent model calls")
            .build();
        this.toolCallCount = meter.counterBuilder("agentsflex.agent.tool.call.count")
            .setDescription("Number of agent tool calls")
            .build();
        this.toolCallErrorCount = meter.counterBuilder("agentsflex.agent.tool.call.error.count")
            .setDescription("Number of failed agent tool calls")
            .build();
        this.toolCallDuration = meter.histogramBuilder("agentsflex.agent.tool.call.duration")
            .setUnit("s")
            .setDescription("Duration of agent tool calls in seconds")
            .build();
    }

    @Override
    public void onEvent(AgentEvent e) {
        if (e == null) {
            return;
        }
        try {
            switch (e.getType()) {
                case TURN_STARTED:
                    onTurnStarted(e);
                    break;
                case MODEL_STARTED:
                    onModelStarted(e);
                    break;
                case MODEL_COMPLETED:
                    onModelCompleted(e);
                    break;
                case TOOL_STARTED:
                    onToolStarted(e);
                    break;
                case TOOL_COMPLETED:
                    onToolCompleted(e);
                    break;
                case TOOL_FAILED:
                    onToolFailed(e);
                    break;
                case TURN_COMPLETED:
                    onTurnEnded(e, null);
                    break;
                case TURN_FAILED:
                    onTurnEnded(e, string(e, "error"));
                    break;
                case TURN_CANCELLED:
                    // 取消是业务终止而非失败：结束根 span 但不置 ERROR（保持 UNSET）。
                    onTurnEnded(e, null);
                    break;
                case STEP_STARTED:
                case STEP_COMPLETED:
                    // no-op：span 树只保留 turn -> model/tool 两层，step 不建 span。
                    break;
                default:
                    // 其余事件类型（delta / 压缩 / 挂起 / 预算 等）本监听器不处理。
                    break;
            }
        } catch (RuntimeException error) {
            // 监听器只观察、不改执行决策；任何异常都吞掉并 WARN，绝不抛回 Runner。
            log.warn("OpenTelemetryAgentEventListener failed to handle event {}",
                e.getType(), error);
        }
    }

    private void onTurnStarted(AgentEvent e) {
        SpanBuilder builder = tracer.spanBuilder(SPAN_TURN)
            .setParent(Context.root())
            .setAttribute(ObservabilityAttributeKeys.TURN_ID, e.getTurnId())
            .setAttribute(OtelAttributeNames.AGENT_ID, e.getAgentId())
            .setAttribute(OtelAttributeNames.AGENT_VERSION, e.getAgentVersion())
            .setAttribute(OtelAttributeNames.EVENT_SEQUENCE, e.getSequence());
        Span span = builder.startSpan();
        Span previous = turnSpans.putIfAbsent(e.getTurnId(), span);
        if (previous != null) {
            // 同一 turn 重复 TURN_STARTED（正常流程不会发生）：丢弃新 span 避免泄漏。
            span.end();
            log.debug("Duplicate TURN_STARTED for turn {}, keeping existing root span",
                e.getTurnId());
        }
    }

    private void onModelStarted(AgentEvent e) {
        Span modelSpan = tracer.spanBuilder(SPAN_MODEL)
            .setParent(parentContext(e.getTurnId()))
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(ObservabilityAttributeKeys.TURN_ID, e.getTurnId())
            .startSpan();
        modelSpans.put(modelKey(e), modelSpan);
    }

    private void onModelCompleted(AgentEvent e) {
        Span modelSpan = modelSpans.remove(modelKey(e));
        if (modelSpan == null) {
            log.debug("MODEL_COMPLETED without matching MODEL_STARTED for turn {}", e.getTurnId());
            return;
        }
        recordModelUsage(modelSpan, e);
        modelSpan.end();
    }

    private void onToolStarted(AgentEvent e) {
        String toolName = string(e, "toolName");
        SpanBuilder builder = tracer.spanBuilder(SPAN_TOOL)
            .setParent(parentContext(e.getTurnId()))
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(ObservabilityAttributeKeys.TURN_ID, e.getTurnId());
        if (toolName != null) {
            builder.setAttribute(ObservabilityAttributeKeys.GEN_AI_TOOL_NAME, toolName);
        }
        Span toolSpan = builder.startSpan();
        toolSpans.put(toolKey(e), new ToolSpanState(toolSpan, System.nanoTime()));
    }

    private void onToolCompleted(AgentEvent e) {
        ToolSpanState state = toolSpans.remove(toolKey(e));
        if (state == null) {
            log.debug("TOOL_COMPLETED without matching TOOL_STARTED for turn {}", e.getTurnId());
            return;
        }
        state.span.end();
        String toolName = string(e, "toolName");
        toolCallCount.add(1, toolAttributes(toolName, OUTCOME_SUCCESS));
        toolCallDuration.record(seconds(state.startNanos), toolAttributes(toolName, OUTCOME_SUCCESS));
    }

    private void onToolFailed(AgentEvent e) {
        ToolSpanState state = toolSpans.remove(toolKey(e));
        String toolName = string(e, "toolName");
        String error = string(e, "error");
        if (state != null) {
            // 事件只给错误文本，没有真实 Throwable，因此只 setStatus，不 recordException。
            state.span.setStatus(StatusCode.ERROR, error == null ? "tool failed" : error);
            state.span.end();
            toolCallDuration.record(seconds(state.startNanos), toolAttributes(toolName, OUTCOME_ERROR));
        } else {
            log.debug("TOOL_FAILED without matching TOOL_STARTED for turn {}", e.getTurnId());
        }
        toolCallErrorCount.add(1, toolAttributes(toolName, null));
    }

    /**
     * 结束根 span（正常/取消 errorText 为 null；失败时传错误文本置 ERROR），并清理该 turn 仍残留的
     * 在途子 span，防内存泄漏。
     */
    private void onTurnEnded(AgentEvent e, String errorText) {
        String turnId = e.getTurnId();
        Span root = turnSpans.remove(turnId);
        if (root != null) {
            setTokenAttributes(root, e);
            if (errorText != null) {
                root.setStatus(StatusCode.ERROR, errorText);
            }
            root.end();
        } else {
            log.debug("Turn end event {} without matching root span for turn {}",
                e.getType(), turnId);
        }
        cleanupInFlight(turnId);
    }

    /**
     * 清理同一 turn 残留的在途 model/tool 子 span（缺失 completed 或乱序时兜底）。
     */
    private void cleanupInFlight(String turnId) {
        String prefix = turnId + "#";
        for (Iterator<Map.Entry<String, Span>> it = modelSpans.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Span> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().end();
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, ToolSpanState>> it = toolSpans.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ToolSpanState> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                entry.getValue().span.end();
                it.remove();
            }
        }
    }

    /**
     * 取根 span 作为父 Context；根 span 不存在时降级为无父的 root。
     */
    private Context parentContext(String turnId) {
        Span parent = turnSpans.get(turnId);
        if (parent == null) {
            log.debug("No root span for turn {}, child span will have no parent", turnId);
            return Context.root();
        }
        return Context.root().with(parent);
    }

    /**
     * MODEL_STARTED / MODEL_COMPLETED 用 turnId + iteration 配对；iteration 缺失回退仅 turnId。
     */
    private String modelKey(AgentEvent e) {
        Long iteration = asLong(e.getData().get("iteration"));
        return iteration == null ? e.getTurnId() + "#iter" : e.getTurnId() + "#iter#" + iteration;
    }

    /**
     * TOOL_* 用 turnId + toolCallId 配对；toolCallId 由 publisher 的 callKey 保证非空。
     */
    private String toolKey(AgentEvent e) {
        String toolCallId = string(e, "toolCallId");
        return e.getTurnId() + "#tool#" + toolCallId;
    }

    private Attributes toolAttributes(String toolName, String outcome) {
        AttributesBuilder builder = Attributes.builder();
        if (toolName != null) {
            builder.put(ObservabilityAttributeKeys.GEN_AI_TOOL_NAME, toolName);
        }
        if (outcome != null) {
            builder.put(OtelAttributeNames.OUTCOME, outcome);
        }
        return builder.build();
    }

    private void recordModelUsage(Span span, AgentEvent e) {
        setTokenAttributes(span, e);
        recordTokenMetric(e, "inputTokens", "input");
        recordTokenMetric(e, "outputTokens", "output");
        recordTokenMetric(e, "cacheReadInputTokens", "cache_read");
        recordTokenMetric(e, "cacheMissInputTokens", "cache_miss");
    }

    private void setTokenAttributes(Span span, AgentEvent e) {
        Long input = asLong(e.getData().get("inputTokens"));
        Long output = asLong(e.getData().get("outputTokens"));
        Long cacheRead = asLong(e.getData().get("cacheReadInputTokens"));
        Long cacheMiss = asLong(e.getData().get("cacheMissInputTokens"));
        if (input != null) span.setAttribute(ObservabilityAttributeKeys.GEN_AI_USAGE_INPUT_TOKENS, input);
        if (output != null) span.setAttribute(ObservabilityAttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS, output);
        if (cacheRead != null) {
            span.setAttribute(ObservabilityAttributeKeys.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS, cacheRead);
        }
        if (cacheMiss != null) {
            span.setAttribute(ObservabilityAttributeKeys.GEN_AI_USAGE_CACHE_MISS_INPUT_TOKENS, cacheMiss);
        }
    }

    private void recordTokenMetric(AgentEvent e, String field, String tokenType) {
        Long value = asLong(e.getData().get(field));
        if (value == null || value < 0) return;
        tokenUsage.record(value.doubleValue(), Attributes.of(
            ObservabilityAttributeKeys.AGENT_TOKEN_TYPE, tokenType));
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0d;
    }

    private static String string(AgentEvent e, String key) {
        Object value = e.getData().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /**
     * 工具子 span 与其计时起点的不可变封装。
     */
    private static final class ToolSpanState {
        final Span span;
        final long startNanos;

        ToolSpanState(Span span, long startNanos) {
            this.span = span;
            this.startNanos = startNanos;
        }
    }
}
