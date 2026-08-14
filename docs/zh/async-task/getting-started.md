# Async Task 概述与快速开始

`agents-flex-async-task` 用于可靠地执行和跟踪 OCR、视频生成等供应商异步任务。它把供应商常见的“提交一次、查询多次”协议转换为可持久化、可恢复、可调度的统一任务生命周期。

## 1. 为什么需要 Async Task

OCR、视频生成等接口通常不会在一次请求内返回最终结果。供应商先接收任务并返回一个任务 ID，调用方随后需要反复查询，直到成功或失败：

```text
提交请求 ──► 供应商任务 ID ──► 查询 ──► 查询 ──► 最终结果
```

直接在业务请求线程中循环查询看似简单，但很快会遇到实际问题：

- 任务可能运行几十秒或几分钟，HTTP 请求和应用线程不能一直等待。
- 应用重启、发布或 Worker 崩溃后，内存中的任务 ID、查询次数和下次查询时间会丢失。
- 多个应用实例可能同时查询或提交同一个任务，造成重复调用甚至重复计费。
- 查询接口可能暂时超时或限流，需要退避重试，但不能无限重试。
- 大量任务同时到来时，需要控制供应商 QPS、账号并发、租户额度和提交顺序。
- 用户取消或业务超时后，系统应停止继续跟踪，而不是永久轮询。

`recognizeAndWait()`、`generateAndWait()` 适合命令行、测试或少量同步任务，但它们无法提供跨请求、跨重启的任务管理。Async Task 解决的是这部分运行时问题。

## 2. 它解决了什么问题

Async Task 将一次长任务拆成两个很短的供应商操作：

1. `Handler.submit()`：只创建一次供应商任务；
2. `Handler.query()`：只查询一次供应商状态。

循环、持久化、调度和错误处理由框架统一负责：

| 问题 | 框架的处理方式 |
| --- | --- |
| 业务请求不能长时间阻塞 | `manager.submit()` 保存任务后立即返回框架任务 ID |
| 应用重启后继续跟踪 | Store 保存提交参数、供应商查询参数、状态、截止时间和结果 |
| 多 Worker 重复处理 | Store 使用租约、fencing token 和 CAS 版本协调领取与保存 |
| 查询临时失败 | Worker 通过 `AsyncTaskRetryPolicy` 安排下一次退避查询 |
| 任务无限运行 | 从创建时刻计算统一的跟踪截止时间 |
| 提交流量失控 | 可选准入策略控制 QPS、账号并发、租户配额、优先级和暂停 |
| 供应商差异 | `AsyncTaskHandler` 将不同供应商状态映射为统一生命周期 |

它不是通用消息队列，也不负责工作流编排、Cron 调度或强制取消供应商远端任务。它专注于可靠跟踪具有“提交 + 查询”协议的外部异步任务。

## 3. 它如何工作

```text
业务接口
   │ manager.submit(params)
   ▼
AsyncTaskManager ───────► AsyncTaskStore
                              ▲
                              │ 领取任务、保存状态、租约与 CAS
                              │
AsyncTaskHandler ◄──── AsyncTaskWorker
   │ submit/query
   ▼
OCR、视频或其他供应商 API
```

四个核心组件各自只负责一件事：

| 组件 | 职责 |
| --- | --- |
| `AsyncTaskManager` | 校验提交参数、选择 Handler、创建任务、读取任务和请求取消 |
| `AsyncTaskStore` | 保存任务快照，并为多个 Worker 提供到期扫描、租约和 CAS 协调 |
| `AsyncTaskWorker` | 领取到期任务，调用 Handler 提交或查询，并应用重试和截止时间 |
| `AsyncTaskHandler` | 把具体供应商适配为一次 `submit()` 和一次 `query()` |

调用 `manager.submit()` 时，Manager **不会访问供应商**。它先把参数写入 Store，创建状态为 `PENDING_SUBMIT` 的框架任务并立即返回。后台 Worker 领取任务后才向供应商提交，然后按计划查询结果。

