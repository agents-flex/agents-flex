# 异步任务模块

`agents-flex-async-task` 用于持久化跟踪 OCR、视频等供应商异步任务。它把一次长任务拆成“提交一次、查询多次”，并统一处理任务状态、重试、截止时间、租约、并发竞争和取消。

## 适合解决什么问题

当供应商任务需要几十秒甚至几分钟时，业务系统通常要解决以下问题：

- HTTP 请求结束后，后台仍能继续查询结果。
- 供应商任务已经返回查询参数后，应用重启或 Worker 崩溃不丢失查询进度。
- 多个 Worker 不会同时查询或提交同一个任务。
- 查询异常可以退避重试，但不会无限执行。
- 批量提交时能够限流、隔离租户并安排优先级。

Async Task 是应用内的异步任务跟踪与调度组件，不是通用消息队列，也不提供工作流编排、Cron 或远端任务强制取消。

## 为什么需要独立模块

直接调用 `recognizeAndWait()` 或 `generateAndWait()` 会占用当前线程，且应用重启后无法恢复。只保存供应商 `taskId` 又不足以覆盖区域、账号、批次、查询 URL、重试次数和截止时间等信息。

异步任务模块会持久化：

- Handler 注册键和统一任务状态
- `TaskQueryParams`：供应商任务 ID 及附加查询参数
- 查询结果、错误、次数和下一次查询时间
- 创建时间、跟踪截止时间和业务元数据（metadata）
- Worker 租约、fencing token 和 CAS 版本
- 使用 `submit()` 时的提交参数、优先级和隔离维度

## 架构

```text
业务代码
   │ submit()
   ▼
AsyncTaskManager ───────► AsyncTaskStore
                              ▲
                              │ 领取、CAS 保存、租约
AsyncTaskHandler ◄──── AsyncTaskWorker
   │ submit/query
   ▼
OCR、视频或自定义供应商 API
```

| 组件 | 职责 |
| --- | --- |
| `AsyncTaskManager` | 按请求类型选择 Handler，创建、查询和取消框架任务 |
| `AsyncTaskHandler` | 将供应商 API 转换为统一的提交和单次查询 |
| `AsyncTaskWorker` | 扫描到期任务，执行提交或查询，应用重试策略 |
| `AsyncTaskStore` | 保存快照、调度索引、CAS 版本和 Worker 租约 |
| `AsyncTaskRetryPolicy` | 决定正常查询间隔和异常退避 |
| `AsyncTaskAdmissionPolicy` | 控制排队提交的 QPS、并发、配额和暂停 |

## 统一提交模型

Manager 只提供一种 `submit()`：先验证并持久化参数，再返回状态为 `PENDING_SUBMIT` 的框架任务。Manager
不会在调用线程访问供应商；Worker 领取任务后才调用 Handler `submit()`，因此所有任务都能统一使用 QPS、
账号并发、租户配额、优先级、延迟提交和供应商暂停。

提交时无需传入 Handler Key。Manager 按请求的精确类型查找候选：唯一候选直接使用；多个候选由 Manager
配置的 `AsyncTaskHandlerSelector` 选择；`AsyncTaskOptions.handlerKey` 可以为单次请求强制路由。最终 key
会写入任务，Worker 后续处理只使用持久化结果，不会再次选择。

Handler 的提交参数类型默认从 `AsyncTaskHandler<P>` 泛型声明解析并按实现类缓存。直接实现、泛型子接口和
多层泛型继承通常不需要重复返回 `XxxRequest.class`；如果代理或未绑定类型变量导致类型无法确定，Handler
必须显式覆盖 `getSubmitParamsType()`，注册时会立即校验失败，不会退化成 `Object.class`。

提交参数必须能够持久化并在其他 Worker 中恢复。内置 OCR Handler 只接受 URL 输入；视频的图片和源视频
素材也必须使用远程 URL。`File`、`InputStream` 和 `byte[]` 等进程本地或大块二进制输入会在写入 Store
前抛出异常，调用方应先上传文件，再提交 Worker 和供应商能够访问且有效期足够长的 URL。

## 生命周期

```text
submit(): PENDING_SUBMIT → SUBMITTING → SUBMITTED → RUNNING → SUCCEEDED
             ├──────────────┴──────────────┴──────────────→ FAILED
             ├──────────────┴──────────────┴──────────────→ CANCELED
             └──────────────┴──────────────┴──────────────→ TRACKING_TIMED_OUT

提交调用结果不确定：SUBMIT_UNKNOWN
```

`SUBMIT_UNKNOWN` 表示网络异常等情况使框架无法判断供应商是否已创建任务。框架不会自动重新提交，以免重复计费。Handler 应在供应商支持时传递 `TaskSubmitContext.getIdempotencyKey()`。

租约只能防止过期 Worker 覆盖新结果，不能让一次非幂等的供应商提交天然具备 exactly-once 语义。Worker
在 `SUBMITTING` 阶段异常退出时，本地无法判断远端是否已经创建任务；生产系统应配置长于 HTTP 超时的
租约、传递供应商幂等键，并监控长期停留在 `SUBMITTING` 的任务，按 `SUBMIT_UNKNOWN` 场景人工核查。

## 任务 ID 的区别

框架中同时存在两个 ID：

| ID | 来源 | 用途 |
| --- | --- | --- |
| `AsyncTask.id` | Manager 创建 | 业务查询、取消和 Store 主键 |
| `TaskQueryParams.externalTaskId` | 供应商返回 | Handler 查询远端任务 |

业务接口通常向客户端暴露 `AsyncTask.id`。供应商 ID 由 Handler 和 Store 管理，不应让客户端直接决定查询路由。

## 何时不需要本模块

- 供应商接口同步返回最终结果。
- 只有少量命令行或测试任务，可以接受 `recognizeAndWait()`。
- 企业已经使用成熟的工作流平台统一管理提交、重试和状态。

这些情况下直接使用模型 API 可能更简单，不需要额外引入 Worker 和 Store。

## 内置适配器

```java
registry.register(new OcrAsyncTaskHandler("ocr:gitee", ocrModel));
registry.register(new VideoAsyncTaskHandler("video:aliyun", videoModel));
```

Handler 的注册键必须在部署期间保持稳定，因为 Store 中的历史任务依靠该键恢复。

## 下一步

- [快速开始](./getting-started)
- [调度与准入控制](./scheduling)
- [Store 持久化](./store)
- [自定义 Handler](./handler)
