---
title: 子 Agent
description: 使用父子 AgentTurn 委派任务、维护父子关系、恢复父任务并控制任务树。
---

# 子 Agent

## 概述

子 Agent 不是父 Agent 内的一次普通方法调用，而是拥有独立 ID、状态、预算、Snapshot 和事件的
`AgentTurn`。根 Turn 的输入通常来自用户，子 Turn 的输入来自父 Agent；因此二者都是“一次 Agent
输入到最终结果”的轮次。父子通过 `parentTurnId`、`rootTurnId` 与暂停关联建立 Turn 树。独立 Turn
使子任务可以审批、重试、后台领取，也能单独查询和审计。

## 两种使用方式

最常见的方式是启用[任务规划](./task-planning)，由模型创建任务后 Runner 自动启动子 Turn。平台也可以调用 Runner 的子任务能力显式委派。

无论入口如何，子 Agent 必须由 `AgentLoader` 加载，且目标 ID 应受业务授权或 `AgentPlanningPolicy` 白名单约束。

## 父子状态

启动子 Turn 后：

- 子 Turn 的 `parentTurnId` 等于父 Turn ID。
- 子 Turn 的 `rootTurnId` 继承根 Turn ID。
- 子 Turn 的 planning depth 加一。
- 父 Turn 保存 `AgentSuspension.child(childTurnId)` 并进入 `WAITING_FOR_CHILD`。
- 子 Turn 终止后，Runner 更新父计划并恢复父 Turn。

父 Turn 不应轮询子对象内存状态；Store 是跨线程和跨进程的事实来源。

## 执行方式

同步 `runner.run(...)` 可以在当前调用链推进子 Turn，适合短任务。长任务通常先保存子 Turn，由 `AgentWorker` 领取。Worker 每轮会修复“子 Turn 已终止但父 Turn 尚未唤醒”的情况，以处理进程在两次写入之间退出的问题。

## 任务输入

子 Turn 接收总体目标和当前任务的受控描述，不会自动复制父 Turn 的完整消息历史。子任务所需的业务事实应明确写入任务描述，或由工具按业务标识查询，而不能依赖父 Turn 的进程内对象。

子 Turn 会继承父 Turn 的 streaming 调用方式；从 Snapshot 恢复或由 Worker 执行时也会保持该设置。

## 结果回传

子 Turn 完成后，最终输出写入父计划任务，并作为消息供父模型汇总。`taskResultMaxLength` 可限制复制长度；超出时父级只得到截断结果，完整内容仍保留在子 Turn。

子 Turn 失败时，父计划根据 `maxReplans` 及任务修改策略决定进入重规划还是停止。业务副作用的幂等仍由每个子 Turn 的工具实现负责。

## 查询任务树

`turn.getRootTurnId()` 用于关联整棵 Turn 树，`turn.getParentTurnId()` 用于定位直接父 Turn。
启用任务规划时，使用 `runner.getTaskProgress(rootTurnId)` 查询聚合后的不可变进度视图；跨进程查询
单个 Turn 时，以 `AgentTurnStore` 中的 Snapshot 为准。
## 控制风险

- 设置 `maxDepth` 和 `maxTasks`，避免递归委派失控。
- 只允许委派到显式 Agent ID。
- 为子 Agent 设置与能力匹配的工具权限和预算。
- 不要把父 Agent 的高权限工具默认复制到所有子 Agent。
- 限制父子结果长度，并为大结果设计分页、摘要或业务文件引用。

## 自定义委派

需要领域专用监督者流程时，在外部编排层组合普通子 Turn，并把委派阶段保存在业务存储中。不要只在内存 Future 中维护父子关系，否则进程重启后无法恢复。
