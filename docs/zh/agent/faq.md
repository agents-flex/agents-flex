---
title: 常见问题
description: 解答 Agent 定义、执行、恢复、工具、规划、Worker 和持久化中的常见问题。
---

# 常见问题

## 概述

本页汇总使用 agents-flex-agent 时最容易混淆的职责边界和生产问题。遇到异常时，先读取 Run Snapshot 的 status、phase、Suspension、error、version 和 nextRunAt，再结合持久化事件定位原因。

## Agent 和 ChatModel 有什么区别？

`ChatModel` 完成一次模型请求；`Agent` 组合模型、工具和策略；`AgentRunner` 让多次模型/工具调用形成可暂停和恢复的任务。只有单次生成时无需引入 Agent。

## `run` 和 `start` 应该选哪个？

`run` 在当前线程执行到终止或阻塞，适合短同步任务。`start` 只保存 READY Run，适合由 Worker 异步执行。不要把长任务放在 HTTP 请求线程里一直等待。

## 为什么 `run` 返回后不是 COMPLETED？

它可能在等待审批、用户输入、子 Run 或重试时间，也可能达到预算/迭代上限。检查 `status` 和 `suspension`，阻塞状态需要外部事件，终态不能再次推进。

## 下一轮对话要复用原 AgentRun 吗？

不要。每一轮创建新的 Run；需要共享历史时使用 `AgentConversation` 或传入业务加载的 conversation history。只有恢复阻塞任务时才继续原 Run。

## 为什么恢复时找不到 Agent？

Snapshot 只保存 Agent ID 与版本。确保 Runner 配置的 Loader 可以精确加载历史版本，并绑定完整模型、工具和策略。

## 修改 Agent 后历史 Run 会用新配置吗？

历史 Run 绑定创建时的版本。正确做法是发布新版本，同时保留旧版本直到相关 Run 终止。不要让 `load(id, version)` 自动返回 active 版本。

## 工具为什么执行了两次？

常见原因是 Middleware 多次调用 `proceed`、外部副作用成功后 Snapshot 失败并重试，或多个执行者绕过 Lease 推进同一 Run。修复责任链和 Store 后，写工具仍必须用 runId + toolCallId 做业务幂等。

## 审批后为什么不能创建新 Run？

审批对应原 Run 中已持久化的 ToolCall。创建新 Run 会丢失 correlationId、原参数和审计链。应提交 `approveTool(callId)` 或 `rejectTool(callId, reason)` 恢复原 Run。

## Token 预算为什么没有生效？

模型实现必须返回 usage 才能准确累计 Token。始终同时配置最大迭代、最大 step、工具次数和外部请求超时，不能只依赖 Token。

## AgentEvent 能用于可靠审计吗？

不能直接保证。它只在当前 JVM 内同步发布，重启后 sequence 也不连续。可靠审计应由业务 `AgentEventListener` 转发到事务 Outbox、消息平台或审计数据库，并使用业务侧游标和重试机制。

## 内存 Store 能用于多实例吗？

不能。每个 JVM 有独立数据，无法协调版本、Lease 或命令。生产多实例必须使用共享持久化实现。

## Worker Lease 能防止所有重复副作用吗？

不能。Lease 防止旧执行者提交 Snapshot，但旧执行者可能已向外部系统发出请求。业务工具的幂等约束仍然必需。

## 为什么 Invocation Context 恢复后为空？

它被设计为瞬时对象，不进入 Snapshot。使用 `restore(runId, context)` 或为 Worker 配置 `AgentInvocationContextProvider`。

## metadata 可以保存任意对象吗？

只保存可序列化、稳定、非敏感的小对象。自定义类型要加入 Serializer 精确白名单。Bean、连接、线程、本地回调和密钥应放在 Invocation Context 或外部服务。

## 任务规划会并行执行吗？

当前内置计划顺序执行，一次一个活动任务。需要并行 DAG 时应在外部编排层实现，并把独立任务提交为不同 Run。

## 如何取消运行？

调用 `runner.requestCancellation(runId)`。取消是协作式的，在安全边界生效，不保证立刻中断正在执行的 HTTP 或工具函数。

## Store 版本冲突如何处理？

说明已有更新提交。停止使用旧对象，重新加载最新快照；不要无条件覆盖。命令和工具应保持幂等，使重新处理不会产生额外副作用。

## 如何排查一直处于 RUNNING 的 Run？

检查 Lease owner/until、Worker 心跳、最近 Snapshot 事件、模型/工具超时和 runnable 队列。Lease 过期后应能被其他 Worker 领取；若不能，重点验证 `claimRunnable` 的状态判断与数据库时间。
