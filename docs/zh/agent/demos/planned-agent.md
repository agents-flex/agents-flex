---
title: Demo：自动任务规划 Agent
description: 使用任务规划器、专业子 Agent、进度查询和审批恢复完成一个复杂目标。
---

# Demo：自动任务规划 Agent

<div v-pre>

## Demo 目标

这个示例让根 Agent 把“分析、验证并发布一个服务”拆成有序任务。分析任务交给根 Agent，验证任务交给测试 Agent，发布任务交给发布 Agent，并在发布工具执行前暂停审批。

仓库中对应的离线可运行源码位于：

```text
demos/agent-demo/src/main/java/com/agentsflex/demo/agent/TaskPlanningAgentDemo.java
```

运行命令：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=planning
```

可运行源码聚焦任务拆分、逐步进度查询、专业子 Agent 和根 Agent 汇总。本页进一步展示如何把工具审批加入计划执行过程。

计划执行期间可以随时查询：

- 当前计划状态；
- 已完成和未完成的任务；
- 当前活动任务及其子 Run；
- 是否在等待工具审批；
- 每个任务的结果或错误。

## 定义专业 Agent

```java
Agent testingAgent = Agent.builder("testing-agent")
    .id("testing-agent")
    .version("1")
    .instructions("你是测试专家。根据输入制定并执行验证，输出风险和结论。")
    .chatModel(chatModel)
    .tool(testTool)
    .build();

Agent releaseAgent = Agent.builder("release-agent")
    .id("release-agent")
    .version("1")
    .instructions("你是发布专家。发布必须调用 deploy_service 工具。")
    .chatModel(chatModel)
    .tool(deployTool)
    .toolApprovalPolicy((run, call, tool) ->
        "deploy_service".equals(call.getName())
            ? ToolApprovalDecision.requireApproval()
                .code("DEPLOYMENT_REVIEW")
                .message("发布任务需要人工批准")
                .build()
            : ToolApprovalDecision.ALLOW)
    .build();
```

任务中的 `assignedAgentId` 必须与 Registry 中的稳定 Agent ID 一致。

## 定义根 Agent 与规划器

如果希望模型动态拆分任务，可以配置 `ModelAgentTaskPlanner`：

```java
Agent rootAgent = Agent.builder("delivery-agent")
    .id("delivery-agent")
    .version("1")
    .instructions(
        "你负责交付目标。子任务完成后，根据所有 Child Agent result 汇总最终结果。")
    .chatModel(chatModel)
    .taskPlanner(new ModelAgentTaskPlanner(8))
    .build();
```

模型规划器适合开放目标。业务流程固定时，更推荐显式规划器：

```java
AgentTaskPlanner planner = (agent, context) ->
    new AgentTaskPlan(context.getGoal(), Arrays.asList(
        AgentTask.builder("分析变更范围")
            .id("analyze")
            .description("识别受影响模块、风险和回滚点")
            .position(0)
            .build(),
        AgentTask.builder("执行验证")
            .id("verify")
            .parentTaskId("analyze")
            .description("运行测试并检查关键业务路径")
            .assignedAgentId("testing-agent")
            .position(1)
            .build(),
        AgentTask.builder("发布生产版本")
            .id("deploy")
            .parentTaskId("verify")
            .description("验证通过后发布，发布工具需要审批")
            .assignedAgentId("release-agent")
            .position(2)
            .build()
    ));

Agent rootAgent = Agent.builder("delivery-agent")
    .id("delivery-agent")
    .version("1")
    .instructions("你负责总体交付，并根据所有子任务结果生成最终报告。")
    .chatModel(chatModel)
    .taskPlanner(planner)
    .build();
```

`parentTaskId` 表达任务层级，执行顺序由 `position` 决定。需要跨任务流程依赖时，可以在外部工作流模块中协调计划的推进。

## 注册 Agent 并创建执行器

```java
InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
InMemoryAgentTaskStore taskStore = new InMemoryAgentTaskStore();

registry.register(rootAgent);
registry.register(testingAgent);
registry.register(releaseAgent);

AgentRunner runner = new AgentRunner(runStore, registry);
AgentPlanExecutor executor = new AgentPlanExecutor(runner, taskStore);
```

生产环境中 RunStore 与 TaskStore 都必须持久化。跨两个 Store 更新时，应通过事务、Outbox 或可靠补偿保证最终一致。

## 先创建计划

```java
AgentTaskPlanSnapshot plan = executor.start(
    rootAgent,
    "验证 inventory-service 2.4.0 并发布到生产环境"
);

System.out.println("planId = " + plan.getPlanId());
System.out.println("rootRunId = " + plan.getRootRunId());
```

此时已经创建根 AgentRun 和任务计划，但尚未开始第一个任务。适合在 UI 中先展示计划并让用户确认。

## 每次推进一个任务

```java
AgentPlanRun first = executor.runNext(plan.getPlanId());
printProgress(executor.getProgress(plan.getPlanId()));

