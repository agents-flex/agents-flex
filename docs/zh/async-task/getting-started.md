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

final class AsyncTaskHandlerKeys {
    private AsyncTaskHandlerKeys() {
    }

    // Handler Key 会写入 Store，应作为持久化协议集中管理。
    static final String OCR_GITEE = "ocr:gitee";
}

GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
OcrModel ocrModel = new GiteeOcrModel(config);

InMemoryAsyncTaskHandlerRegistry registry =
    new InMemoryAsyncTaskHandlerRegistry();
registry.register(new OcrAsyncTaskHandler(
    AsyncTaskHandlerKeys.OCR_GITEE,
    ocrModel
));
```

`ocr:gitee` 是持久化在任务中的稳定键。集中定义常量可以避免注册和提交时拼写不一致。更改键后，旧任务
将无法找到对应 Handler，因此它不应作为可以随意调整的配置项。

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
    AsyncTaskHandlerKeys.OCR_GITEE,
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

## 生产最佳实践

快速开始中的内存 Store、固定 Worker ID 和直接字符串适合展示调用流程。生产系统还需要把 Handler Key、
持久化兼容性和运行参数当作正式协议管理。

### 1. 将 Handler Key 作为持久化协议管理

任务只保存 `handlerKey`，Worker 恢复任务时依靠这个值从 Registry 找到 Handler。因此 Key 不是临时 Bean
名称，也不只是日志标签，而是 Store 数据与运行时代码之间的长期契约。

推荐使用小写、ASCII、冒号分段的格式：

```text
<capability>:<provider>[:<variant>]

ocr:gitee
ocr:baidu
video:aliyun:wanx
document:internal:archive
```

在独立常量类或模块级常量中集中定义，并让注册、提交、监控和测试共同引用：

```java
public final class AsyncTaskHandlerKeys {
    public static final String OCR_GITEE = "ocr:gitee";
    public static final String OCR_BAIDU = "ocr:baidu";
    public static final String VIDEO_ALIYUN_WANX =
        "video:aliyun:wanx";

    private AsyncTaskHandlerKeys() {
    }
}
```

设计 Key 时遵循以下规则：

- Key 表达稳定的能力和协议路由，不放租户 ID、请求 ID、部署环境或 API Key。
- 账号与租户分别使用 `accountId`、`tenantId`；凭证由 Handler 根据安全的账号标识从运行时配置中加载。
- 只有两个 Handler 的提交参数、查询协议或结果语义确实不同，才增加 `variant`。
- 不要把普通代码版本写入 Key。能够兼容历史任务时，保持原 Key 并升级 Handler 实现。
- 所有可能领取同一 Store 任务的 Worker，都必须注册相同 Key 及兼容实现。

#### Handler 升级与 Key 迁移

修改 Handler 时，首先保证旧的 `TaskQueryParams`、metadata 和排队 DTO 仍能反序列化和查询。若发生无法
向后兼容的协议变更，不能直接把旧 Key 改名后上线，建议采用并行迁移：

1. 为新协议新增 Key，例如 `ocr:gitee:v2`，旧 Key 继续注册旧 Handler。
2. 新提交只使用新 Key，旧 Worker 仍能完成 Store 中已有任务。
3. 观察旧 Key 的非终态任务数量，等待其归零或按业务规则迁移。
4. 确认没有历史任务需要恢复后，再移除旧 Handler。

蓝绿发布或滚动发布期间，新旧应用版本会同时运行。此时它们对同一个 Key 的提交 DTO、查询参数和结果
类型必须兼容，否则旧实例可能领取新任务，新实例也可能领取旧任务。

### 2. 根据治理需求选择 `submit()` 或 `enqueue()`

| 场景 | 建议 |
| --- | --- |
| 调用入口已经限流，需要立即取得供应商任务 ID | 使用 `submit()` |
| 需要供应商 QPS、账号并发或租户配额 | 使用 `enqueue()` |
| 需要优先级、延迟提交或暂停供应商 | 使用 `enqueue()` |
| 提交参数无法安全序列化 | 使用 `submit()`，或设计专用的可序列化命令 DTO |

`submit()` 会保存任务状态和供应商查询参数，但不保存提交 payload；`enqueue()` 会先持久化命令，等 Worker
获得准入后再访问供应商。不要仅为了复用现有请求对象就把文件流、HTTP Client 或凭证放入排队 DTO。

### 3. 让供应商提交具备幂等性

Handler 的 `submit()` 应尽量把 `TaskSubmitContext.getIdempotencyKey()` 传给支持幂等键的供应商。该值默认
使用框架任务 ID，可以降低网络响应丢失时重复创建、重复计费的风险。

任务进入 `SUBMIT_UNKNOWN` 后，不要自动重新调用 `submit()`。应先使用供应商控制台、业务请求标识或
幂等查询能力核实远端状态，再由人工或明确的补偿流程决定是否重提。

### 4. 生产环境使用共享 Store

需要跨重启恢复或运行多个 Worker 时，使用 JDBC 或 Redis Store，不使用 `InMemoryAsyncTaskStore`。

- 数据库表结构由 Flyway 或 Liquibase 管理，避免生产应用账号持有 DDL 权限。
- `enqueue()` 的业务 DTO 需要实现 `Serializable`，并加入最小范围的反序列化白名单。
- Store 中的 metadata、查询参数、结果和错误信息都可能包含业务数据，需要配置访问控制、加密、备份和保留周期。
- 上线前应使用真实 Store 做一次“提交后重启，再继续查询”的恢复测试。

具体配置参见 [Store 持久化](./store)。

### 5. 正确配置 Worker 身份与租约

每个运行实例的 `workerId` 必须全局唯一，可以使用平台注入的实例 ID，而不是所有节点都写死为
`worker-1`：

```java
String instanceId = System.getenv("APP_INSTANCE_ID");
if (instanceId == null || instanceId.trim().isEmpty()) {
    throw new IllegalStateException("APP_INSTANCE_ID is required");
}