因此，只有 Manager 而没有 Worker，任务会被保存，但不会继续向前执行；只有 Worker 而没有可靠 Store，任务也无法在重启后恢复。

## 4. 任务生命周期

典型成功路径如下：

```text
PENDING_SUBMIT → SUBMITTING → SUBMITTED → RUNNING → SUCCEEDED
```

| 状态 | 含义 |
| --- | --- |
| `PENDING_SUBMIT` | 任务已写入 Store，尚未调用供应商 |
| `SUBMITTING` | Worker 已领取任务，正在创建供应商任务 |
| `SUBMITTED` | 供应商已接受任务并返回查询标识 |
| `RUNNING` | 供应商正在排队或处理，Worker 将继续查询 |
| `SUCCEEDED` | 任务成功，`result` 中保存最终业务结果 |
| `FAILED` | 供应商明确失败，或查询异常超过重试上限 |
| `CANCELED` | 框架停止本地跟踪，不保证远端任务已经取消 |
| `TRACKING_TIMED_OUT` | 超过框架跟踪截止时间，远端任务仍可能运行 |
| `SUBMIT_UNKNOWN` | 提交发生异常，无法确认供应商是否已经创建任务 |

`SUBMIT_UNKNOWN` 是一个重要的保护状态。网络超时可能发生在供应商已经创建任务、但响应没有返回本地之后。框架不会盲目自动重提，以免创建重复任务或重复计费。

## 5. 快速开始：异步执行 OCR

下面使用 Gitee OCR 和内存 Store 跑通完整流程。内存 Store 便于理解 API，但 JVM 退出后数据会丢失，不能用于需要恢复能力的生产任务。

### 5.1 添加依赖

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

### 5.2 创建 OCR 模型并注册 Handler

Handler 是供应商协议与统一任务生命周期之间的适配器。注册键会持久化到 Store，用于 Worker 在后续查询或服务重启后找到同一个实现，因此必须保持稳定。

```java
import com.agentsflex.asynctask.handler.InMemoryAsyncTaskHandlerRegistry;
import com.agentsflex.asynctask.handler.OcrAsyncTaskHandler;
import com.agentsflex.core.model.ocr.OcrModel;
import com.agentsflex.ocr.gitee.GiteeOcrConfig;
import com.agentsflex.ocr.gitee.GiteeOcrModel;

GiteeOcrConfig ocrConfig = new GiteeOcrConfig();
ocrConfig.setApiKey(System.getenv("GITEE_API_KEY"));

OcrModel ocrModel = new GiteeOcrModel(ocrConfig);

InMemoryAsyncTaskHandlerRegistry registry =
    new InMemoryAsyncTaskHandlerRegistry();
registry.register(new OcrAsyncTaskHandler("ocr:gitee", ocrModel));
```

`ocr:gitee` 不应使用随机值、实例 ID 或临时 Bean 名称。所有可能处理同一批历史任务的 Worker 都必须注册兼容的 Handler。

### 5.3 创建 Store 和 Manager

```java
import com.agentsflex.asynctask.AsyncTaskManager;
import com.agentsflex.asynctask.store.AsyncTaskStore;
import com.agentsflex.asynctask.store.InMemoryAsyncTaskStore;

AsyncTaskStore store = new InMemoryAsyncTaskStore();
AsyncTaskManager manager = new AsyncTaskManager(store, registry);
```

Manager 是业务代码的主要入口。它负责创建、查询和取消框架任务，但不启动后台线程，也不直接调用 OCR 模型。

### 5.4 创建并启动 Worker

