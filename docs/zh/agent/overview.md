---
title: Agent 智能体概述
description: 从业务问题出发理解 Agent、AgentRun、AgentRunner、Tool、Conversation 和 Store 的职责。
---

# Agent 智能体概述

## 概述

普通对话通常只有一次模型请求：应用把消息交给模型，再把回复返回给用户。Agent 面对的是另一类问题：模型需要根据当前信息决定是否调用工具，读取工具结果后继续判断，必要时等待审批、拆分任务、重试失败步骤，最终才形成答案。

Agents-Flex Agent 模块提供一套可嵌入 Java 应用的执行运行时。它负责把模型、工具和业务控制规则组织成一次可查询、可暂停、可恢复的运行，同时不规定业务平台必须怎样保存 Agent 配置、怎样设计审批页面或怎样生成统计报表。

一个典型场景是生产发布助手：用户要求发布服务，模型生成部署工具调用；运行时在执行前保存调用参数并进入审批等待；管理员批准后，Worker 从原来的工具步骤继续执行，而不是重新让模型生成一次部署参数。

## 快速开发

最小调用只需要三个对象：

```java
Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .instructions("回答订单问题；需要实时状态时调用订单查询工具。")
    .tool(queryOrderTool)
    .build();

AgentRunner runner = new AgentRunner();
AgentRun run = runner.run(agent, "查询订单 A1024");

System.out.println(run.getStatus());
System.out.println(run.getFinalOutput());
```

模型直接回答时，这次 `AgentRun` 很快结束；模型调用工具时，`AgentRunner` 会自动执行工具、写入 `ToolMessage`，再继续调用模型。调用方始终读取同一个 `AgentRun` 的状态和结果。

## 五个核心对象

| 对象 | 保存什么 | 不负责什么 |
| --- | --- | --- |
| `Agent` | 模型、指令、工具、规划、审批和执行策略 | 不保存某次任务状态 |
| `AgentRun` | 一次输入产生的消息、状态、步骤、预算和结果 | 不作为跨进程配置来源 |
| `AgentRunner` | 创建、推进、暂停、恢复和保存 Run | 自身不长期持有任务状态 |
| `AgentConversation` | 同一会话跨多次 Run 共享的 `ChatMemory` | 不把整段会话视为一个永不结束的 Run |
| `AgentRunStore` | 可恢复的 `AgentRunSnapshot`、版本和 Lease | 不保存模型客户端和 Tool 实例 |

这组边界解决了两个常见混淆。第一，Agent 是能力定义，不是执行结果；同一个 Agent 可以同时处理许多 Run。第二，每条正常用户消息都会产生一个新的 Run，即使消息只是“你好”；Conversation 负责让下一轮看到之前的消息，而不是复用上一轮的状态、预算或审批记录。

## 一次运行如何推进

默认执行模式围绕模型原生 ToolCall 工作：

```text
用户消息
   |
   v
创建 READY Run，并保存初始 Checkpoint
   |
   v
调用模型 ---- 无 ToolCall ----> COMPLETED
   |
   | ToolCall
   v
保存待执行调用 -> 审批/执行 Tool -> 写入 ToolMessage
   |                                  |
   +----------------------------------+
                 再次调用模型
```

每个稳定边界都会保存快照。这样进程即使在“模型已经决定调用工具、工具尚未执行”时退出，恢复后也能继续同一个 ToolCall。

## 同步任务与长任务

短任务可以直接使用 `runner.run(...)`，当前线程会推进到终止或等待状态。长任务使用 `runner.start(...)` 只创建并保存 Run，再交给 `AgentWorker`：

```java
AgentRun queued = runner.start(agent, "分析代码仓库并生成报告");
return queued.getId();
```

Worker 通过共享 Store 和 Lease 领取任务。模型限流、审批等待、子 Agent 和重试调度都不会要求 HTTP 请求一直占用线程。

## 模块能力

Agent 运行时覆盖以下能力：

- 文本、图片、音频、视频和文件形式的用户消息；
- 持续对话以及每轮独立的运行记录；
- 模型原生 ToolCall、工具 metadata、审批和幂等身份；
- Checkpoint、类型化恢复命令和持久化 Command Inbox；
- 自动重试、时间/Token/工具次数预算和协作式取消；
- 模型自主任务规划、可查询任务进度和子 Agent；
- Worker Lease、崩溃接管和父子 Run 恢复；
- Middleware、调用上下文、上下文压缩和大型结果外置；
- 实时细粒度事件与可持久化生命周期事件；
- JDBC、Redis 生产 Store，以及业务自定义 Store 和 AgentLoader。

## 框架与业务平台的边界

框架提供执行契约，平台可以在其上自由建设配置管理、版本发布、权限、模式说明、任务类型、效果报告和模拟演示。例如“保存某种运行模式的定义和适用场景”属于平台配置；平台只需在运行时把这些数据组装为 `Agent`、`AgentExecutionPolicy` 或自定义 `AgentExecutionMode`。

因此，框架不会要求所有平台采用同一张 Agent 表，也不会把业务统计字段固化进核心对象。平台可以把租户、账号、模块、任务类型和配置版本写入 Run metadata 或事件增强器，再通过自己的查询服务生成日志与报表。

## 阅读顺序

第一次使用时，先完成[快速开发](./getting-started.md)，再阅读 [Agent 定义](./agent.md)、[会话与运行](./agent-run.md)和 [Runner 与执行循环](./agent-runner.md)。需要接入生产任务时，再继续阅读 Checkpoint、Worker、Store、事件和生产实践相关页面。
