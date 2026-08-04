---
title: AgentWorker
description: 使用 Lease、心跳与 fencing token 安全执行长任务和分布式 AgentRun。
---

# AgentWorker

## 概述

`AgentWorker` 从共享 `AgentRunStore` 领取可运行快照，并推进到终止或阻塞。它适合长任务、异步 API 和多实例部署。Worker 使用 Lease 防止同一个 Run 同时被多个进程推进，并用唯一 `leaseId` 阻止旧执行者提交过期结果。

## 提交与执行

```java
AgentRun queued = runner.start(agent, "生成月报");

try (AgentWorker worker = new AgentWorker(
    "report-worker-1", runner, 30_000)) {
    List<AgentRun> processed = worker.pollAndRun(10);
}
```

`start` 只保存 READY Run。`pollAndRun(limit)` 会先修复父子唤醒，再逐个领取 Run 并同步推进。

## 自动轮询

```java
AgentWorker worker = new AgentWorker("worker-1", runner, 30_000);
worker.startPolling(1_000, 20);

// 应用关闭时
worker.close();
```

重复 `startPolling` 不会创建多个线程。`close` 停止新轮询，但不保证强制中断正在进行的模型 HTTP 或业务工具。

## Lease 与 fencing

领取时 Store 写入：

- `leaseOwner`：Worker ID。
- `leaseId`：每次领取生成的唯一令牌。
- `leaseUntil`：到期时间。

Worker 在租期约三分之一处自动续租。保存 Snapshot 时同时校验 owner 和 leaseId；即使新 Worker 与旧进程使用相同 workerId，旧 leaseId 也无法覆盖新状态。

Lease 不是分布式事务。外部工具仍需业务幂等，网络分区时旧 Worker 可能已经发出副作用，只是在 Snapshot 时被 fencing 拒绝。

## 可领取状态

Store 通常领取 READY、可继续的 RUNNING、到期 `RETRY_SCHEDULED`，以及已请求取消但尚未终止的 Run。等待审批、用户或子任务的 Run 在相应事件到达前不可领取。

## 父子恢复

Worker 每轮调用 `recoverCompletedChildren`，发现子 Run 已终止而父 Run 仍等待时进行补偿唤醒。这覆盖子 Snapshot 已提交、父 Snapshot 尚未更新时进程退出的窗口。

## 容量规划

当前 Worker 一次领取后同步执行 Run。可通过多个 Worker 实例扩容，但应考虑模型和工具的连接池、速率限制与线程安全。`batchSize` 决定一轮最多处理多少，不代表内部并行度。

## 运维指标

监控 runnable 队列长度、最老任务年龄、领取/完成速率、Lease 续租失败、版本冲突、重试到期延迟和 Worker 最近成功轮询时间。业务系统还应独立监控恢复事件 Inbox 或消息队列。Lease 时间应明显大于正常网络抖动，并小于可接受的故障接管时间。

## 优雅关闭

停止接收新任务，调用 `close()`，等待正在执行的 poll 返回，再关闭进程。由于外部调用不一定可中断，部署平台的 termination grace period 应覆盖常见模型/工具超时；超时退出后由 Lease 到期触发接管。
