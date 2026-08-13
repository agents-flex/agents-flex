# 延迟提交

## 概述

延迟提交让任务先可靠写入 Store，但在指定等待时间结束前不调用供应商。它适合“现在接收任务，稍后再真正执行”的业务需求。

例如系统可以立即接受用户请求并返回框架任务 ID，但要求 30 秒后再调用 OCR；应用即使在等待期间重启，JDBC 或 Redis Store 中的任务仍可被新 Worker 恢复。

延迟提交提供的是最早执行时间，不是精确到毫秒的定时调度。

> 该能力只适用于 `manager.enqueue()`。`manager.submit()` 会在当前线程立即访问供应商。

## 为什么需要延迟提交

业务代码中直接 `Thread.sleep()` 或使用进程内定时器存在明显问题：

- 占用线程或内存，任务量大时不可扩展。
- 应用重启后等待任务丢失。
- 无法与 QPS、配额、优先级统一调度。
- 无法查询任务当前状态，也难以取消。

延迟提交把 payload、计划时间和状态持久化，由 Worker 在到期后领取，适合可靠的后台调度。

## 适用场景

- 用户提交后需要等待冷却时间再处理。
- 大批量任务需要错峰，避免同时打到供应商。
- 上游文件预计稍后才会在对象存储中可用。
- 失败补偿需要在未来某个时间重新创建新任务。
- 预约执行，但允许秒级或分钟级误差。

如果要求日历表达式、重复执行或严格定时，应使用专业调度系统创建 `enqueue()` 任务，而不是仅依赖相对延迟。

## 快速开始

### 1. 设置相对延迟

```java
AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");
options.setDelayMillis(30_000L);
```

`delayMillis` 必须大于等于 0，默认值为 0。

### 2. 将任务放入队列

```java
AsyncTask task = manager.enqueue(
    "ocr:gitee:queued",
    command,
    30 * 60_000L,
    options
);
```

调用完成后任务已经持久化，但状态仍为 `PENDING_SUBMIT`，供应商还没有收到请求。

### 3. 启动 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", store, registry, retryPolicy, admission, 30_000L
);
worker.start(1_000L, 10);
```

Worker 会跳过尚未到期的任务，到期后再进行准入检查和供应商提交。

## 时间是如何计算的

Manager 使用 Store 的权威时间计算：

```text
scheduledSubmitAt = store.currentTimeMillis() + delayMillis
```

使用 Store 时间可以减少不同应用节点本地时钟不一致带来的影响。当前 API 接收相对延迟；如果业务有绝对执行时间，可以在调用前计算：

```java
long delayMillis = Math.max(
    0L,
    executeAt.toEpochMilli() - store.currentTimeMillis()
);
options.setDelayMillis(delayMillis);
```

## 为什么到期后没有立刻执行

`scheduledSubmitAt` 只是最早允许提交时间。实际开始还取决于：

- Worker 是否运行，以及扫描间隔。
- 本轮 `batchSize` 是否足够。
- 更高优先级的到期任务是否正在等待。
- 供应商 QPS、账号并发和租户配额是否允许。
- 供应商是否处于暂停状态。

因此设置 30 秒表示“至少等待 30 秒”，不承诺第 30 秒整执行。

## 跟踪时限必须覆盖等待时间

`trackingTimeoutMillis` 从任务创建开始计算，包含延迟、排队、供应商运行和查询阶段：

```java
options.setDelayMillis(10 * 60_000L);

// 10 分钟延迟 + 最多 30 分钟供应商处理
manager.enqueue(
    handlerKey,
    command,
    40 * 60_000L,
    options
);
```

如果 Worker 准备提交时已经超过 `deadlineAt`，任务会进入 `TRACKING_TIMED_OUT`，供应商不会收到请求。

## 与优先级组合

```java
options.setDelayMillis(30_000L);
options.setPriority(100);
```

高优先级不会绕过延迟。只有任务到期后，优先级才决定它与其他到期任务的领取顺序。

## payload 的持久化要求

延迟期间必须保存提交参数，因此 command 必须实现 `Serializable`，并被 Store 序列化器允许。

不要保存短期签名 URL、打开的文件流、HTTP Client 或本地临时文件句柄。推荐保存对象存储 key，在真正提交时由 Handler 生成仍然有效的下载 URL。

## 取消延迟任务

```java
boolean accepted = manager.cancel(task.getId());
```

取消标记会持久化。Worker 领取任务后会在调用供应商前再次检查，并将任务置为 `CANCELED`。取消不会直接从 Store 删除任务。

## 常见问题

### 延迟任务会因为应用重启而丢失吗？

使用 JDBC 或 Redis Store 时不会。内存 Store 会随进程退出丢失，仅适合测试和开发。

### 可以每天凌晨执行吗？

可以由外部调度器在每天凌晨调用 `enqueue()`。本功能本身不提供 Cron 和重复任务定义。

### 延迟期间供应商暂停了会怎样？

任务到期后仍无法提交，会继续等待；恢复供应商后重新参与领取，但总等待时间仍受 `deadlineAt` 限制。

## 下一步

- [优先级队列](./priority-queue)
- [暂停供应商](./provider-pause)
- [Store 持久化](./store)