AsyncTaskWorker worker = new AsyncTaskWorker(
    "async-task-" + instanceId,
    store,
    registry,
    retryPolicy,
    60_000L
);
```

`leaseMillis` 应大于一次最慢供应商 HTTP 调用加 Store 保存的时间，并留出网络抖动余量。当前内置 Worker
不会在单次 Handler 调用期间自动续租；超长调用需要增大租约，或由自定义执行器调用 Store 的续租能力。
同时为供应商 HTTP Client 设置略短于租约的连接和读取超时，避免请求长期占有过期租约。

### 6. 合理设置跟踪截止时间和重试

`trackingTimeoutMillis` 是从任务创建开始计算的总时限。对 `enqueue()` 而言，它同时包含本地排队时间和
供应商处理时间，因此不能只按供应商平均耗时设置。

建议根据“最大可接受排队时间 + 供应商 P99 处理时间 + 异常退避余量”确定截止时间。正常查询间隔不宜
过短，应遵守供应商建议并加入退避；网络异常交给 `AsyncTaskRetryPolicy`，不要在 Handler 内循环、休眠或
无限重试。

### 7. 区分框架任务 ID 与供应商任务 ID

业务 API 应向调用方暴露 `AsyncTask.id`，再通过 `manager.get(taskId)` 查询。供应商 `externalTaskId` 及
区域、批次、游标等信息由 `TaskQueryParams` 持久化并留在 Handler 边界内。这样可以避免客户端绕过租户
鉴权、选择错误 Provider，或将供应商路由细节耦合到业务协议。

查询任务前仍需校验当前用户或租户是否有权访问该框架任务 ID；任务 ID 本身不是访问凭证。

### 8. 控制持久化数据和结果的生命周期

不要把 API Key、Token、打开的流、HTTP Client 或不可序列化对象放入 payload、metadata、
`TaskQueryParams` 和 result。持久化字段只保存恢复任务真正需要的数据，账号字段保存内部账号标识，不保存
真实凭证。

OCR、视频供应商返回的下载 URL 可能很快过期。任务成功后应及时把文件转存到自己的对象存储，再向业务
返回稳定资源地址。终态任务不会由框架自动删除，生产环境还应根据审计与隐私要求制定归档、脱敏和清理
策略。

### 上线检查清单

- Handler Key 已集中定义，所有 Worker 注册一致，历史 Key 没有被直接删除。
- 已明确选择 `submit()` 或 `enqueue()`，排队 DTO 可序列化且兼容滚动发布。
- 供应商支持幂等时已传递框架幂等键，并为 `SUBMIT_UNKNOWN` 设计人工或补偿流程。
- 使用 JDBC 或 Redis Store，并完成服务重启和多 Worker 竞争测试。
- `workerId` 全局唯一，HTTP 超时、租约、查询间隔和跟踪截止时间相互匹配。
- API 只暴露框架任务 ID，并执行租户或用户级访问校验。
- 凭证未进入持久化数据，供应商临时结果已规划转存和清理策略。
- 应用关闭时调用 `worker.close()`，Redis Store 使用自建客户端时也正确关闭相应资源。

## 常见问题

### 为什么 `submit()` 之后还需要 Worker？

`submit()` 只立即完成供应商任务创建。供应商返回 `taskId` 后，Worker 才会按计划调用 Handler `query()`，直到成功、失败或超时。

### `trackingTimeoutMillis` 是单次 HTTP 超时吗？

不是。它是框架从任务创建到停止自动跟踪的总时限。供应商 HTTP Client 的连接和读取超时应单独配置。

### 可以在多个进程启动 Worker 吗？

使用 JDBC 或 Redis Store 时可以。每个 `workerId` 必须唯一，`leaseMillis` 要覆盖一次 Handler 调用和结果保存。内存 Store 不能跨进程共享。
