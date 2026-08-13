# 异步任务快速开始

本节使用内存 Store 和 Gitee OCR 展示完整的“立即提交、后台查询、读取结果”流程。

示例选择 `manager.submit()`：它会立即创建供应商任务，但不会等待 OCR 完成，后续查询由 Worker 执行。需要 QPS、配额、优先级或延迟提交时，再按照[调度与准入控制](./scheduling)改用 `enqueue()` 和可序列化命令 DTO。

## 第一步：添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-async-task</artifactId>
    <version>${agents-flex.version}</version>
</dependency>

<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 第二步：注册 Handler

```java
import com.agentsflex.asynctask.handler.InMemoryAsyncTaskHandlerRegistry;
import com.agentsflex.asynctask.handler.OcrAsyncTaskHandler;
import com.agentsflex.core.model.ocr.OcrModel;
import com.agentsflex.ocr.gitee.GiteeOcrConfig;
import com.agentsflex.ocr.gitee.GiteeOcrModel;

GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
OcrModel ocrModel = new GiteeOcrModel(config);

InMemoryAsyncTaskHandlerRegistry registry =
    new InMemoryAsyncTaskHandlerRegistry();
registry.register(new OcrAsyncTaskHandler("ocr:gitee", ocrModel));
```

`ocr:gitee` 是持久化在任务中的稳定键。更改键后，旧任务将无法找到对应 Handler。

## 第三步：创建 Store 和 Manager

```java
import com.agentsflex.asynctask.AsyncTaskManager;
import com.agentsflex.asynctask.store.AsyncTaskStore;
import com.agentsflex.asynctask.store.InMemoryAsyncTaskStore;

AsyncTaskStore store = new InMemoryAsyncTaskStore();
AsyncTaskManager manager = new AsyncTaskManager(store, registry);
```

内存 Store 只适用于开发和单元测试。生产环境请选择 [JDBC 或 Redis Store](./store)。

## 第四步：启动 Worker

```java
import com.agentsflex.asynctask.AsyncTaskWorker;
import com.agentsflex.asynctask.policy.ExponentialAsyncTaskRetryPolicy;

ExponentialAsyncTaskRetryPolicy retryPolicy =
    new ExponentialAsyncTaskRetryPolicy(
        3_000L,  // 正常状态每 3 秒查询一次
        1_000L,  // 第一次查询异常等待 1 秒
        60_000L, // 异常退避最多 60 秒
        5        // 最多容忍 5 次连续异常
    );

AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1",
    store,
    registry,
    retryPolicy,
    30_000L
);
worker.start(1_000L, 10);
```

`leaseMillis` 应覆盖一次供应商请求和 Store 保存的最长正常耗时；`workerId` 在所有实例间必须唯一。

## 第五步：立即提交

```java
import com.agentsflex.asynctask.AsyncTask;
import com.agentsflex.core.model.ocr.OcrRequest;

import java.io.File;

OcrRequest request = OcrRequest.ofFile(new File("input/document.pdf"));

AsyncTask task = manager.submit(
    "ocr:gitee",
    request,
    30 * 60_000L
);
```

`trackingTimeoutMillis` 从任务创建时开始计算。`submit()` 在当前线程调用供应商，但不等待 OCR 解析完成；提交成功后 Worker 会持续查询。

## 第六步：读取结果

```java
import com.agentsflex.asynctask.AsyncTaskStatus;
import com.agentsflex.core.model.ocr.OcrResponse;

AsyncTask latest = manager.get(task.getId());

if (latest.getStatus() == AsyncTaskStatus.SUCCEEDED) {
    OcrResponse response = (OcrResponse) latest.getResult();
    System.out.println(response.getMarkdown());
} else if (latest.getStatus().isTerminal()) {
    System.err.println(latest.getErrorMessage());
}
```

`manager.get()` 返回当前快照，不会阻塞。应用可以通过自己的 REST API、WebSocket、消息通知或定时器向用户暴露状态。

为了在本地示例中观察状态变化，可以有限期轮询框架 Store：

```java
long deadline = System.currentTimeMillis() + 60_000L;
AsyncTask latest;

do {
    latest = manager.get(task.getId());
    System.out.println("current status: " + latest.getStatus());

    if (latest.getStatus().isTerminal()) {
        break;
    }
    Thread.sleep(1_000L);
} while (System.currentTimeMillis() < deadline);
```

这段轮询只用于演示。Web 服务应立即返回框架任务 ID，由独立查询接口或通知机制向客户端提供状态。

## 处理终态

```java
switch (latest.getStatus()) {
    case SUCCEEDED:
        OcrResponse result = (OcrResponse) latest.getResult();
        System.out.println(result.getMarkdown());
        break;
    case FAILED:
    case CANCELED:
    case TRACKING_TIMED_OUT:
    case SUBMIT_UNKNOWN:
        throw new IllegalStateException(
            latest.getStatus() + ": " + latest.getErrorMessage()
        );
    default:
        // 尚未终态，由 Worker 继续处理。
}
```

`TRACKING_TIMED_OUT` 只表示框架停止自动跟踪，远端任务可能仍在运行。`SUBMIT_UNKNOWN` 表示供应商可能已收到提交，但本地没有拿到确定响应；不要直接自动重提，以免创建重复收费任务。

## 取消跟踪

```java
boolean accepted = manager.cancel(task.getId());
```

取消是本地跟踪取消，不保证供应商远端任务也被取消。已终态、不存在或已请求取消时返回 `false`。

## 关闭 Worker

```java
worker.close();
```

应用关闭时应释放 Worker 的调度线程。未完成任务仍保存在持久化 Store 中，其他 Worker 或重启后的实例可以在租约到期后继续处理。

## 完整生命周期建议

```text
业务接口调用 manager.submit()
    ↓
保存并返回框架 task.getId()
    ↓
Worker 定时查询供应商
    ↓
业务接口调用 manager.get(taskId)
    ↓
成功后及时转存供应商结果资源
```

需要优先级、延迟提交和额度控制时，请改用 [`enqueue()`](./scheduling)。

## 常见问题

### 为什么 `submit()` 之后还需要 Worker？

`submit()` 只立即完成供应商任务创建。供应商返回 `taskId` 后，Worker 才会按计划调用 Handler `query()`，直到成功、失败或超时。

### `trackingTimeoutMillis` 是单次 HTTP 超时吗？

不是。它是框架从任务创建到停止自动跟踪的总时限。供应商 HTTP Client 的连接和读取超时应单独配置。

### 可以在多个进程启动 Worker 吗？

使用 JDBC 或 Redis Store 时可以。每个 `workerId` 必须唯一，`leaseMillis` 要覆盖一次 Handler 调用和结果保存。内存 Store 不能跨进程共享。
