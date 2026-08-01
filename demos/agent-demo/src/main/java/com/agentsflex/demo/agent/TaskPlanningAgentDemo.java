/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.task.AgentPlanExecutor;
import com.agentsflex.core.agent.task.AgentPlanRun;
import com.agentsflex.core.agent.task.AgentTask;
import com.agentsflex.core.agent.task.AgentTaskPlan;
import com.agentsflex.core.agent.task.AgentTaskPlanSnapshot;
import com.agentsflex.core.agent.task.AgentTaskPlanStatus;
import com.agentsflex.core.agent.task.AgentTaskProgress;
import com.agentsflex.core.agent.task.InMemoryAgentTaskStore;
import com.agentsflex.core.message.AiMessage;

import java.util.Arrays;

/** 演示有序任务计划、进度查询和专业子 Agent 调度。 */
public final class TaskPlanningAgentDemo {

    private TaskPlanningAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 4 - 任务规划、进度与子 Agent");

        // 每个专业 Agent 拥有独立模型、指令、工具和 AgentRun，彼此不会共享临时消息状态。
        DemoScriptedChatModel analystModel = new DemoScriptedChatModel()
            .enqueue(prompt -> new AiMessage("影响模块：订单 API；主要风险：库存一致性。"));
        DemoScriptedChatModel testerModel = new DemoScriptedChatModel()
            .enqueue(prompt -> new AiMessage("测试通过：订单创建、库存扣减和回滚路径正常。"));
        DemoScriptedChatModel rootModel = new DemoScriptedChatModel()
            .enqueue(prompt -> {
                // 每个子任务完成后，Runner 会把结构化 Child Agent result 写回根 Run。
                long childResults = prompt.getMessages().stream()
                    .filter(message -> message.getTextContent().contains("Child Agent result:"))
                    .count();
                DemoSupport.require(childResults == 2, "根 Agent 应看到两个子任务结果");
                return new AiMessage("交付结论：改动验证通过，可以进入发布审批。");
            });

        Agent analyst = Agent.builder("analysis-agent")
            .id("analysis-agent")
            .version("1")
            .instructions("你负责分析变更范围和风险。")
            .chatModel(analystModel)
            .build();
        Agent tester = Agent.builder("test-agent")
            .id("test-agent")
            .version("1")
            .instructions("你负责验证关键业务路径。")
            .chatModel(testerModel)
            .build();

        Agent root = Agent.builder("delivery-agent")
            .id("delivery-agent")
            .version("1")
            .instructions("你负责汇总所有子 Agent 结果并给出交付结论。")
            .chatModel(rootModel)
            // Planner 只描述任务列表和分派关系；实际模型/工具执行仍由独立 AgentRun 承担。
            .taskPlanner((agent, context) -> new AgentTaskPlan(context.getGoal(), Arrays.asList(
                AgentTask.builder("分析变更")
                    .id("analyze")
                    .description("识别影响模块、风险和回滚点")
                    .assignedAgentId("analysis-agent")
                    .position(0)
                    .build(),
                AgentTask.builder("执行验证")
                    .id("verify")
                    .parentTaskId("analyze")
                    .description("验证订单、库存和回滚路径")
                    .assignedAgentId("test-agent")
                    .position(1)
                    .build())))
            .build();

        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        // assignedAgentId 通过 Registry 解析；生产环境应注册稳定且带版本治理的 Agent 定义。
        registry.register(analyst);
        registry.register(tester);
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(), registry);
        AgentPlanExecutor executor = new AgentPlanExecutor(runner, new InMemoryAgentTaskStore());

        AgentTaskPlanSnapshot plan = executor.start(root, "分析并验证订单服务变更");
        // start 只创建根 Run 和任务快照，所以此时可以先向用户展示或确认完整任务列表。
        printProgress(executor.getProgress(plan.getPlanId()));

        // runNext 最多推进一个任务，适合平台在每一步后刷新任务列表。
        AgentPlanRun firstTask = executor.runNext(plan.getPlanId());
        DemoSupport.require(firstTask.getPlan().getStatus() == AgentTaskPlanStatus.RUNNING,
            "第一个任务完成后计划仍应继续运行");
        printProgress(executor.getProgress(plan.getPlanId()));

        AgentPlanRun completed = executor.runUntilBlocked(plan.getPlanId());
        // 所有子任务完成后，执行器把结果写回根 Run，再由根 Agent 生成统一结论。
        printProgress(executor.getProgress(plan.getPlanId()));
        AgentRun rootRun = completed.getRootRun();
        DemoSupport.printRun(rootRun);

        DemoSupport.require(completed.getPlan().getStatus() == AgentTaskPlanStatus.COMPLETED,
            "所有任务和根 Agent 汇总都应完成");
        DemoSupport.require("交付结论：改动验证通过，可以进入发布审批。"
                .equals(rootRun.getFinalOutput()),
            "根 Agent 应生成最终汇总结论");
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