```java
import com.agentsflex.asynctask.AsyncTaskWorker;
import com.agentsflex.asynctask.policy.ExponentialAsyncTaskRetryPolicy;

ExponentialAsyncTaskRetryPolicy retryPolicy =
    new ExponentialAsyncTaskRetryPolicy(
        3_000L,   // 正常处理中，每 3 秒查询一次
        1_000L,   // 第一次查询异常后等待 1 秒
        60_000L,  // 异常退避最长 60 秒
        5         // 最多容忍 5 次连续查询异常
    );

AsyncTaskWorker worker = new AsyncTaskWorker(
    "worker-1", // 每个运行实例必须唯一
    store,
    registry,
    retryPolicy,
    30_000L     // 一次 Handler 调用和保存结果所使用的租约时长
);

worker.start(
    1_000L, // 每轮扫描完成后等待 1 秒
    10      // 每轮提交阶段和查询阶段各最多领取 10 个任务
);
```

Worker 是应用级长生命周期组件，通常在应用启动时创建并调用一次 `start()`，在应用关闭时调用 `close()`。不要为每个业务任务创建一个 Worker。

当前内置 Worker 使用单线程依次处理本轮任务，`batchSize` 是每轮最多领取数量，不是并发线程数。`leaseMillis` 应大于一次最慢供应商 HTTP 调用加 Store 保存所需时间。

### 5.5 提交任务

```java
import com.agentsflex.asynctask.AsyncTask;
import com.agentsflex.core.model.ocr.OcrRequest;

OcrRequest request = OcrRequest.ofUrl(
    "https://files.example.com/report.pdf"
);

AsyncTask task = manager.submit(
    request,
    30 * 60_000L // 从创建开始，最多跟踪 30 分钟
);

System.out.println("framework task id: " + task.getId());
System.out.println("current status: " + task.getStatus());
```

`submit()` 返回时通常是 `PENDING_SUBMIT`，供应商还没有收到请求。业务接口应该保存并返回 `task.getId()`，让客户端通过独立接口查询状态，而不是等待 OCR 完成。

这里存在两个不同的 ID：

| ID | 用途 |
| --- | --- |
| `AsyncTask.id` | 框架任务 ID，供业务查询、取消和鉴权 |
| `TaskQueryParams.externalTaskId` | 供应商任务 ID，只在 Handler 和 Worker 内部使用 |

### 5.6 查询结果

`manager.get()` 读取当前快照，不会阻塞：

```java
import com.agentsflex.asynctask.AsyncTaskStatus;
import com.agentsflex.core.model.ocr.OcrResponse;

AsyncTask latest = manager.get(task.getId());

if (latest == null) {
    throw new IllegalStateException("Task not found");
}

if (latest.getStatus() == AsyncTaskStatus.SUCCEEDED) {
    OcrResponse response = (OcrResponse) latest.getResult();
    System.out.println(response.getMarkdown());
} else if (latest.getStatus().isTerminal()) {
    System.err.println(
        latest.getStatus() + ": " + latest.getErrorMessage()
    );
} else {
    System.out.println("Task is still running: " + latest.getStatus());
}
```

真实 Web 应用通常提供两个接口：

```text
POST /ocr-tasks       创建任务，立即返回 AsyncTask.id
GET  /ocr-tasks/{id}  读取当前状态和结果
```

查询接口必须校验当前用户或租户是否有权访问该任务。框架任务 ID 是定位信息，不是访问凭证。

### 5.7 本地演示中等待终态

为了在命令行示例中观察状态变化，可以有限期轮询 Store。该写法只用于演示，不应放在 Web 请求线程里：

```java
long waitDeadline = System.currentTimeMillis() + 60_000L;
AsyncTask latest;

do {
    latest = manager.get(task.getId());
    System.out.println("status: " + latest.getStatus());

    if (latest.getStatus().isTerminal()) {
        break;
    }
    Thread.sleep(1_000L);
} while (System.currentTimeMillis() < waitDeadline);
```

演示等待时间和任务的 `trackingTimeoutMillis` 是两件事：前者只是当前代码愿意等待多久；后者决定 Worker 何时停止自动跟踪任务。

