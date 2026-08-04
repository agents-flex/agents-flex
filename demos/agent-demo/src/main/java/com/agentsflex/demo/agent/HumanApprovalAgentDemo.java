/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentWorker;
import com.agentsflex.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 演示高风险工具审批、Snapshot 和跨 Runner 恢复。 */
public final class HumanApprovalAgentDemo {

    private HumanApprovalAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 2 - Human-in-the-loop 与跨 Runner 恢复");

        // 计数器用于证明审批之前工具没有执行，并且恢复之后只执行了一次。
        AtomicInteger deployments = new AtomicInteger();
        Tool deployTool = Tool.builder("deploy_service", "将服务部署到生产环境")
            .addParameter(Parameter.builder()
                .name("service")
                .type("string")
                .required(true)
                .build())
            .addParameter(Parameter.builder()
                .name("version")
                .type("string")
                .required(true)
                .build())
            .metadata("riskLevel", "HIGH")
            .metadata("sideEffect", true)
            .metadata("binding", "deployment-platform")
            .function(arguments -> {
                deployments.incrementAndGet();
                return "已部署 " + arguments.get("service") + ":" + arguments.get("version");
            })
            .build();

        DemoScriptedChatModel model = new DemoScriptedChatModel()
            .enqueue(prompt -> DemoSupport.toolCalls(new ToolCall(
                "deploy-call-1", "deploy_service",
                "{\"service\":\"order-api\",\"version\":\"2.4.0\"}")))
            .enqueue(prompt -> new AiMessage("order-api 2.4.0 已完成生产发布。"));

        Agent agent = Agent.builder("release-agent")
            .id("release-agent")
            .version("1")
            .instructions("部署生产环境必须调用 deploy_service，并等待人工批准。")
            .chatModel(model)
            .tool(deployTool)
            // 审批策略读取 Tool metadata，业务可在这里叠加用户、环境和权限信息。
            .toolApprovalPolicy((run, call, tool) ->
                Boolean.TRUE.equals(tool.getMetadata().get("sideEffect"))
                    ? ToolApprovalDecision.requireApproval()
                        .code("PRODUCTION_DEPLOYMENT_REVIEW")
                        .message("生产发布需要人工批准")
                        .reason("部署工具会修改生产环境")
                        .metadata("riskLevel", tool.getMetadata().get("riskLevel"))
                        .build()
                    : ToolApprovalDecision.ALLOW)
            .build();

        // 两个 Runner 共享 Store 和 Loader，用来模拟“请求 A 暂停，请求 B 审批后恢复”。
        // 真实多进程部署应将 Store 替换为生产实现，并由业务 AgentLoader 从配置表组装 Agent。
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentLoader agentLoader = new InMemoryAgentLoader(agent);
        List<AgentEvent> events = new java.util.ArrayList<>();
        InMemoryAgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();

        AgentRunner firstRunner = new AgentRunner(
            runStore, agentLoader, commandStore)
            .addEventListener(events::add);
        // run() 会执行到终态或阻塞态；遇到审批点时返回 WAITING_FOR_APPROVAL。
        AgentRun waiting = firstRunner.run(agent, "发布 order-api 2.4.0");

        DemoSupport.printRun(waiting);
        DemoSupport.require(waiting.getStatus() == AgentRunStatus.WAITING_FOR_APPROVAL,
            "高风险工具执行前应暂停");
        DemoSupport.require(deployments.get() == 0, "审批前不能产生部署副作用");

        // pending ToolCall 已在审批前持久化。恢复时不会重新调用模型生成部署参数，
        // Runner 会从 Snapshot 指定版本的 Agent 中取得同名工具。
        AgentRunSnapshot snapshot = runStore.load(waiting.getId());
        DemoSupport.require("deploy_service".equals(
            snapshot.getState().getPendingToolCalls().get(0).getName()),
            "Snapshot 必须保存待执行 ToolCall");

        String callId = waiting.getSuspension().getCorrelationId();
        // correlationId 将审批决定绑定到当前待处理 ToolCall，metadata 用于保存审批审计信息。
        AgentResumeCommand approval = AgentResumeCommand.approveTool(callId)
            .withMetadata("approverId", "admin-1001")
            .withMetadata("approvalSource", "release-console");

        AgentRunner secondRunner = new AgentRunner(
            runStore, agentLoader, commandStore)
            .addEventListener(events::add);
        // 审批接口只负责把命令可靠写入 Inbox，不在当前请求中执行模型或部署工具。
        secondRunner.submitCommand("approval-deploy-call-1", waiting.getId(), approval);
        List<AgentRun> processed;
        try (AgentWorker worker = new AgentWorker("release-worker-01", secondRunner, 30_000)) {
            // Worker 先消费审批命令，再通过 Run Lease 领取恢复后的任务。
            processed = worker.pollAndRun(10);
        }
        DemoSupport.require(processed.size() == 1, "Worker 应领取批准后的部署任务");
        AgentRun completed = processed.get(0);

        DemoSupport.printRun(completed);
        DemoSupport.require(completed.getStatus() == AgentRunStatus.COMPLETED,
            "批准后应从 TOOLS 阶段继续并完成");
        DemoSupport.require(deployments.get() == 1, "部署工具只能执行一次");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> audit = (java.util.Map<String, Object>)
            completed.getMetadata().get("lastResumeCommandMetadata");
        System.out.println("approval audit: " + audit);

        // 业务系统可以在统一监听器中把事件写入自己的审计库或消息队列。
        System.out.println("event stream  :");
        for (AgentEvent event : events) {
            System.out.println("  " + event.getSequence() + " " + event.getType());
        }
    }
}
