---
title: 子 Agent
description: 使用父子 AgentRun 委派任务、传播上下文、恢复父任务并控制任务树。
---

# 子 Agent

## 概述

子 Agent 不是父 Agent 内的一次普通方法调用，而是拥有独立 ID、状态、预算、Checkpoint 和事件的 `AgentRun`。父子通过 `parentRunId`、`rootRunId` 与暂停关联建立任务树。独立 Run 使子任务可以审批、重试、后台领取，也能单独查询和审计。

## 两种使用方式

最常见的方式是启用[任务规划](./task-planning)，由模型创建任务后 Runner 自动启动子 Run。平台也可以调用 Runner 的子任务能力，在自定义执行模式中显式委派。

无论入口如何，子 Agent 必须由 `AgentLoader` 加载，且目标 ID 应受业务授权或 `AgentPlanningPolicy` 白名单约束。

## 父子状态

启动子 Run 后：

- 子 Run 的 `parentRunId` 等于父 Run ID。
- 子 Run 的 `rootRunId` 继承根 Run ID。
- 子 Run 的 planning depth 加一。
- 父 Run 保存 `AgentSuspension.child(childRunId)` 并进入 `WAITING_FOR_CHILD`。
- 子 Run 终止后，Runner 更新父计划并恢复父 Run。

父 Run 不应轮询子对象内存状态；Store 是跨线程和跨进程的事实来源。

## 执行方式

同步 `runner.run(...)` 可以在当前调用链推进子 Run，适合短任务。长任务通常先保存子 Run，由 `AgentWorker` 领取。Worker 每轮会修复“子 Run 已终止但父 Run 尚未唤醒”的情况，以处理进程在两次写入之间退出的问题。

## 上下文传播

同一进程创建子 Run 时会继承父 Run 的 `AgentInvocationContext`；从 Checkpoint 恢复后，Worker 仍需通过 Provider 重建。子 Run 接收的是总体目标和当前任务的受控描述，不会自动复制父 Run 的完整消息历史。

这可以减少上下文污染，也意味着子任务所需事实应明确写入任务描述或通过工具查询。

## 结果回传

子 Run 完成后，最终输出写入父计划任务，并作为消息供父模型汇总。`taskResultMaxLength` 可限制复制长度；超出时父级只得到截断结果，完整内容仍保留在子 Run。

子 Run 失败时，父计划根据 `maxReplans` 及任务修改策略决定进入重规划还是停止。业务副作用的幂等仍由每个子 Run 的工具实现负责。

## 查询任务树

    
## 控制风险

- 设置 `maxDepth` 和 `maxTasks`，避免递归委派失控。
- 只允许委派到显式 Agent ID。
- 为子 Agent 设置与能力匹配的工具权限和预算。
- 不要把父 Agent 的高权限工具默认复制到所有子 Agent。
- 对父子结果长度和 Artifact 生命周期设置限制。

## 自定义委派

需要领域专用监督者模式时，实现 `AgentExecutionMode`，用 Mode State 保存委派阶段，并通过 Runner 的受控能力创建/等待子 Run。不要只在内存 Future 中维护父子关系，否则进程重启后无法恢复。
