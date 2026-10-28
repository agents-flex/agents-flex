/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentBudget;
import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnOptions;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import com.agentsflex.observability.otel.OpenTelemetryAgentEventListener;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 使用真实 DeepSeek 和 OTLP 后端的 Agent 可观测 Demo。
 */
public final class DeepseekObservabilityAgentDemo {

    private static final String CACHE_STABLE_PROMPT =
        "请查询 Asia/Shanghai 当前时间，并告诉我 Agents-Flex 的 Agent 链路是否正常。"
            + "回答时请保持简洁，并明确说明模型调用、工具调用和最终响应三个阶段都已完成。";

    private DeepseekObservabilityAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 4 - DeepSeek + OpenTelemetry Agent");
        configureObservability();

        String apiKey = requiredEnvironment("DEEPSEEK_API_KEY");
        DeepseekConfig config = new DeepseekConfig();
        config.setProvider("deepseek");
        config.setApiKey(apiKey);
        config.setEndpoint(environment("DEEPSEEK_ENDPOINT", "https://api.deepseek.com"));
        config.setModel(environment("DEEPSEEK_MODEL", "deepseek-chat"));
        config.setObservabilityEnabled(true);
        config.setLogEnabled(false);

        Tool currentTime = Tool.builder("get_current_time", "获取指定时区的当前时间")
            .addParameter(Parameter.builder()
                .name("timezone")
                .type("string")
                .description("时区名称，例如 Asia/Shanghai")
                .required(true)
                .build())
            .metadata("provider", "local-demo")
            .metadata("sideEffect", false)
            .function(arguments -> {
                String timezone = String.valueOf(arguments.get("timezone"));
                return "timezone=" + timezone + ", localTime="
                    + ZonedDateTime.now(ZoneId.of(timezone))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            })
            .build();

        Agent agent = Agent.builder("deepseek-observability-agent")
            .id("deepseek-observability-agent")
            .version("1")
            .instructions("你是 Agents-Flex 可观测演示助手。"
                + "必须先调用 get_current_time 工具，timezone 传 Asia/Shanghai，"
                + "然后用中文简洁说明工具返回的时间，并表示链路已跑通。"
                + "这是一个用于验证模型供应商 Prompt Cache 的稳定前缀："
                + "你必须始终遵循相同的工具选择规则、相同的输出语言和相同的完成标准，"
                + "不要因为请求重复而省略工具调用，也不要改变既定的回答结构。")
            .chatModel(new DeepseekChatModel(config))
            .tool(currentTime)
            .executionPolicy(AgentExecutionPolicy.builder()
                .maxIterations(4)
                .budget(AgentBudget.builder()
                    .maxToolCalls(2)
                    .maxDurationMillis(60_000)
                    .build())
                .build())
            .build();

        AgentRunner runner = new AgentRunner()
            .addEventListener(new OpenTelemetryAgentEventListener(
                Observability.getTracer(), Observability.getMeter()));

        try {
            int probePairs = positiveEnvironmentInteger("DEEPSEEK_DEMO_CACHE_PROBE_PAIRS", 1);
            int pauseMillis = positiveEnvironmentInteger("DEEPSEEK_DEMO_PAUSE_MILLIS", 1);
            long totalRead = 0L;
            long totalMiss = 0L;
            for (int pair = 1; pair <= probePairs; pair++) {
                AgentTurn first = runCacheProbe(runner, agent, "pair-" + pair + "-first");
                pause(pauseMillis);
                AgentTurn second = runCacheProbe(runner, agent, "pair-" + pair + "-second");
                totalRead += first.getCacheReadInputTokens() + second.getCacheReadInputTokens();
                totalMiss += first.getCacheMissInputTokens() + second.getCacheMissInputTokens();
                System.out.println("Prompt Cache pair " + pair + "/" + probePairs
                    + " summary: first(read=" + first.getCacheReadInputTokens()
                    + ", miss=" + first.getCacheMissInputTokens()
                    + "), second(read=" + second.getCacheReadInputTokens()
                    + ", miss=" + second.getCacheMissInputTokens() + ")");
                if (pair < probePairs) {
                    pause(pauseMillis);
                }
            }
            System.out.println("Prompt Cache total: pairs=" + probePairs
                + ", read=" + totalRead + ", miss=" + totalMiss);
            System.out.println("提示：DeepSeek Prompt Cache 为尽力命中机制，单次演示不保证 read > 0。");
        } finally {
            // 命令行 Demo 立即退出，显式 shutdown 可将 Batch Span 和末尾 Metric 刷到 OTLP。
            Observability.shutdown();
        }
    }

    private static AgentTurn runCacheProbe(AgentRunner runner, Agent agent, String requestId) {
        AgentTurnOptions options = AgentTurnOptions.builder()
            .metadata("tenantId", "otel-demo")
            .metadata("requestId", "deepseek-cache-" + requestId)
            .build();
        System.out.println("Running cache probe: " + requestId);
        AgentTurn turn = runner.run(agent, CACHE_STABLE_PROMPT, options);
        DemoSupport.printTurn(turn);
        DemoSupport.require(turn.getStatus() == AgentTurnStatus.COMPLETED,
            "DeepSeek Agent 应正常完成");
        DemoSupport.require(turn.getToolCallCount() > 0,
            "DeepSeek Agent 应至少调用一次工具");
        return turn;
    }

    private static void configureObservability() {
        setDefaultProperty("agentsflex.otel.enabled", "true");
        setDefaultProperty("agentsflex.otel.exporter.type", "otlp");
        setDefaultProperty("agentsflex.otel.service.name", "agents-flex-deepseek-demo");
        setDefaultProperty("agentsflex.otel.metric.export.interval", "2");
        setDefaultProperty("otel.exporter.otlp.endpoint", "http://127.0.0.1:4317");
    }

    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " 未配置，无法调用真实 DeepSeek");
        }
        return value.trim();
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int positiveEnvironmentInteger(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
        return parsed;
    }

    private static void pause(int millis) {
        if (millis <= 1) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek Demo 等待期间被中断", interrupted);
        }
    }
}
