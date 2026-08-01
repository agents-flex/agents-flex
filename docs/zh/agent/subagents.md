---
title: 子 Agent 与父子 Run
description: 创建子 AgentRun、等待子任务、回传结果，并理解与对话模块 Subagent Tool 的区别。
---

# 子 Agent 与父子 Run

<div v-pre>

## 子 Agent 的运行模型

Agent 模块中的子 Agent 使用持久化父子 Run：

```text
父 AgentRun
  ├── parentRunId = null
  ├── rootRunId = 自身 ID
  └── WAITING_FOR_CHILD
          │
          ▼
      子 AgentRun
        ├── parentRunId = 父 ID
        └── rootRunId = 根 ID
```

父 Run 和子 Run 都拥有独立消息、Checkpoint、预算、审批、重试和事件。

## 注册 Agent

```java
InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
registry.register(parentAgent);
registry.register(researchAgent);

AgentRunner runner = new AgentRunner(
    new InMemoryAgentRunStore(),
    registry
);
```

生产环境通常在应用启动时注册全部稳定 Agent 定义。

## 创建子 Run

```java
AgentRun parent = runner.start(parentAgent, "比较三种数据库方案");

AgentRun child = runner.startChild(
    parent,
    "research-agent",
    "调研 PostgreSQL 在该场景下的优缺点"
);
```

`startChild` 原子完成：

- 父 Run 进入 `WAITING_FOR_CHILD`；
- 父 Suspension correlationId 指向 childRunId；
- 创建子 Run；
- 保存父子 Snapshot。

生产 Store 的 `saveParentAndChild` 必须使用同一事务，避免只创建子 Run或只暂停父 Run。

## 执行并恢复父 Run

```java
AgentRun completedChild = runner.runUntilBlocked(child);

AgentRun resumedParent = runner.resumeParentFromChild(completedChild);

AgentRun completedParent = runner.runUntilBlocked(resumedParent);
```

子结果会作为包含 childRunId、agentId、status、output 和 error 的消息写入父 Prompt。重复的 child completion 不会重复写入消息。

## 使用 Worker

`AgentWorker` 完成终止子 Run 后会自动调用 `resumeParentFromChild`：

```java
AgentWorker worker = new AgentWorker("child-worker", runner, 30_000);
worker.pollAndRun(10);
```

父 Run 恢复为 RUNNING 后，可由下一次 Worker 轮询继续执行。

## 子 Agent 与任务计划

手工 `startChild` 适合业务明确知道要启动哪个子任务。复杂目标自动拆分时，使用 `AgentPlanExecutor`：

```java
AgentTask task = AgentTask.builder("验证数据库方案")
    .assignedAgentId("database-agent")
    .position(1)
    .build();
```

计划执行器会自动创建子 Run、关联 childRunId、更新任务状态并最终汇总根 Run。

## 与对话模块 Subagent Tool 的区别

Agents-Flex 还有通过 Tool 启动后台子代理的对话模块能力。两者关注点不同：

| 方式 | 适合 |
| --- | --- |
| 对话模块 Subagent Tool | 模型主动调用工具启动后台代理，轻量并行任务 |
| Agent 父子 Run | 持久化状态、审批、重试、Lease、跨进程恢复 |
| Agent Task Plan | 可查看任务列表、顺序执行和最终汇总 |

需要数据库级任务可靠性时，优先使用父子 AgentRun 或 Task Plan。

## 上下文设计

子 Agent 不会自动继承父 Prompt 的全部消息。传给子 Run 的 input 应包含：

- 总体目标；
- 当前任务；
- 必要业务参数；
- 输出格式；
- 禁止执行的动作；
- 可用于幂等和审计的业务 ID。

避免把无关的完整对话复制给所有子 Agent，防止 Token 膨胀和权限泄露。

## 下一步

- [任务规划与进度](./task-planning.md)
- [自动任务规划 Demo](./demos/planned-agent.md)
- [生产实践](./production.md)

</div>