AgentPlanRun second = executor.runNext(plan.getPlanId());
printProgress(executor.getProgress(plan.getPlanId()));
```

`runNext()` 最多选择一个新任务。任务内部的 AgentRun 仍会执行到完成或阻塞。

进度输出方法：

```java
private static void printProgress(AgentTaskProgress progress) {
    System.out.println("计划状态：" + progress.getStatus());
    System.out.println("完成进度："
        + progress.getCompletedTaskCount()
        + "/"
        + progress.getTotalTaskCount());

    for (AgentTask task : progress.getTasks()) {
        System.out.println("[" + task.getStatus() + "] "
            + task.getPosition() + " " + task.getTitle());

        if (task.getResult() != null) {
            System.out.println("  result: " + task.getResult());
        }
        if (task.getError() != null) {
            System.out.println("  error: " + task.getError());
        }
    }

    if (progress.getActiveRunStatus() != null) {
        System.out.println("活动 Run：" + progress.getActiveRunStatus());
    }
    if (progress.getActiveSuspension() != null) {
        System.out.println("等待原因："
            + progress.getActiveSuspension().getMessage());
    }
}
```

## 自动运行到阻塞

```java
AgentPlanRun result = executor.runUntilBlocked(plan.getPlanId());
```

Executor 会顺序完成分析和测试任务。发布 Agent 产生 `deploy_service` ToolCall 后，计划进入 `WAITING`，当前发布任务进入 `WAITING`，activeRun 状态为 `WAITING_FOR_APPROVAL`。

```java
AgentTaskProgress waiting = executor.getProgress(plan.getPlanId());

if (waiting.getActiveRunStatus() == AgentRunStatus.WAITING_FOR_APPROVAL) {
    String callId = waiting.getActiveSuspension().getCorrelationId();
    System.out.println("待审批 ToolCall：" + callId);
}
```

Task 不复制审批细节。审批原因、correlationId 和恢复阶段由当前 activeRun 的 Suspension 提供。

## 恢复审批并继续整个计划

```java
String callId = waiting.getActiveSuspension().getCorrelationId();

AgentPlanRun completed = executor.resume(
    plan.getPlanId(),
    AgentResumeCommand.approveTool(callId)
);

System.out.println(completed.getPlan().getStatus());
System.out.println(completed.getRootRun().getFinalOutput());
```

`AgentPlanExecutor.resume()` 会：

1. 恢复当前发布子 Run；
2. 执行获批工具并完成发布任务；
3. 把子 Run 结果写回根 Run；
4. 更新任务状态为 `COMPLETED`；
5. 让根 Agent 汇总全部任务结果；
6. 将计划置为 `COMPLETED`。

## 一次调用创建并执行

不需要先确认计划时，可以直接：

```java
AgentPlanRun result = executor.run(
    rootAgent,
    "验证 inventory-service 2.4.0 并发布到生产环境"
);
```

该方法等价于 `start()` 后调用 `runUntilBlocked()`，仍会在审批、用户输入或重试时间处返回。

## 与 Worker 配合

当某个任务因为临时错误进入 `RETRY_SCHEDULED`，计划状态为 `WAITING`。到期后 Worker 可以恢复 activeRun：

```java
AgentWorker worker = new AgentWorker("plan-worker-01", runner, 30_000);
worker.pollAndRun(10);

AgentPlanRun continued = executor.runUntilBlocked(plan.getPlanId());
```

第一步由 Worker 完成具体 Run 的重试，第二步由 Executor 读取结果、更新任务计划并继续下一任务。

在完整调度系统中，可以在 Run 完成事件消费者中触发计划继续推进，不需要业务线程轮询。

## ModelAgentTaskPlanner 的注意事项

模型规划输出会被解析为结构化任务列表，但业务仍需设置边界：

- 使用 `maxTasks` 限制任务数量；
- 指令中列出允许分配的 Agent ID；
- Registry 只注册允许调用的专业 Agent；
- 校验任务标题、父任务和 assignedAgentId；
- 高风险工具继续走 ToolApprovalPolicy；
- 给根 Run 和每个子 Run设置时间、Token、工具调用预算；
- 不把模型生成的任务描述直接当作数据库语句或系统命令。

## API 返回示例

可以把 `AgentTaskProgress` 转换为前端状态：

```json
{
  "planId": "plan-123",
  "status": "WAITING",
  "completed": 2,
  "total": 3,
  "activeRunStatus": "WAITING_FOR_APPROVAL",
  "tasks": [
    {"id": "analyze", "title": "分析变更范围", "status": "COMPLETED"},
    {"id": "verify", "title": "执行验证", "status": "COMPLETED"},
    {"id": "deploy", "title": "发布生产版本", "status": "WAITING"}
  ]
}
```

UI 应以 TaskPlan 展示整体进度，以 activeRun 展示当前等待原因和审批动作。

## 建议测试场景

- 任务严格按 position 顺序执行；
- assignedAgentId 正确解析到专业 Agent；
- 每完成一个任务都能查询到结果；
- 发布审批前工具没有执行；
- 批准后计划继续并由根 Agent 汇总；
- 拒绝后任务和计划状态符合业务预期；
- 子 Run 重试到期后 Worker 能恢复；
- TaskStore version 冲突不会覆盖其他执行器的进度；
- 根 Run 或子 Run 达到预算后计划可靠终止；
- 服务重启后能通过 planId 和 rootRunId 恢复查询。

## 延伸阅读

- [任务规划与进度](../task-planning.md)
- [子 Agent](../subagents.md)
- [长任务与故障恢复](../scenarios/long-running-task.md)
- [生产实践](../production.md)

</div>
