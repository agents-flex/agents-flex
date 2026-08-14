# 调度与准入控制

每供应商 QPS、每账号并发、租户配额、优先级、延迟提交和供应商暂停统一作用于 `submit()` 创建的待提交任务。Manager 只负责持久化，Worker 领取任务并通过准入策略后才访问供应商。

## 什么时候需要调度控制

所有任务都使用 `submit()`。不需要特定治理能力时使用默认选项；任务批量进入、供应商有额度限制、平台服务多个租户，或者需要错峰和运维暂停时，通过 `AsyncTaskOptions` 和准入策略补充相应维度。

六项能力解决的问题不同：

## 能力文档

| 能力 | 解决的问题 | 详细文档 |
| --- | --- | --- |
| 每供应商 QPS | 平滑突发流量，避免每秒提交过多 | [每供应商 QPS](./provider-qps) |
| 每账号并发上限 | 限制同一供应商账号的活动任务数 | [每账号并发上限](./account-concurrency) |
| 租户配额 | 防止单个租户占满共享容量 | [租户配额](./tenant-quota) |
| 优先级队列 | 容量不足时先处理更重要的任务 | [优先级队列](./priority-queue) |
| 延迟提交 | 指定最早允许访问供应商的时间 | [延迟提交](./delayed-submission) |
| 暂停某个供应商 | 故障或维护期间停止创建新任务 | [暂停供应商](./provider-pause) |

## 公共配置

```java
import com.agentsflex.asynctask.policy.InMemoryAsyncTaskAdmissionPolicy;

InMemoryAsyncTaskAdmissionPolicy admission =
    new InMemoryAsyncTaskAdmissionPolicy();

admission.setProviderQps("gitee", 5);
```

创建任务时，通过 `AsyncTaskOptions` 指定调度维度：

```java
import com.agentsflex.asynctask.AsyncTaskOptions;

AsyncTaskOptions options = new AsyncTaskOptions();
options.setProviderKey("gitee");
options.setAccountId("account-a");
options.setTenantId("tenant-a");
options.setPriority(100);
options.setDelayMillis(30_000L);
```

`providerKey` 未设置时默认使用 Handler Key。没有设置 `accountId` 或 `tenantId` 的任务，不参与对应维度的限制。

## 提交参数必须可持久化

`submit()` 的参数类型必须同时满足：

1. 与 Handler 的 `getSubmitParamsType()` 一致。
2. 实现 `Serializable`。
3. 能被 Store 配置的序列化器恢复。

OCR 可以直接提交 URL 请求，不需要额外定义 Command DTO：

```java
OcrRequest request = OcrRequest.ofUrl(
    "https://files.example.com/document.pdf"
);
AsyncTask task = manager.submit(
    request,
    30 * 60_000L,
    options
);
```

不要持久化 API Key、HTTP Client、打开的流、本地文件或大块二进制。以下调用会在创建任务前抛出
`IllegalArgumentException`：

```java
manager.submit(
    OcrRequest.ofFile(new File("document.pdf")),
    timeout,
    options
);
```

正确做法是先上传文件，再使用 `OcrRequest.ofUrl(url)`。URL 的有效期必须覆盖可能的排队时间和供应商
读取时间。自定义请求类型还必须实现 `Serializable`，并通过 Handler 的 `validateSubmitParams()` 声明
领域约束。

## 启动带准入控制的 Worker

```java
AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1",
    store,
    registry,
    retryPolicy,
    admission,
    30_000L
);
worker.start(1_000L, 10);
```

每轮扫描会先提交最多 `batchSize` 个待提交任务，再查询最多 `batchSize` 个到期任务。

## 集群部署注意事项

`InMemoryAsyncTaskAdmissionPolicy` 的配置和 QPS 窗口只存在于当前 JVM。JDBC/Redis Store 可以防止同一任务被多个 Worker 同时领取，但不会自动把本地准入计数变成集群全局计数。

内置策略的账号并发和租户配额通过 Store 提供的任务快照计算，只有与 `InMemoryAsyncTaskStore` 的同一领取
锁配合时，才能把同批任务纳入后续判断。使用 JDBC/Redis Store 时，即使暂时只有一个 JVM，也不要把它
视为严格的原子额度实现；减小 `batchSize` 只能降低竞争窗口，不能替代共享预占。

如果需要严格的集群级 QPS、账号并发和租户配额，应实现共享的 `AsyncTaskAdmissionPolicy`，并使用 Redis
Lua、数据库锁或网关限流保证“检查并预占”的原子性。只让策略读取 JDBC/Redis 中的任务快照仍然存在
并发窗口，不等同于原子配额。无法提供共享预占时，应按实例数折算上限，或确保每个供应商只有一个提交
Worker。

## 推荐阅读顺序

1. 先根据供应商限制配置[每供应商 QPS](./provider-qps)和[每账号并发](./account-concurrency)。
2. 多租户系统再增加[租户配额](./tenant-quota)。
3. 有明显任务等级时使用[优先级队列](./priority-queue)。
4. 需要错峰或预约时使用[延迟提交](./delayed-submission)。
5. 为运维管理提供[供应商暂停](./provider-pause)。
