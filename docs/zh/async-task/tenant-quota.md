# 租户配额

## 概述

租户配额用于限制一个租户同时占用的活动异步任务数量。它面向 SaaS、多部门平台或开放 API 等多租户系统，防止单个租户提交大量 OCR、视频任务后占满共享容量。

当前实现中的“配额”指活动任务上限，不是每天调用次数、文档页数或费用额度。

> 租户配额只控制 `manager.enqueue()` 的后台提交。没有设置 `tenantId` 的任务不会参与配额限制。

## 为什么需要租户配额

只限制供应商 QPS 或账号并发，仍无法保证租户之间公平。例如账号允许同时运行 20 个任务，租户 A 可能一次占满全部 20 个名额，租户 B 的少量任务只能等待。

租户配额可以解决：

- 防止单个租户耗尽共享供应商容量。
- 为不同套餐设置不同的活动任务上限。
- 将异常批量提交的影响限制在单个租户内。
- 在 OCR、视频等不同能力之间统一约束租户占用量。

## 适用场景

- SaaS 平台按企业、工作空间或项目隔离资源。
- 内部平台按部门限制并发任务。
- 免费版、专业版、企业版需要不同任务容量。
- 多种异步能力共用一套租户资源预算。

如果需要按天累计调用次数或费用，应在计费和用量系统中实现，不能用本功能替代。

## 快速开始

### 1. 配置租户活动任务上限

```java
import com.agentsflex.asynctask.policy.InMemoryAsyncTaskAdmissionPolicy;

InMemoryAsyncTaskAdmissionPolicy admission =
    new InMemoryAsyncTaskAdmissionPolicy();

admission.setTenantQuota("tenant-a", 10);
admission.setTenantQuota("tenant-b", 3);
```

### 2. 给任务设置租户

```java
AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");
options.setAccountId("account-a");
options.setTenantId("tenant-a");

AsyncTask task = manager.enqueue(
    "ocr:gitee:queued",
    command,
    30 * 60_000L,
    options
);
```

### 3. 将策略传给 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", store, registry, retryPolicy, admission, 30_000L
);
worker.start(1_000L, 10);
```

达到上限后，该租户的新任务保持 `PENDING_SUBMIT`；其他租户仍可正常提交。

## 租户 ID 从哪里来

`tenantId` 应来自服务端已经认证的租户上下文，例如登录会话、JWT 中经过验证的 claim 或数据库中的工作空间归属。

不要直接信任客户端请求体中的任意租户 ID，否则调用方可以伪造其他 ID 绕过配额。也不要在 `tenantId` 中放租户名称等容易变更的展示信息，应使用稳定内部标识。

## 统计范围

租户配额只按 `tenantId` 统计，默认跨供应商、跨账号、跨 Handler：

```text
tenant-a / 百度 OCR / RUNNING       占用 1
tenant-a / Gitee OCR / SUBMITTED    占用 1
tenant-a / 视频生成 / PENDING       等待
tenant-b / 视频生成                 使用 tenant-b 自己的配额
```

已经离开 `PENDING_SUBMIT` 且尚未终态的任务占用配额。成功、失败、取消、跟踪超时或提交结果未知后释放配额。

## 如何设计配额值

配额值应同时考虑套餐、供应商总容量和任务平均耗时。例如供应商账号并发上限为 20，服务 10 个普通租户时，不应简单地给每个租户都配置 20。

一种常见设计是：

| 套餐 | 活动任务上限 |
| --- | ---: |
| 免费版 | 1 |
| 专业版 | 5 |
| 企业版 | 20 |

所有租户上限之和可以高于物理容量，因为租户通常不会同时用满；供应商账号并发上限负责保护最终物理容量。

## 与账号并发组合

```java
admission.setTenantQuota("tenant-a", 5);
admission.setAccountConcurrency("gitee", "account-a", 10);
```

任务必须同时满足两个条件。租户还有额度但账号已满时需要等待；账号有容量但租户已满时同样需要等待。

## 集群部署

`InMemoryAsyncTaskAdmissionPolicy` 不提供跨 JVM 的严格全局配额。多个 Worker 进程可能同时判断租户还有容量。

生产环境若把配额用于套餐承诺或计费，应使用 Redis、数据库等共享存储原子预占和释放容量，并处理 Worker 崩溃、租约过期和终态清理。

## 常见问题

### 配额是每天最多提交多少个任务吗？

不是。它限制当前活动任务数。任务结束并释放容量后，同一租户可以继续提交新任务。

### 为什么某租户的 OCR 和视频任务会相互影响？

这是默认设计：租户配额跨供应商和 Handler 统计。如果需要按产品分别配额，可以构造不同的业务隔离键，或实现自定义准入策略。

### 没有设置 `tenantId` 会怎样？

该任务不会参与租户配额。生产入口应统一注入租户 ID，避免遗漏形成绕过通道。

## 下一步

- [每账号并发上限](./account-concurrency)
- [优先级队列](./priority-queue)
- [调度与准入控制](./scheduling)
