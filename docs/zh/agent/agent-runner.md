---
title: AgentRunner 与执行循环
description: 使用 Runner 创建、推进、暂停和恢复 AgentRun，并理解默认 ToolCall 循环的稳定边界。
---

# AgentRunner 与执行循环

## 概述

`AgentRunner` 是 Agent 模块的执行入口。它协调模型、Tool、审批、Checkpoint、规划、预算和事件，但不把任务状态保存在自身字段中。只要依赖的 Store 和 Loader 相同，新的 Runner 实例就能恢复原任务。

## 快速开发

```java
AgentRunner runner = AgentRunner.builder()
    .agentLoader(new InMemoryAgentLoader(agent))
    .build();

AgentRun completedOrBlocked = runner.run(agent, "查询订单并给出处理建议");
```

异步任务使用：

```java
AgentRun queued = runner.start(agent, "分析大型代码仓库");
```

`run` 创建并同步推进；`start` 只创建 `READY` Run、保存初始 Checkpoint，然后立即返回。

## Runner 依赖

```java
AgentRunner runner = AgentRunner.builder()
    .runStore(runStore)
    .agentLoader(agentLoader)
    .eventStore(eventStore)
    .commandStore(commandStore)
    .artifactStore(artifactStore)
    .build();
```

未配置项使用进程内实现。分布式环境至少需要共享 RunStore、CommandStore 和可跨进程工作的 AgentLoader；需要审计和大型结果时再共享 EventStore 与 ArtifactStore。

## `runUntilBlocked` 的边界

```java
AgentRun current = runner.runUntilBlocked(runId);
```

方法会持续调用 `step`，直到：

- 模型给出最终答案；
- 发生不可恢复失败或取消；
- 达到迭代、步骤或预算上限；
- 等待用户输入、工具审批、子 Agent 或重试时间。

“Blocked”不是线程阻塞。Runner 已经保存状态并返回，调用线程应结束；外部事件到达后再恢复。

## 默认执行循环

一次模型阶段按以下顺序运行：

1. 检查取消、总时长、Token 和最大迭代；
2. 由 ContextManager 压缩历史，并按 ContextPolicy构造模型可见窗口；
3. 经过 step 和 model-call Middleware；
4. 调用 ChatModel，同步或流式接收结果；
5. 保存 AiMessage、Token 统计和 pending ToolCalls；
6. 没有 ToolCall 时完成，有 ToolCall 时切换到 TOOLS。

工具阶段逐个处理已保存调用：

1. 从当前 Agent 版本按名称找到 Tool；
2. 执行审批策略；
3. 必要时保存等待状态；
4. 经过 Agent Middleware、ToolInterceptor 后执行函数；
5. 外置过大的结果，写入 ToolMessage；
6. 全部完成后回到 MODEL。

## 为什么先保存 pending ToolCall

生产发布场景中，模型可能生成服务名和版本号。Runner 在审批前先保存这组参数。审批通过后直接恢复 TOOLS 阶段，不重新询问模型，避免前后两次决策不同。

同理，工具临时失败后的自动重试仍使用原 ToolCall，默认幂等键 `runId:toolCallId` 也保持不变。

## 单步推进

```java
AgentRun run = runner.start(agent, new UserMessage("查询天气"));
AgentStepResult result = runner.step(run);

switch (result.getType()) {
    case TOOLS_EXECUTED:
        // 已写入工具结果，可继续 step
        break;
    case BLOCKED:
        // 等待外部事件
        break;
    case COMPLETED:
        // 读取 run.getFinalOutput()
        break;
    default:
        break;
}
```

单步 API 适合调试、自定义调度器和测试。普通业务调用优先使用 `run` 或 Worker，避免遗漏后续步骤。

## 恢复与取消

```java
AgentRun restored = runner.restore(runId, invocationContext);
AgentRun latest = runner.runUntilBlocked(restored);

AgentRun cancelled = runner.requestCancellation(runId);
```

取消是持久化、单调且协作式的。框架不会强制终止正在执行的 HTTP 请求或 Tool 方法，而是在下一个安全边界停止并保存 `CANCELLED`。

## 同步规划与 Worker 规划

同步 `runUntilBlocked` 遇到规划子 Run 时会在当前线程继续执行子任务，再恢复父任务。Worker 持租约运行时只推进自己领取的 Run；新子 Run 保存到共享 Store，等待独立领取，避免一个租约隐式覆盖整棵任务树。

## 并发规则

不要让两个线程直接推进同一个 `AgentRun` 对象。单机可由调用方保证串行；分布式环境必须通过 Store 乐观锁和 Lease 领取。Runner 是可复用的，但 ChatModel、Tool 和自定义 Memory 的线程安全需要由其实现保证。

