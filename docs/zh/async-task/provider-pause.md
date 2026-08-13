# 暂停某个供应商

## 概述

暂停供应商是一项运行时开关，用于临时阻止某个 `providerKey` 创建新的远端任务。任务仍可以进入本地队列，已经提交的任务也继续查询，但 Worker 不会再向被暂停的供应商发起新的创建请求。

它适合供应商故障、维护、额度耗尽、成本控制和人工熔断等运维场景。

> 暂停只作用于 `manager.enqueue()` 的后台提交。`manager.submit()` 不经过准入策略，仍会立即访问供应商。

## 为什么需要暂停能力

供应商出现异常时，如果只是关闭 Worker，会同时停止所有供应商和结果查询；如果拒绝业务请求，又可能丢失用户任务。暂停单个供应商可以做到：

- 继续接收并持久化新任务，等待恢复后再提交。
- 不影响其他健康供应商。
- 不停止已提交任务的状态查询和结果回收。
- 给运维人员一个明确、可恢复的控制手段。

相比修改代码、重启服务或临时删除 API Key，暂停开关的影响范围更清晰。

## 适用场景

- 供应商持续返回 5xx、429 或网络不可达。
- 供应商计划维护或区域故障。
- 账号余额、调用额度即将耗尽。
- 发现供应商输出质量异常，需要停止新任务。
- 发布新 Handler 前，希望先停止流量并排空活动任务。

## 快速开始

### 1. 创建准入策略并暂停

```java
import com.agentsflex.asynctask.policy.InMemoryAsyncTaskAdmissionPolicy;

InMemoryAsyncTaskAdmissionPolicy admission =
    new InMemoryAsyncTaskAdmissionPolicy();

admission.pauseProvider("gitee");
```

### 2. 任务使用相同的供应商键

```java
AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");

AsyncTask task = manager.enqueue(
    "ocr:gitee:queued",
    command,
    30 * 60_000L,
    options
);
```

此时任务会正常写入 Store，但保持 `PENDING_SUBMIT`。

### 3. 检查并恢复

```java
boolean paused = admission.isProviderPaused("gitee");

admission.resumeProvider("gitee");
```

重复暂停或恢复是幂等的。恢复后，Worker 会在下一轮扫描中重新处理仍有效的任务。

## `providerKey` 必须一致

暂停依据是任务上的 `providerKey`，不是类名、模型名或展示名称。

```java
options.setProviderKey("gitee");
admission.pauseProvider("gitee");
```

如果没有设置 `providerKey`，Manager 默认使用 Handler Key，例如 `ocr:gitee:queued`，此时必须暂停该完整 Handler Key。建议在生产系统中明确设置并集中定义供应商键。

## 暂停后的准确行为

| 行为 | 是否继续 |
| --- | --- |
| 新任务调用 `enqueue()` 并写入 Store | 是 |
| Worker 创建新的供应商任务 | 否 |
| 其他供应商创建任务 | 是 |
| 已提交任务调用 Handler `query()` | 是 |
| 直接调用 `manager.submit()` | 是，不受暂停控制 |
| 供应商远端任务自动取消 | 否 |

暂停不会删除任务、修改优先级或延长截止时间。

## 为什么仍然查询已提交任务

暂停的目标是阻止新增负载。如果连查询也停止，已经付费创建的任务可能完成但结果 URL 过期，或者本地无法及时释放账号并发和租户配额。

如果供应商的查询接口本身也发生故障，Worker 会按查询重试策略退避；这与提交暂停是两个独立机制。

## 恢复时要注意队列流量

长时间暂停后可能积累大量待提交任务。直接恢复会让 Worker 按 QPS 和其他准入条件逐步释放流量。建议恢复前配置合理 QPS：

```java
admission.setProviderQps("gitee", 2);
admission.resumeProvider("gitee");
```

如果任务在暂停期间超过 `deadlineAt`，Worker 会将其置为 `TRACKING_TIMED_OUT`，不会补提交。暂停时间不会自动延长跟踪时限。

## 生产运维设计

可以通过受权限保护的管理接口封装开关：

```java
public void setProviderEnabled(String providerKey, boolean enabled) {
    if (enabled) {
        admission.resumeProvider(providerKey);
    } else {
        admission.pauseProvider(providerKey);
    }
}
```

生产系统还应记录操作者、原因、变更时间和恢复时间，并对暂停时长和队列积压设置告警。普通租户不应拥有全局暂停权限。

## 集群部署

`InMemoryAsyncTaskAdmissionPolicy` 的暂停状态只存在于当前 JVM。多 Worker 部署时，暂停一个实例不能阻止其他实例提交，实例重启后状态也会丢失。

全局暂停应保存在 Redis、数据库或配置中心，并由共享 `AsyncTaskAdmissionPolicy` 在准入时读取。还需要明确共享配置不可用时采用“默认暂停”还是“默认放行”。

## 暂停与自动熔断

当前能力是显式开关，不会根据错误率自动暂停。自动熔断可以建立在同一抽象之上：监控 `SUBMIT_UNKNOWN`、供应商错误率和延迟，达到阈值后写入共享暂停状态，恢复则采用人工确认或半开探测。

## 常见问题

### 暂停后为什么还有供应商请求？

可能是已提交任务的结果查询，也可能是业务使用了 `manager.submit()`，或者其他 JVM 没有共享暂停状态。

### 暂停会取消供应商正在运行的任务吗？

不会。它只阻止新任务创建。

### 恢复后会一次提交全部积压任务吗？

不会超过 Worker 的批量大小和已配置的准入限制，但未配置 QPS 时仍可能较快释放。恢复前应检查积压量和限速配置。

## 下一步

- [每供应商 QPS](./provider-qps)
- [延迟提交](./delayed-submission)
- [调度与准入控制](./scheduling)
