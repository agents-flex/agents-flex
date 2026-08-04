---
title: Demo：自动任务规划
description: 让根 Agent 自主创建计划，顺序委派给专业 Agent，并汇总任务结果。
---

# Demo：自动任务规划

## 概述

本示例把“分析并验证订单服务变更”交给根 Agent。根模型创建两个任务，分别委派给分析 Agent 与测试 Agent；Runner 为每个任务创建子 Run，收集结果后由根模型生成交付结论。

完整源码位于 `demos/agent-demo/src/main/java/com/agentsflex/demo/agent/TaskPlanningAgentDemo.java`。Demo 使用脚本模型固定响应，便于验证状态机，不依赖外部模型服务。

## 定义专业 Agent

```java
Agent analyst = Agent.builder("analysis-agent")
    .chatModel(analystModel)
    .instructions("你负责分析变更范围和风险。")
    .build();

Agent tester = Agent.builder("test-agent")
    .chatModel(testerModel)
    .instructions("你负责验证关键业务路径。")
    .build();
```

真实项目中，每个专业 Agent 应只绑定完成职责所需的最小工具集。例如分析 Agent 可读代码与变更记录，测试 Agent 可执行受控测试，但都不一定拥有发布权限。

## 定义根 Agent

```java
Agent root = Agent.builder("delivery-agent")
    .chatModel(rootModel)
    .instructions("复杂目标可以先创建少量任务；简单问题直接回答。")
    .planningPolicy(AgentPlanningPolicy.builder()
        .enabled(true)
        .allowAgent("analysis-agent")
        .allowAgent("test-agent")
        .maxTasks(4)
        .maxDepth(1)
        .build())
    .build();
```

白名单同时限制模型可选 ID 和 Runner 实际委派目标。Loader 仍必须能够加载这些 ID。

## 模型创建计划

实际模型会调用 `AgentPlanningTool.NAME`。工具参数结构如下：

```json
{
  "goal": "分析并验证订单服务变更",
  "tasks": [
    {
      "id": "analyze",
      "title": "分析变更",
      "description": "识别影响模块、风险和回滚点",
      "assignedAgentId": "analysis-agent"
    },
    {
      "id": "verify",
      "title": "执行验证",
      "description": "验证订单、库存和回滚路径",
      "assignedAgentId": "test-agent"
    }
  ]
}
```

Runner 校验字段、任务数量和 Agent 白名单后，把计划写入父 Run Snapshot。

## 运行并查询进度

```java
AgentRunner runner = new AgentRunner(
    new InMemoryAgentRunStore(),
    new InMemoryAgentLoader(root, analyst, tester));

AgentRun run = runner.run(root, "分析并验证订单服务变更");
AgentTaskProgress progress = runner.getTaskProgress(run.getId());

System.out.println(progress.getStatus());
System.out.println(progress.getCompletedTaskCount()
    + "/" + progress.getTotalTaskCount());
for (AgentTask task : progress.getTasks()) {
    System.out.println(task.getStatus() + " " + task.getTitle()
        + " -> " + task.getAssignedAgentId());
}
```

同步示例会在同一调用链执行子 Run。在 Worker 模式中，父 Run 会处于 `WAITING_FOR_CHILD`，子任务可能由其他节点领取，UI 可持续查询 `AgentTaskProgress` 或订阅任务事件。

## 最终汇总

所有任务结束后，父计划进入 `FINALIZING`，根模型看到受控的子结果消息并生成最终结论。若 `finalSummaryRequired=false`，Runner 可使用最后一个有效任务结果完成，以减少一次模型请求。

## 失败与重规划

配置 `maxReplans`、任务修改与追加策略后，子任务失败可使计划进入 `REPLANNING`。根模型只能调整尚未执行的任务，已完成结果不会被回滚。对已经产生外部副作用的任务，仍需业务补偿机制。

## 生产化改造

- 使用真实 Tool Calling 模型，并在指令中定义规划阈值。
- 为每个 Agent 配置最小权限工具和独立预算。
- 持久化 Run Store，使用 Worker 执行长任务；业务恢复事件和审计由应用自行保存。
- 监控任务数、深度、重规划次数和子结果截断。
- 根 Agent 输出只汇总事实，不把失败任务伪装为完成。

## 运行仓库 Demo

```bash
mvn -pl demos/agent-demo -am test
mvn -f demos/agent-demo/pom.xml exec:java \
  -Dexec.mainClass=com.agentsflex.demo.agent.TaskPlanningAgentDemo
```