### 5.8 关闭 Worker

```java
worker.close();
```

应在应用关闭阶段执行，而不是每次提交后执行。关闭 Worker 只停止当前进程的扫描线程，不会删除 Store 中的任务。

## 6. 必须使用可持久化输入

异步任务可能由另一个进程处理，也可能在服务重启后恢复，因此 payload 不能依赖当前 JVM 的本地状态。

持久化 OCR 任务只接受供应商和 Worker 都能访问、且有效期足够长的 URL：

```java
OcrRequest request = OcrRequest.ofUrl(fileUrl);
```

以下输入会在写入 Store 前被拒绝：

- `File`、`InputStream`、`OutputStream`、`Reader`、`Writer`；
- `byte[]` 以及对象内部嵌套的上述类型；
- 未实现 `Serializable` 的提交参数或 metadata。

本地文件应先上传到对象存储或文件服务，再提交 URL。不要把 API Key、Token、HTTP Client 或大块二进制放入 payload 和 metadata；Handler 应根据稳定的账号标识从运行时安全配置中加载凭证。

## 7. 取消和超时的真实含义

请求取消：

```java
boolean accepted = manager.cancel(task.getId());
```

取消标记会被持久化，Worker 在提交或查询前后合并该标记，并把任务转为 `CANCELED`。这表示框架停止本地跟踪，不代表供应商远端任务一定已经停止。

提交时的 `trackingTimeoutMillis` 从框架任务创建时开始计算，包含：

- 在 `PENDING_SUBMIT` 中排队的时间；
- Worker 调用供应商提交的时间；
- 供应商处理和框架查询的时间；
- 查询异常的退避等待时间。

达到截止时间后任务进入 `TRACKING_TIMED_OUT`，但远端任务仍可能继续运行。它不是单次 HTTP 请求超时；连接和读取超时应在具体供应商客户端中单独设置。

## 8. 从本地示例走向生产

最小示例只展示运行链路。生产环境至少需要完成以下调整：

1. 使用 [JDBC 或 Redis Store](./store) 替换 `InMemoryAsyncTaskStore`，确保任务可以跨重启恢复。
2. 为每个 Worker 使用全局唯一的 `workerId`，并让 `leaseMillis` 覆盖最慢的一次 Handler 调用。
3. 根据供应商限制配置 [QPS、账号并发、租户配额、优先级和暂停策略](./scheduling)。
4. 供应商支持幂等键时，自定义 Handler 应传递 `TaskSubmitContext.getIdempotencyKey()`。
5. 监控长期停留在 `PENDING_SUBMIT`、`SUBMITTING`、`SUBMITTED` 和 `RUNNING` 的任务。
6. 为 `SUBMIT_UNKNOWN` 建立核查和补偿流程，不要默认自动重新提交。
7. 及时转存供应商返回的临时结果 URL，并为终态任务制定归档和清理策略。

JDBC 和 Redis Store 能够原子领取任务并防止旧 Worker 覆盖新结果，但不会自动让进程内 QPS 或配额策略变成集群全局限流。严格的多实例配额需要共享的 `AsyncTaskAdmissionPolicy`，或保证同一治理维度只有一个提交 Worker。

## 9. 什么时候不需要 Async Task

以下情况直接使用模型 API 通常更简单：

- 供应商同步返回最终结果；
- 只有少量命令行或测试任务，可以接受当前线程等待；
- 已经有成熟的消息队列或工作流平台统一处理持久化、重试和状态；
- 业务不需要跨请求查询、取消或跨重启恢复。

如果任务需要离开当前请求继续执行，并且必须可靠知道“排队、提交、处理中、成功或失败”，Async Task 才是合适的抽象。

## 10. 下一步

- [异步任务模块概览](./overview)
- [调度与准入控制](./scheduling)
- [Store 持久化](./store)
- [自定义 AsyncTaskHandler](./handler)
