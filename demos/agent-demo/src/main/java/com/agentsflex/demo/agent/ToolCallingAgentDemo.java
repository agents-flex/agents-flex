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
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunOptions;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.tool.AgentToolInvocation;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 演示最小的“模型决策、工具执行、结果回传、模型回答”闭环。 */
public final class ToolCallingAgentDemo {

    private ToolCallingAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 1 - 基础 ToolCall Agent");

        // AtomicReference 只用于把工具线程内观察到的幂等键带回主流程，方便在示例末尾校验。
        AtomicReference<String> idempotencyKey = new AtomicReference<>();

        // Tool metadata 不会发送给模型作为参数，它用于审批、路由、审计和跨进程工具解析。
        Tool weatherTool = Tool.builder("query_weather", "查询指定城市天气")
            .addParameter(Parameter.builder()
                .name("city")
                .type("string")
                .description("城市名称")
                .required(true)
                .build())
            .metadata("provider", "demo-weather-service")
            .metadata("sideEffect", false)
            .function(arguments -> {
                // AgentToolInvocation 由 Runner 放入 ToolContext。真实写操作可以把这个稳定键
                // 传给外部服务，避免进程在 Tool 成功后、Checkpoint 保存前崩溃造成重复副作用。
                AgentToolInvocation invocation = AgentToolInvocation.current();
                DemoSupport.require(invocation != null, "工具应当获得 Agent 调用上下文");
                idempotencyKey.set(invocation.getIdempotencyKey());
                return arguments.get("city") + "：晴，26 摄氏度";
            })
            .build();

        DemoScriptedChatModel model = new DemoScriptedChatModel()
            // 第一轮模型根据用户问题选择工具。ToolCall ID 在整个恢复和重试过程中保持不变。
            .enqueue(prompt -> DemoSupport.toolCalls(
                new ToolCall("weather-call-1", "query_weather", "{\"city\":\"杭州\"}")))
            .enqueue(prompt -> {
                // 第二次模型调用必须看到 ToolMessage。Runner 自动维护协议消息，业务无需手工拼接。
                List<Message> messages = prompt.getMessages();
                Message last = messages.get(messages.size() - 1);
                DemoSupport.require(last instanceof ToolMessage, "模型应看到工具结果消息");
                DemoSupport.require(last.getTextContent().contains("晴"), "工具结果内容应完整回传");
                return new AiMessage("杭州今天晴，气温约 26 摄氏度。");
            });

        Agent agent = Agent.builder("weather-agent")
            .id("weather-agent")
            .version("1")
            .instructions("你是天气助手，需要数据时调用工具，得到结果后再回答。")
            .chatModel(model)
            .tool(weatherTool)
            .executionPolicy(AgentExecutionPolicy.builder()
                // maxIterations 限制模型决策轮次，Budget 进一步约束工具次数和总运行时间。
                .maxIterations(4)
                .budget(AgentBudget.builder()
                    .maxToolCalls(2)
                    .maxDurationMillis(30_000)
                    .build())
                .build())
            .build();

        AgentRunner runner = new AgentRunner();
        // Run metadata 会随 Snapshot 持久化，可承载租户、请求和业务追踪标识。
        AgentRunOptions options = AgentRunOptions.builder()
            .metadata("tenantId", "demo-tenant")
            .metadata("requestId", "weather-request-001")
            .build();
        AgentRun completed = runner.run(agent, "杭州今天天气如何？", options);

        DemoSupport.printRun(completed);
        System.out.println("idempotency : " + idempotencyKey.get());
        DemoSupport.require(completed.getStatus() == AgentRunStatus.COMPLETED,
            "基础 Agent 应正常完成");
        DemoSupport.require(model.getCallCount() == 2, "模型应调用两次");
        DemoSupport.require((completed.getId() + ":weather-call-1").equals(idempotencyKey.get()),
            "幂等键应由 Run ID 和 ToolCall ID 构成");
    }
}
