---
title: Agent 整体架构
---

# Agent 整体架构

## 概述

Agents-Flex Agent 模块是一套可恢复的智能体运行时。它把“Agent 能做什么”“一次消息正在做什么”“如何推进任务”和“如何跨进程保存状态”分开，使同一套业务 Agent 既能同步回答，也能等待审批、后台重试、派发子 Agent 或在其他节点恢复。

这种设计的核心不是某种固定提示词格式，而是明确的状态边界：模型、工具和 Middleware 是运行时能力，`AgentRunSnapshot` 是可持久化状态，`AgentRunner` 负责在两者之间安全推进。

## 快速开发

开发一个 Agent 通常只需要四步：

```java
Agent agent = Agent.builder()
    .id("support-agent")
    .version("1")
    .instructions("帮助用户诊断问题，需要时调用工具。")
    .chatModel(chatModel)
    .tool(queryTicketTool)
    .build();

AgentRunner runner = AgentRunner.builder()
    .agentLoader(agentLoader)
    .runStore(runStore)
    .build();

AgentConversation conversation = new AgentConversation(agent, runner);
AgentRun run = conversation.send(new UserMessage("查询工单 T-1024"));
```

随着场景变复杂，只需要给 Runner 接入共享 Store、事件、Middleware 和 Worker，不需要改写 Agent 的业务工具。

## 架构图

![Agent Runtime 架构](/assets/images/agent-runtime-architecture.svg)

## 核心对象

| 对象 | 职责 | 生命周期 |
| --- | --- | --- |
| `Agent` | 模型、指令、工具和执行策略的不可变定义 | 可复用、可版本化 |
| `AgentConversation` | 维护同一会话的历史消息 | 跨多次用户消息 |
| `AgentRun` | 一条用户消息的一次可观察执行 | 从创建到终止或等待 |
| `AgentRunner` | 推进循环、保存状态、执行工具和恢复 | 通常为应用级单例 |
| `AgentWorker` | 从共享 Store 领取并后台推进 Run | Worker 进程级 |

每条消息都创建独立 Run，因此普通问候、一次工具调用和长任务使用相同入口；是否调用工具、规划任务或直接回答由模型与策略共同决定。Conversation 把前一次 Run 的对话历史带入下一次 Run，Run 则保留每次处理的独立状态、预算和审计轨迹。

## 控制面与执行面

业务平台的 API、配置管理、权限和报表属于控制面。它通过 `AgentLoader` 把一张或多张业务表组装成 Agent，通过 Runner 创建或恢复 Run，并把审批等外部决定写入 Command Inbox。

Runner 与 Worker 属于执行面。它们加载 Agent、调用模型和工具、检查预算、保存 Checkpoint，并发布实时与持久化事件。两者可以部署在同一 JVM，也可以通过 JDBC 或 Redis Store 分离部署。

## 可恢复状态与运行时对象

进入 Snapshot 的数据必须能在另一个进程中解释，包括消息、ToolCall、状态、计划、计数器、暂停原因和模式状态。以下对象不进入 Snapshot：

- `ChatModel`、`Tool` 和 Middleware 实例；
- 数据库连接、Spring Bean 和线程池；
- 当前 HTTP 请求、未序列化身份对象和短期凭据；
- 实时 listener。

恢复时，`AgentLoader.load(agentId, version)` 重新组装 Agent，`AgentInvocationContextProvider` 重新附加请求外的业务服务和身份上下文。这个边界使分布式恢复不依赖原 JVM 内存。

## 扩展链路

一次 step 依次经过 step Middleware、模型调用 Middleware、模型、工具审批、tool Middleware、ToolInterceptor 与 Tool。`AgentRuntimeEventStream` 提供低延迟进程内事件，`AgentRunEventStore` 保存跨进程可查询的审计事件。

上下文过长时，`AgentContextManager` 可压缩历史；大型工具结果可写入 `AgentArtifactStore`，模型只接收摘要与引用。规划能力通过内置规划工具创建任务列表和子 Run，仍由同一 Runner/Worker 体系执行。

## 包结构

`com.agentsflex.agent` 根包放置 Runner、Run、Worker、策略和生命周期对象；子包按能力边界组织：

| 包 | 内容 |
| --- | --- |
| `command` | 持久化恢复命令和唤醒 |
| `context` | 上下文压缩、Artifact 和大型结果卸载 |
| `event` | 实时事件、持久化事件和增强器 |
| `loader` | Agent 定义加载 |
| `middleware` | step、模型和工具调用拦截链 |
| `mode` | 执行模式扩展 |
| `store` | Snapshot Store 与序列化契约 |
| `task` | 规划策略、任务与进度 |
| `tool` | 审批、调用上下文和错误策略 |

包名表达稳定职责，而不是实施阶段或具体产品页面。JDBC 与 Redis 实现在独立 `agents-flex-agent-store-*` Maven 模块中，核心运行时不绑定某个基础设施客户端。
