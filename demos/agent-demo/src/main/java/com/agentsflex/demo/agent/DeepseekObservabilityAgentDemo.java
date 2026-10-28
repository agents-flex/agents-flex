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
                + "然后用中文简洁说明工具返回的时间，并表示链路已跑通。")
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

        AgentTurnOptions options = AgentTurnOptions.builder()
            .metadata("tenantId", "otel-demo")
            .metadata("requestId", "deepseek-real-call")
            .build();

        try {
            AgentTurn turn = runner.run(agent,
                "请查询 Asia/Shanghai 当前时间，并告诉我 Agents-Flex 的 Agent 链路是否正常。",
                options);
            DemoSupport.printTurn(turn);
            DemoSupport.require(turn.getStatus() == AgentTurnStatus.COMPLETED,
                "DeepSeek Agent 应正常完成");
            DemoSupport.require(turn.getToolCallCount() > 0,
                "DeepSeek Agent 应至少调用一次工具");
        } finally {
            // 命令行 Demo 立即退出，显式 shutdown 可将 Batch Span 和末尾 Metric 刷到 OTLP。
            Observability.shutdown();
        }
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
}
