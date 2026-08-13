# 异步任务模块

`agents-flex-async-task` 用于持久化跟踪 OCR、视频等供应商异步任务。它把一次长任务拆成“提交一次、查询多次”，并统一处理任务状态、重试、截止时间、租约、并发竞争和取消。

## 适合解决什么问题

当供应商任务需要几十秒甚至几分钟时，业务系统通常要解决以下问题：

- HTTP 请求结束后，后台仍能继续查询结果。
- 应用重启或 Worker 崩溃后，其他实例能够接管。
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
- 使用 `enqueue()` 时的提交参数、优先级和隔离维度

## 架构

```text
业务代码
   │ submit() / enqueue()
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
| `AsyncTaskManager` | 创建、查询和取消框架任务 |
| `AsyncTaskHandler` | 将供应商 API 转换为统一的提交和单次查询 |
| `AsyncTaskWorker` | 扫描到期任务，执行提交或查询，应用重试策略 |
| `AsyncTaskStore` | 保存快照、调度索引、CAS 版本和 Worker 租约 |
| `AsyncTaskRetryPolicy` | 决定正常查询间隔和异常退避 |
| `AsyncTaskAdmissionPolicy` | 控制排队提交的 QPS、并发、配额和暂停 |

## 两种提交方式

| 能力 | `submit()` | `enqueue()` |
| --- | --- | --- |
| 供应商调用时机 | 当前调用线程立即执行 | Worker 领取后执行 |
| 提交参数持久化 | 否 | 是 |
| 供应商 QPS / 账号并发 / 租户配额 | 不适用 | 支持 |
| 优先级 / 延迟提交 | 不支持 | 支持 |
| 参数要求 | Handler 所需类型 | Handler 所需类型且实现 `Serializable` |
| 适合场景 | 已在业务入口限流、希望立即拿到供应商 ID | 需要可靠队列和统一准入控制 |

`submit()` 仍会先保存 `SUBMITTING` 快照，再调用供应商；只是不会持久化提交 payload。供应商返回后，框架保存 `TaskQueryParams` 并由 Worker 继续查询。

`enqueue()` 会先保存 payload，供应商尚未被调用。Worker 只有在计划时间到达且准入策略允许时才提交。

> 内置 `OcrAsyncTaskHandler` 接受 `OcrRequest`，内置 `VideoAsyncTaskHandler` 接受 `GenerateVideoRequest`。这两个请求类型当前不实现 `Serializable`，因此可以直接用于 `submit()`，不能直接用于 `enqueue()`。排队提交时应定义可序列化命令 DTO，并实现接受该 DTO 的 Handler。

## 生命周期

```text
enqueue(): PENDING_SUBMIT → SUBMITTING ┐
                                       ├→ SUBMITTED → RUNNING → SUCCEEDED
submit():             SUBMITTING ──────┘                         ├→ FAILED
                                                                  ├→ CANCELED
                                                                  └→ TRACKING_TIMED_OUT

提交调用结果不确定：SUBMIT_UNKNOWN
```

`SUBMIT_UNKNOWN` 表示网络异常等情况使框架无法判断供应商是否已创建任务。框架不会自动重新提交，以免重复计费。Handler 应在供应商支持时传递 `TaskSubmitContext.getIdempotencyKey()`。

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
