---
title: Worker、Lease 与分布式执行
---

# Worker、Lease 与分布式执行

## 概述

短对话可以在请求线程中同步运行；报告生成、批量查询、等待审批或需要重试的任务更适合由 `AgentWorker` 后台推进。API 服务只负责创建 Run、提交命令和查询状态，Worker 从共享 `AgentRunStore` 领取到期任务。

Lease 是有时限的执行权。它允许 Worker 崩溃后由其他节点接管，同时防止两个进程正常情况下并发推进同一个 Run。

## 快速开发

```java
AgentRunner runner = AgentRunner.builder()
    .runStore(sharedRunStore)
    .commandStore(sharedCommandStore)
    .eventStore(sharedEventStore)
    .agentLoader(agentLoader)
    .build();

AgentWorker worker = new AgentWorker(
    "worker-shanghai-01", runner, 30_000);

worker.startPolling(1_000, 10);
```

应用已有 Quartz、XXL-JOB 或 Kubernetes 调度时，也可以由外部调度器定期调用：

```java
List<AgentRun> results = worker.pollAndRun(10);
```

关闭应用时调用 `worker.close()`。关闭只停止领取和心跳线程，不保证强制中断已进入第三方 SDK 的调用。

## API 与 Worker 分离

典型长任务流程如下：

1. API 节点通过 `runner.submit(...)` 或相应启动入口保存可运行的 Run；
2. Worker 原子领取 Run，Store 写入 `leaseOwner`、唯一 `leaseId` 和 `leaseUntil`；
3. Worker 恢复 Agent 与调用上下文，并推进到完成、等待或重试；
4. 每个稳定边界保存 Snapshot 和事件；
5. Worker 释放 Lease，或者崩溃后等待 Lease 到期。

读取任务状态不需要 Lease。取消、审批和用户补充输入通过持久化命令进入系统，也不应直接修改正在执行进程中的 Java 对象。

## Lease 与 fencing

`workerId` 标识进程，`leaseId` 是每次领取生成的唯一令牌。保存和释放操作同时校验两者，可阻止旧进程在暂停后恢复运行并覆盖新持有者的结果。这种 fencing 对滚动发布、长时间 GC 和网络分区尤其重要。

Worker 在执行期间按租约时长约三分之一的周期自动续租。如果续租失败，本地 Run 会失去有效租约，后续 Checkpoint 拒绝继续写入。

JDBC 与 Redis Store 使用存储端时间判断 Lease 和 `nextRunAt`，避免多台应用服务器时钟漂移导致任务被提前接管。

## 调用上下文恢复

Snapshot 不保存 Service、认证对象等运行时依赖。Worker 可通过 `AgentInvocationContextProvider` 依据 Snapshot 重建：

```java
AgentInvocationContextProvider provider = snapshot ->
    AgentInvocationContext.builder()
        .tenantId((String) snapshot.getMetadata().get("tenantId"))
        .attribute("ticketService", ticketService)
        .build();

AgentWorker worker = new AgentWorker(
    "worker-01", runner, 30_000, provider);
```

持久化的 `metadata` 只放重建所需的稳定标识，不要把访问令牌、连接和 Service 对象写入其中。

## Command Inbox 与事件唤醒

审批结果或补充输入可能到达任意 API 节点。`AgentRunCommandStore` 先持久化命令，Worker 再以独立 Lease 领取并调用恢复逻辑。`AgentWakeupListener` 可用于通知调度系统立即触发轮询；即使通知丢失，定时轮询仍能兜底。

命令 Store 与 Run Store 应使用同一可靠性等级。仅使用进程内命令队列会让分布式部署在节点切换时丢失审批决定。

## 子 Agent 调度

规划工具创建子 Run 时，父 Run 进入 `WAITING_FOR_CHILD`。子 Run 像普通任务一样被任意 Worker 领取。子 Run 终止后，Worker 唤醒父 Run并合并结果；`recoverCompletedChildren(...)` 还会扫描“子任务已结束但父任务尚未唤醒”的情况，修复进程在两次写入之间退出造成的遗漏。

父子创建由 `saveParentAndChild(...)` 原子提交，生产 Store 不应把它拆成两个缺少事务保护的写操作。

## 并发与幂等

Lease 和 Snapshot 版本保护框架状态，但无法自动保证外部副作用只发生一次。工具仍应使用 `AgentToolInvocation.getId()` 作为幂等键，并在业务数据库中原子记录执行结果。对于无法提供幂等语义的外部系统，应缩短工具执行窗口、设置超时并设计人工对账流程。

