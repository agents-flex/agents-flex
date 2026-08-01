---
title: 任务规划、进度与子 Agent
description: 让模型自主决定是否创建任务计划，并用普通 AgentRun 执行、委派和恢复子任务。
---

# 任务规划、进度与子 Agent

## 概述

用户说“你好”时不需要任务列表；用户要求分析变更、验证关键路径并生成上线建议时，结构化计划能让模型逐项执行，也让产品展示已完成、正在执行和待执行任务。

规划不是另一套执行体系。启用后，Runner 向模型提供内置规划工具，由模型根据用户输入决定是否调用。计划直接保存在根 `AgentRunSnapshot`，每个任务使用普通子 `AgentRun` 执行，因此审批、重试、预算、Worker 和事件能力自然复用。

## 快速开发

```java
Agent root = Agent.builder("delivery-agent")
    .id("delivery-agent")
    .description("分析交付变更并汇总结论")
    .chatModel(rootModel)
    .planningPolicy(AgentPlanningPolicy.builder()
        .enabled(true)
        .maxTasks(6)
        .allowAgent("analysis-agent")
        .allowAgent("test-agent")
        .build())
    .build();

AgentRunner runner = AgentRunner.builder()
    .agentLoader(new InMemoryAgentLoader(root, analysisAgent, testAgent))
    .build();

AgentRun run = runner.run(root, "分析并验证订单服务变更");
AgentTaskProgress progress = runner.getTaskProgress(run.getId());
```

调用方仍然只调用 Runner，不需要预判本轮是否应该规划，也不需要创建独立 PlanExecutor。

## 模型如何创建计划

启用规划后，模型能看到框架内置的结构化工具及允许委派的 Agent 描述。简单请求可以直接回答；复杂请求可以生成 goal 和有序 tasks。Runner 识别内置调用，校验任务数量、ID、委派目标和策略，然后保存计划。

`AgentPlanningTool` 是模型与 Runner 的内部协议实现，业务代码通常不应直接调用它。平台只需配置 `AgentPlanningPolicy`。

## 任务数据

每个 `AgentTask` 包含 ID、标题、说明、期望输出、位置、状态、assignedAgentId、childRunId、结果、错误和时间戳。`AgentTaskPlan` 还保存总体目标、当前任务、重规划次数和计划状态。

```java
for (AgentTask task : progress.getTasks()) {
    System.out.println(task.getStatus() + " " + task.getTitle());
}
System.out.println(progress.getCompletedTaskCount()
    + "/" + progress.getTotalTaskCount());
```

`runner.getTaskProgress(runId)` 会组合根计划与当前活动子 Run 的真实状态，因此审批界面可以显示某个任务正在等待批准，而不只是显示 RUNNING。

## 子 Agent 委派

任务不指定 assignedAgentId 时由当前 Agent 执行；指定其他 ID 时，目标必须在 `allowedAgentIds` 中，并由 AgentLoader 的 `loadActive` 返回完整 Agent。

子 Agent 有自己的模型、指令、工具、预算、消息和 Checkpoint。它记录 parentRunId，整棵任务树共享 rootRunId。父 Run 进入 `WAITING_FOR_CHILD`，子 Run 结束后结果被截断到策略允许长度，再写回父计划和父 Prompt。

这适合把搜索、代码分析和测试分别交给专业 Agent；只有简单的一两个工具调用时，单 Agent 通常更容易维护。

## 规划策略

```java
AgentPlanningPolicy policy = AgentPlanningPolicy.builder()
    .enabled(true)
    .maxTasks(8)
    .maxDepth(2)
    .childPlanningAllowed(false)
    .failureStrategy(AgentPlanningPolicy.FailureStrategy.STOP)
    .planningInstructions("任务必须可独立验证，避免重复步骤")
    .maxReplans(1)
    .taskRevisionAllowed(true)
    .taskAppendAllowed(false)
    .taskResultMaxLength(8_000)
    .finalSummaryRequired(true)
    .allowAgent("research-agent")
    .build();
```

- maxDepth 防止无限嵌套规划；
- FailureStrategy 决定子任务失败后停止还是继续；
- maxReplans 与 revision/append 控制模型能否调整未执行任务；
- taskResultMaxLength 防止子任务大结果撑满父上下文；
- finalSummaryRequired 决定全部任务后是否再调用父模型汇总。

## 同步与分布式执行

同步 Runner 会依次执行子 Run并恢复父 Run，适合短任务和本地开发。Worker 模式下，父子创建通过 `AgentRunStore.saveParentAndChild` 原子提交；子 Run 由 Worker 独立领取，完成后 Worker 恢复父 Run。即使 Worker 在子任务完成后、唤醒父任务前退出，`recoverCompletedChildren` 也能扫描并修复。

计划与根 Snapshot 使用同一个版本保存，`AgentRunStore` 是规划恢复的事实来源，不需要另一套独立的任务执行状态。
