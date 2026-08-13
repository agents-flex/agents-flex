# 每供应商 QPS

## 概述

每供应商 QPS 用于限制应用向某个 OCR、视频或其他异步服务商创建任务的速度。例如供应商规定“每秒最多创建 5 个任务”，就可以把该供应商的 QPS 设置为 5。超过速度的任务不会失败，而是继续保留在队列中，等待后续 Worker 扫描。

这个能力解决的是“单位时间内提交太快”的问题。它控制提交频率，不控制供应商当前正在执行多少个任务。

> QPS 控制只作用于 `manager.enqueue()` 创建的排队任务。`manager.submit()` 会立即访问供应商，不经过 QPS 控制。

## 为什么需要 QPS 控制

没有统一限速时，一次批量导入可能瞬间向供应商发送数百个请求，常见后果包括：

- 触发供应商 `429 Too Many Requests` 或封禁策略。
- 大量失败请求进入重试，进一步放大流量。
- 一个业务高峰占满供应商额度，影响其他正常请求。
- 不同业务各自限流，规则分散且难以统一调整。

把任务先写入 Store，再由 Worker 按 QPS 提交，可以把突发流量转换为平稳流量。

## 适用场景

- 供应商文档明确规定每秒请求上限。
- OCR 批量导入、视频批量生成等突发任务。
- 多个业务共用一个供应商，希望统一控制总提交速度。
- 供应商近期不稳定，需要临时降低请求速率。

如果限制是“同一个账号最多同时运行 2 个任务”，应使用[每账号并发上限](./account-concurrency)，而不是 QPS。

## 快速开始

### 1. 创建准入策略

```java
import com.agentsflex.asynctask.policy.InMemoryAsyncTaskAdmissionPolicy;

InMemoryAsyncTaskAdmissionPolicy admission =
    new InMemoryAsyncTaskAdmissionPolicy();

// Gitee 每秒最多提交 5 个任务
admission.setProviderQps("gitee", 5);
```

### 2. 给任务设置相同的供应商键

```java
import com.agentsflex.asynctask.AsyncTaskSubmissionOptions;

AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");

AsyncTask task = manager.enqueue(
    "ocr:gitee:queued",
    command,
    30 * 60_000L,
    options
);
```

`command` 必须是 Handler 接受且实现 `Serializable` 的提交 DTO。`providerKey` 未设置时会使用 Handler Key；准入策略中的键必须与任务最终保存的键完全一致。

### 3. 将策略交给 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1",
    store,
    registry,
    retryPolicy,
    admission,
    30_000L
);
worker.start(200L, 20);
```

至此，即使业务瞬间调用 `enqueue()` 创建 100 个任务，Worker 也只会按配置速度提交，其他任务保持 `PENDING_SUBMIT`。

## 工作原理

`InMemoryAsyncTaskAdmissionPolicy` 为每个 `providerKey` 保存最近 1000 毫秒内获准提交的时间点，窗口定义为 `(now - 1000, now]`。

QPS 为 2 时：

```text
1000ms：任务 A、B 获准提交
1500ms：任务 C 仍在队列中
2000ms：A、B 离开统计窗口，任务 C 获准提交
```

供应商暂停、租户配额和账号并发会先于 QPS 判断。只有最终获准提交的任务才消耗 QPS 额度。

## 如何选择 QPS

优先使用供应商官方额度，并预留一定余量。例如官方上限是 10 QPS，可以先配置为 8，观察 `429`、网络延迟和成功率后再调整。

Worker 的扫描间隔和批量大小也会影响实际速度：

- `batchSize` 小于 QPS 时，单轮无法用满额度。
- 扫描间隔过长时，实际吞吐可能低于上限。
- QPS 是最大允许值，不是每秒必须达到的目标值。

## 与其他能力组合

常见生产配置会同时限制提交速度和活动任务数：

```java
admission.setProviderQps("gitee", 5);
admission.setAccountConcurrency("gitee", "account-a", 2);
```

任务必须同时满足两项条件：当前一秒还有 QPS 额度，并且账号仍有并发容量。

## 集群部署

`InMemoryAsyncTaskAdmissionPolicy` 的窗口只在当前 JVM 中有效。3 个进程都配置 5 QPS 时，集群总速度可能接近 15 QPS。

需要严格全局 QPS 时，应实现基于 Redis、数据库或网关的共享 `AsyncTaskAdmissionPolicy`，通过原子操作维护滑动窗口；另一种方案是让同一供应商只有一个 Worker 负责提交。

## 常见问题

### 被限流的任务会失败吗？

不会。任务继续保持 `PENDING_SUBMIT`，下一轮扫描会重新尝试，直到获准、取消或超过跟踪截止时间。

### QPS 会限制结果查询吗？

不会。当前能力只限制供应商任务创建，已经提交任务的 `query()` 调用不计入该 QPS。

### 为什么配置后没有生效？

确认使用的是 `enqueue()`，Worker 构造器传入了该 `admission` 实例，并且任务的 `providerKey` 与配置键一致。

## 下一步

- [每账号并发上限](./account-concurrency)
- [暂停供应商](./provider-pause)
- [调度与准入控制](./scheduling)
