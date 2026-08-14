# 优先级队列

## 概述

优先级队列用于决定多个待提交任务中谁先获得提交机会。当供应商 QPS、账号并发或 Worker 吞吐有限时，系统不再只能严格“先来先服务”，而是可以让在线用户请求、付费租户任务或故障补偿任务先于普通批处理任务执行。

优先级只影响还处于 `PENDING_SUBMIT` 的任务。任务一旦提交给供应商，框架不会因为出现更高优先级任务而中断或抢占它。

> 所有 `manager.submit()` 创建的任务都会进入待提交队列。优先级决定 Worker 在同一轮候选任务中的领取顺序。

## 为什么需要优先级队列

异步系统通常同时承载不同类型的工作：

- 用户在页面上刚提交的单个 OCR，希望尽快看到结果。
- 夜间批量归档几十万份历史文档，允许慢慢处理。
- 企业租户购买了更高服务等级。
- 运维补偿任务需要优先恢复业务数据。

如果所有任务只有 FIFO，一次大型批处理可能让交互请求等待很久。优先级队列让有限容量先服务更重要的任务。

## 适用场景

- 在线交互任务与离线批处理共用 Worker。
- 不同套餐具有不同服务等级。
- 告警、补偿或人工重试需要优先处理。
- 供应商被限流，队列中经常存在等待任务。

如果系统几乎没有排队，设置优先级不会明显改变完成时间。

## 快速开始

### 1. 定义有限的优先级等级

```java
public final class TaskPriorities {
    public static final int BATCH = -100;
    public static final int NORMAL = 0;
    public static final int HIGH = 100;
    public static final int URGENT = 1000;

    private TaskPriorities() {
    }
}
```

框架接受任意 `int`，数值越大越优先。业务中建议只使用少量固定等级，避免每个调用方随意设置数字。

### 2. 提交高优先级任务

```java
AsyncTaskOptions options = new AsyncTaskOptions();
options.setProviderKey("gitee");
options.setPriority(TaskPriorities.HIGH);

AsyncTask task = manager.submit(
    OcrRequest.ofUrl("https://files.example.com/document.pdf"),
    30 * 60_000L,
    options
);
```

未设置时优先级默认为 0。负数可表示低优先级后台任务。

### 3. 启动 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", store, registry, retryPolicy, admission, 30_000L
);
worker.start(1_000L, 10);
```

当本轮有多个到期候选任务时，Store 会优先返回数值更大的任务。

## 排序规则

只有已经到达 `scheduledSubmitAt` 的任务才进入候选集，随后依次比较：

1. `priority` 降序，数字大的优先。
2. `scheduledSubmitAt` 升序，更早可提交的优先。
3. `createdAt` 升序，更早创建的优先。

```text
任务 A：priority=100，scheduled=10:00  第 1
任务 B：priority=100，scheduled=10:05  第 2
任务 C：priority=0，  scheduled=09:00  第 3
```

上例假设领取时三项都已到期。任务 B 如果要到 10:05 才到期，就不会在 10:04 因高优先级而提前提交。

## 优先级不会绕过准入控制

高优先级只决定检查顺序，不会突破：

- 供应商 QPS
- 账号并发上限
- 租户配额
- 供应商暂停
- 延迟提交时间

例如某个高优先级任务所属账号已满，而另一个普通任务使用不同账号且有容量，普通任务仍可能先提交。

## 如何映射业务等级

不要让客户端直接传任意整数。服务端应根据认证后的租户套餐和任务类型映射：

```java
int resolvePriority(TenantPlan plan, TaskType type) {
    if (type == TaskType.INCIDENT_RECOVERY) {
        return TaskPriorities.URGENT;
    }
    if (plan == TenantPlan.ENTERPRISE) {
        return TaskPriorities.HIGH;
    }
    return TaskPriorities.NORMAL;
}
```

这样可以避免普通调用方把所有任务都标记成最高优先级，使队列失去意义。

## 低优先级饥饿

当前实现不会随等待时间自动提升优先级。如果高优先级任务持续涌入，低优先级任务可能长时间无法提交。

常见治理方式：

- 限制高优先级任务的来源和比例。
- 为离线批处理使用独立 Worker、供应商账号或队列。
- 在自定义 Store 中实现等待时间 aging。
- 监控各优先级的队列长度和最长等待时间。

不要直接修改已持久化任务字段来提升优先级，除非自定义 Store 能同步更新调度索引并处理 CAS 版本。

## 多 Worker 下的语义

内存、JDBC 和 Redis Store 都按相同规则选择候选，并通过锁、CAS 或 Lua 防止同一任务被重复领取。但多个 Worker 获得不同任务后，真实外部请求的开始顺序仍可能受网络和线程调度影响。

因此优先级提供的是领取优先，而不是严格的供应商完成顺序保证。

## 常见问题

### 高优先级任务能抢占正在运行的低优先级任务吗？

不能。框架不取消或抢占已提交的远端任务。

### 为什么高优先级任务没有立即执行？

检查它是否已到延迟时间，以及 QPS、账号并发、租户配额和供应商暂停是否允许。

### 相同优先级如何排序？

先比较计划提交时间，再比较创建时间，较早的任务优先。

## 下一步

- [延迟提交](./delayed-submission)
- [租户配额](./tenant-quota)
- [调度与准入控制](./scheduling)
