# 每账号并发上限

## 概述

每账号并发上限用于限制同一个供应商账号同时存在多少个活动任务。例如某个 Gitee 账号最多允许 2 个 OCR 任务同时运行，就可以将 `gitee + account-a` 的并发上限设置为 2。

它解决的是“已经提交但尚未结束的任务太多”的问题。与 QPS 不同，并发上限关注任务占用供应商容量的整个生命周期，而不是某一秒提交了多少次。

> 该能力只控制 `manager.submit()` 的后台提交。没有设置 `accountId` 的任务不会参与账号并发限制。

## 为什么需要账号并发上限

很多异步供应商对账号施加的是活动任务限制，而不是简单的请求频率限制。即使每秒只提交 1 个视频任务，任务平均需要 5 分钟，几分钟后仍可能积累大量运行中任务。

没有并发控制时可能出现：

- 供应商返回“并发任务数已达上限”。
- 同一账号下大量任务相互排队，完成时间不可预测。
- 一个账号持续过载，而其他账号资源闲置。
- 失败后重复提交，使供应商活动任务进一步膨胀。

账号并发上限会让超出容量的任务留在本地队列，等已有任务进入终态后再提交。

## 适用场景

- 供应商按 API Key、项目或账号限制活动任务数。
- 视频生成、长文档解析等处理时间较长的任务。
- 应用配置了多个供应商账号，需要分别控制各账号负载。
- 希望避免供应商侧排队过长，将等待过程留在自己的 Store 中管理。

## 快速开始

### 1. 配置账号容量

```java
import com.agentsflex.asynctask.policy.InMemoryAsyncTaskAdmissionPolicy;

InMemoryAsyncTaskAdmissionPolicy admission =
    new InMemoryAsyncTaskAdmissionPolicy();

admission.setAccountConcurrency("gitee", "account-a", 2);
```

### 2. 标记任务所属账号

```java
AsyncTaskOptions options = new AsyncTaskOptions();
options.setProviderKey("gitee");
options.setAccountId("account-a");

AsyncTask task = manager.submit(
    OcrRequest.ofUrl("https://files.example.com/document.pdf"),
    30 * 60_000L,
    options
);
```

### 3. 启动带准入策略的 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", store, registry, retryPolicy, admission, 30_000L
);
worker.start(1_000L, 10);
```

当 `account-a` 已有 2 个活动任务时，新任务不会访问供应商，而是继续等待。

## 统计维度

账号容量按 `providerKey + accountId` 组合统计：

```text
gitee + account-a  独立计数
gitee + account-b  独立计数
baidu + account-a  独立计数
```

因此不同供应商可以使用相同账号别名，不会互相影响。`setAccountConcurrency()` 的供应商键必须与 `options.providerKey` 一致。

## 什么是活动任务

任务离开 `PENDING_SUBMIT` 后开始占用账号容量。当前会占用容量的状态包括：

- `SUBMITTING`
- `SUBMITTED`
- `RUNNING`

进入以下终态后释放容量：

- `SUCCEEDED`
- `FAILED`
- `CANCELED`
- `TRACKING_TIMED_OUT`
- `SUBMIT_UNKNOWN`

账号上限为 1 时，生命周期如下：

```text
任务 A：PENDING_SUBMIT → SUBMITTING → RUNNING  占用容量
任务 B：PENDING_SUBMIT                         等待
任务 A：SUCCEEDED                             释放容量
任务 B：PENDING_SUBMIT → SUBMITTING            开始提交
```

## QPS 与并发上限怎么选

两者解决不同问题：

| 能力 | 控制对象 | 示例 |
| --- | --- | --- |
| 每供应商 QPS | 一秒内允许多少次提交 | 每秒最多创建 5 个任务 |
| 每账号并发上限 | 同时有多少个活动任务 | account-a 最多运行 2 个任务 |

长任务通常需要同时配置：

```java
admission.setProviderQps("gitee", 5);
admission.setAccountConcurrency("gitee", "account-a", 2);
```

## 多账号场景

如果应用有多个真实供应商账号，应为任务设置能够稳定映射到真实凭证的 `accountId`。Handler 在提交时根据 metadata 或服务端路由选择凭证，不要把 API Key 本身写入 `accountId` 或持久化 payload。

当前框架负责限制已经选定账号的并发量，不会自动在多个账号之间做负载均衡。账号选择应由业务路由或自定义 Handler 完成。

## 集群部署

内存策略的检查与计数不是跨 JVM 原子操作。多个进程可能同时看到账号还有一个空位，并各自提交一个任务。

此外，JDBC/Redis Store 会把候选快照交给 Java 策略后逐项领取；内置策略不会把本批已获准的候选写回该
快照。因此即使只有一个进程，一轮批量领取也不能作为严格并发预占。供应商有硬性并发限制时，应实现
共享原子预占，或为该供应商使用单提交 Worker 并把提交批量设为 1。

严格的全局并发限制需要共享策略原子预占容量，或确保某个供应商账号只由一个提交 Worker 管理。涉及计费或供应商硬限制时，不应仅依赖进程内策略。

## 常见问题

### 排队任务会占用并发名额吗？

不会。`PENDING_SUBMIT` 尚未访问供应商，不占账号容量。

### 查询失败会立即释放名额吗？

可重试异常期间任务仍未终态，会继续占用容量。超过重试上限进入 `FAILED`，或达到截止时间进入 `TRACKING_TIMED_OUT` 后才释放。

### 为什么同一个账号仍超过上限？

检查所有任务是否使用相同 `providerKey` 和 `accountId`，以及是否存在多个 JVM 使用各自的内存策略。

## 下一步

- [每供应商 QPS](./provider-qps)
- [租户配额](./tenant-quota)
- [调度与准入控制](./scheduling)
