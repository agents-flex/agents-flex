---
title: 架构设计
description: 从定义平面、执行平面、持久化平面和观察平面理解 Agent 运行时架构。
---

# 架构设计

## 概述

agents-flex-agent 采用“不可变定义 + 可持久化运行状态 + 无状态推进器”的架构。模型负责动态决策，Runner 负责确定性的控制流，Store 负责跨线程与跨进程一致性。该分层避免把业务任务绑定到某个 HTTP 请求或某个 JVM 对象。

## 组件分层

```text
应用入口 / 审批系统 / 调度系统 / UI
                |
        AgentRunner / AgentWorker
        |       |       |       |
   AgentLoader  Mode  Middleware Listener
        |       |       |
     Agent   AgentRun  ChatModel + Tool
                |
  RunStore / CommandStore / EventStore / ArtifactStore
```

### 定义平面

`Agent`、策略、Tool、Middleware 和 ExecutionMode 定义一个可发布版本。`AgentLoader` 把配置数据与运行时对象装配起来，并保留历史版本的恢复能力。

### 执行平面

`AgentRunner` 按 status 与 phase 推进 `AgentRun`。默认模式实现模型原生 Tool Calling；规划通过内置工具创建子 Run；Suspension 把外部等待转换为持久状态。

### 调度平面

`AgentWorker` 领取 READY 和到期任务，Command Inbox 接收外部恢复事件，Lease 与 fencing token 控制多实例所有权。调度系统可以用轮询或 Wakeup Listener 降低延迟。

### 持久化平面

Run Snapshot 是当前状态，Run Event 是变化历史，Command 是外部输入，Artifact 是大内容。它们有独立接口和一致性契约，可落在同一数据库，也可通过 outbox 与幂等协调异构存储。

### 观察平面

Listener 提供粗粒度回调，Runtime Event 服务流式 UI，Run Event 服务审计与断点消费。`rootRunId` 关联父子任务树，Invocation Context 提供请求和租户关联。

## 默认状态机

```text
READY -> RUNNING/MODEL
  MODEL -- final message --> COMPLETED
  MODEL -- ToolCall ------> RUNNING/TOOLS
  TOOLS -- executed ------> RUNNING/MODEL
  TOOLS -- approval ------> WAITING_FOR_APPROVAL
  any   -- retryable -----> RETRY_SCHEDULED
  parent -- child --------> WAITING_FOR_CHILD
  any   -- limit/error ---> terminal status
```

恢复命令把阻塞 Run 转回 Suspension 记录的 phase。终态不可重新打开。

## 一致性边界

框架能保证 Snapshot 内状态一致和 Store 写入并发控制，但无法把任意外部工具副作用纳入同一事务。因此架构采用稳定 ToolCall ID + 业务幂等键。审批前保存调用、工具后保存结果，把不可避免的故障窗口缩小并变得可恢复。

## 扩展原则

- 业务配置通过 Loader 接入，不侵入 Snapshot。
- 执行语义通过 ExecutionMode 扩展，不在 Listener 中改状态。
- 横切控制通过 Middleware，低层通用工具逻辑通过 ToolInterceptor。
- 瞬时依赖通过 Invocation Context，持久业务标识通过 metadata。
- 长内容进入 Artifact Store，不让 Prompt 和 Snapshot 无界增长。

## 部署形态

### 单实例同步

HTTP 请求直接调用 `runner.run`，内存 Store 可用于开发。进程退出会丢状态。

### 单实例持久化

替换 Store，短任务同步执行，审批后恢复。可抗重启，但同一进程仍承担请求与执行。

### 多实例 Worker

API 服务调用 `start`/`submitCommand`，Worker 集群通过共享 Store 执行。所有实例共享 Loader 版本视图，Lease 负责接管，适合生产长任务。

## 安全边界

模型不能直接绕过工具授权；Runner 只执行当前 Agent 中按名称解析到的 Tool，并在执行前应用审批策略和 Middleware。生产平台还应在配置发布、Invocation Context 重建、Artifact 读取和恢复 API 上实施租户隔离。
