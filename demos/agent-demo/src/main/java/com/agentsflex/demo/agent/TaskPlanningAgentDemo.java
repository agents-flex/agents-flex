/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.task.AgentPlanningPolicy;
import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.task.AgentTask;
import com.agentsflex.agent.task.AgentTaskPlanStatus;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;

import java.util.Collections;

/** 演示模型自主规划、有序子任务、专业 Agent 委派和进度查询。 */
public final class TaskPlanningAgentDemo {

    private TaskPlanningAgentDemo() { }

    public static void main(String[] args) { run(); }

    static void run() {
        DemoSupport.section("Demo 4 - 模型自主任务规划");

        // 专业 Agent 使用独立模型和指令执行单个任务，执行过程仍是普通 AgentRun。
        DemoScriptedChatModel analystModel = new DemoScriptedChatModel()
            .enqueue(prompt -> new AiMessage("影响模块：订单 API；主要风险：库存一致性。"));
        DemoScriptedChatModel testerModel = new DemoScriptedChatModel()
            .enqueue(prompt -> new AiMessage("测试通过：订单创建、库存扣减和回滚路径正常。"));

        DemoScriptedChatModel rootModel = new DemoScriptedChatModel()
            .enqueue(prompt -> {
                // 实际模型会根据用户目标自行判断是否调用该工具；这里用固定响应展示协议。
                String arguments = "{\"goal\":\"分析并验证订单服务变更\",\"tasks\":["
                    + "{\"id\":\"analyze\",\"title\":\"分析变更\","
                    + "\"description\":\"识别影响模块、风险和回滚点\","
                    + "\"assignedAgentId\":\"analysis-agent\"},"
                    + "{\"id\":\"verify\",\"title\":\"执行验证\","
                    + "\"description\":\"验证订单、库存和回滚路径\","
                    + "\"assignedAgentId\":\"test-agent\"}]}";
                AiMessage message = new AiMessage();
                message.setToolCalls(Collections.singletonList(
                    new ToolCall("plan-1", AgentPlanningTool.NAME, arguments)));
                return message;
            })
            .enqueue(prompt -> {
                long childResults = prompt.getMessages().stream()
                    .filter(message -> message.getTextContent() != null
                        && message.getTextContent().contains("Child Agent result:"))
                    .count();
                DemoSupport.require(childResults == 2, "根 Agent 应看到两个子任务结果");
                return new AiMessage("交付结论：改动验证通过，可以进入发布审批。");
            });

        Agent analyst = Agent.builder("analysis-agent").chatModel(analystModel)
            .instructions("你负责分析变更范围和风险。").build();
        Agent tester = Agent.builder("test-agent").chatModel(testerModel)
            .instructions("你负责验证关键业务路径。").build();
        Agent root = Agent.builder("delivery-agent").chatModel(rootModel)
            .instructions("复杂目标可以先创建少量任务；简单问题直接回答。")
            // 委派目标使用显式允许列表，避免模型选择未经授权的 Agent。
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .allowAgent("analysis-agent").allowAgent("test-agent").build())
            .build();

        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(),
            new InMemoryAgentLoader(root, analyst, tester));

        // 调用方始终使用同一个入口，不需要预先判断本轮是否应该规划。
        AgentRun run = runner.run(root, "分析并验证订单服务变更");
        AgentTaskProgress progress = runner.getTaskProgress(run.getId());
        printProgress(progress);
        DemoSupport.printRun(run);

        DemoSupport.require(run.getStatus().isTerminal(), "根 Run 应结束");
        DemoSupport.require(progress.getStatus() == AgentTaskPlanStatus.COMPLETED,
            "计划和最终汇总都应完成");
    }

    private static void printProgress(AgentTaskProgress progress) {
        System.out.println("plan status  : " + progress.getStatus());
        System.out.println("progress     : " + progress.getCompletedTaskCount()
            + "/" + progress.getTotalTaskCount());
        for (AgentTask task : progress.getTasks()) {
            System.out.println("  [" + task.getStatus() + "] " + task.getTitle()
                + " -> " + task.getAssignedAgentId());
        }
    }
}
