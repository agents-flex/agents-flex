# 调度与准入控制

每供应商 QPS、每账号并发、租户配额、优先级、延迟提交和供应商暂停只作用于 `enqueue()` 创建的待提交任务。`submit()` 会在当前调用线程立即访问供应商，不经过准入策略。

## 什么时候需要调度控制

当业务只是偶尔提交一个任务，并且入口已经有可靠限流，`submit()` 更直接。当任务会批量进入、供应商有额度限制、平台服务多个租户，或者需要错峰和运维暂停时，应使用 `enqueue()`。

六项能力解决的问题不同：

## 能力文档

| 能力 | 配置入口 | 详细文档 |
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

创建任务时，通过 `AsyncTaskSubmissionOptions` 指定调度维度：

```java
import com.agentsflex.asynctask.AsyncTaskSubmissionOptions;

AsyncTaskSubmissionOptions options = new AsyncTaskSubmissionOptions();
options.setProviderKey("gitee");
options.setAccountId("account-a");
options.setTenantId("tenant-a");
options.setPriority(100);
options.setDelayMillis(30_000L);
```

`providerKey` 未设置时默认使用 Handler Key。没有设置 `accountId` 或 `tenantId` 的任务，不参与对应维度的限制。

## 提交参数必须可序列化

`enqueue()` 的参数类型必须同时满足：

1. 与 Handler 的 `getSubmitParamsType()` 一致。
2. 实现 `Serializable`。
3. 能被 Store 配置的序列化器恢复。

推荐保存稳定 DTO，例如对象存储 key、模型名和业务选项：

```java
public final class QueuedOcrCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    private String objectKey;
    private String model;

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
```

然后为 DTO 实现对应 Handler：

```java
AsyncTask task = manager.enqueue(
    "ocr:gitee:queued",
    command,
    30 * 60_000L,
    options
);
```

不要持久化 API Key、HTTP Client、打开的流、本地临时文件句柄或短期签名 URL。Handler 可以根据对象存储 key 在提交时生成有效 URL。

内置 OCR 和视频 Handler 的请求类型当前不实现 `Serializable`，所以不能直接写成：

```java
// 无法满足 enqueue() 的 Serializable 类型约束
manager.enqueue("ocr:gitee", ocrRequest, timeout, options);
```

正确做法是让自定义 Handler 接受 `QueuedOcrCommand`，在真正提交时根据 `objectKey` 构造 `OcrRequest`。实现方式参见[自定义 Handler](./handler)。

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

如果需要严格的集群级 QPS、账号并发和租户配额，应实现共享的 `AsyncTaskAdmissionPolicy`，并使用 Redis Lua、数据库锁或网关限流保证原子性。否则应把配置上限按实例数折算，或确保每个供应商只有一个负责提交的 Worker。

## 推荐阅读顺序

1. 先根据供应商限制配置[每供应商 QPS](./provider-qps)和[每账号并发](./account-concurrency)。
2. 多租户系统再增加[租户配额](./tenant-quota)。
3. 有明显任务等级时使用[优先级队列](./priority-queue)。
4. 需要错峰或预约时使用[延迟提交](./delayed-submission)。
5. 为运维管理提供[供应商暂停](./provider-pause)。
