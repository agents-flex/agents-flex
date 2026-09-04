---
title: AgentEventListener
description: 监听 Agent 的运行状态和执行进度，用于页面展示、日志、指标、告警和业务审计。
---

# AgentEventListener

## 概述

Agent 执行一项任务时，可能需要多次调用模型和工具，也可能等待审批、安排重试或在后台继续运行。如果业务
系统只能在最后拿到一个结果，就很难回答下面这些问题：

- 页面怎样显示“正在思考”“正在查询订单”或“报告已生成”？
- 任务长时间没有结束，究竟是在执行、等待输入，还是已经失败？
- 某个工具调用失败时，怎样记录日志并发送告警？
- 怎样统计任务完成量、失败量和工具使用情况？
- 需要审计时，怎样知道一项任务经历过哪些关键动作？

`AgentEventListener` 用来解决这些“了解运行过程”的问题。`AgentRunner` 在任务经过关键节点时发布事件，
业务系统通过 Listener 接收通知，然后更新页面、写日志、记录指标或转发到自己的消息系统。

```text
AgentRunner 执行任务
         ↓ 发布事件
AgentEventListener 接收通知
         ↓
页面进度 / 日志 / 指标 / 告警 / 审计
```

事件表示一件事情**已经发生**。监听器可以观察运行过程，但不能通过返回值改变 Agent 的执行结果。需要在
模型或工具执行前修改参数、阻止调用或替换结果时，应使用 [Middleware](./middleware)。

## 何时需要使用

| 需求 | 可以监听的事件 |
| --- | --- |
| 向页面推送模型生成的文字 | `MODEL_TEXT_DELTA` |
| 显示长时间工具的处理进度 | `TOOL_PROGRESS` |
| 在任务结束后通知用户 | `TURN_COMPLETED` |
| 记录失败并触发告警 | `TURN_FAILED`、`TOOL_FAILED` |
| 提醒用户完成审批或表单 | `TOOL_APPROVAL_REQUESTED`、`TOOL_INPUT_REQUESTED` |
| 统计模型和工具调用次数 | `MODEL_STARTED`、`TOOL_STARTED` |
| 观察任务是否进入自动重试 | `RETRY_SCHEDULED` |

如果应用只关心最终回答，可以直接读取 `AgentTurn`，不一定要注册监听器。事件更适合关注执行过程或把关键
变化交给其他系统处理。

## 快速开始

### 1. 注册监听器

创建 `AgentRunner` 后，通过 `addEventListener(...)` 注册监听器：

```java
AgentEventListener listener = event -> {
    if (event.getType() == AgentEventType.TURN_STARTED) {
        System.out.println("任务开始：" + event.getTurnId());
    }

    if (event.getType() == AgentEventType.TURN_COMPLETED) {
        System.out.println("任务完成：" + event.getTurnId());
    }

    if (event.getType() == AgentEventType.TURN_FAILED) {
        System.out.println("任务失败：" + event.getData().get("error"));
    }
};

AgentRunner runner = new AgentRunner()
    .addEventListener(listener);
```

之后通过这个 Runner 执行的任务，都会把事件发送给该监听器：

```java
AgentTurn turn = runner.run(agent, "生成本月销售报告");
```

一个 Runner 可以注册多个监听器。例如，一个负责页面通知，一个负责日志，另一个负责统计指标。

### 2. 读取事件信息

监听器收到的 `AgentEvent` 包含事件类型、任务 ID 和相关数据：

```java
String eventId = event.getEventId();
String turnId = event.getTurnId();
AgentEventType type = event.getType();
long occurredAt = event.getOccurredAt();
Map<String, Object> data = event.getData();
```

| 字段 | 说明 |
| --- | --- |
| `eventId` | 当前事件的唯一 ID，可用于日志关联或业务去重 |
| `turnId` | 产生事件的任务 ID |
| `agentId`、`agentVersion` | 任务使用的 Agent 及其版本 |
| `type` | 发生了什么，例如任务完成或工具失败 |
| `occurredAt` | 事件发生时间，使用毫秒时间戳 |
| `sequence` | 当前 Runner 内，同一任务的事件序号 |
| `data` | 与事件类型相关的补充信息 |

所有事件的 `data` 都会包含任务状态、当前步骤等基础信息。不同事件还会增加自己的字段，例如：

- `MODEL_TEXT_DELTA` 的 `content`：模型本次生成的文字；
- `TOOL_STARTED` 的 `toolName`：准备执行的工具名称；
- `TOOL_PROGRESS` 的 `message`：工具上报的进度说明；
- `TURN_FAILED` 的 `error`：失败原因；
- `RETRY_SCHEDULED` 的 `nextRunnableAt`：下次可以重试的时间。

`data` 是只读数据。业务代码应根据事件类型读取需要的字段，并处理字段不存在的情况。

### 3. 移除监听器

不再需要某个监听器时，可以移除之前注册的同一个实例：

```java
runner.removeEventListener(listener);
```

移除后，该监听器不会再接收后续事件。

## 向页面展示实时进度

事件可以配合 WebSocket 或 SSE（两种由服务器主动向页面推送数据的方式），把执行进度发送给前端。例如，
监听模型生成的文字：

```java
runner.addEventListener(event -> {
    if (event.getType() == AgentEventType.MODEL_TEXT_DELTA) {
        Object content = event.getData().get("content");
        uiPublisher.send(event.getTurnId(), String.valueOf(content));
    }
});
```

这里的 `uiPublisher` 代表业务系统自己的 WebSocket 或 SSE 推送组件，不是 Agents-Flex 内置类。

事件适合提供实时体验，但不能作为页面状态的唯一来源。浏览器断线、应用重启或消息发送失败时，前端可能
错过部分事件。重新连接后，应根据 `turnId` 从 `AgentTurnStore` 查询任务的最新状态。

## 上报工具执行进度

上传文件、生成报告等工具可能运行较长时间。工具内部可以通过 `AgentToolContext` 主动上报进度：

```java
AgentToolContext context = AgentToolContext.current();

context.emitProgress(
    "正在生成报告",
    Collections.singletonMap("percent", 50)
);
```

监听器会收到 `TOOL_PROGRESS`：

```java
runner.addEventListener(event -> {
    if (event.getType() == AgentEventType.TOOL_PROGRESS) {
        String message = String.valueOf(event.getData().get("message"));
        Object percent = event.getData().get("percent");
        progressService.update(event.getTurnId(), message, percent);
    }
});
```

`progressService` 同样表示业务自己的进度保存或推送组件。进度事件只用于展示和观察，不会改变工具的返回值。
工具运行上下文的其他能力见[工具运行上下文](./tool-context)。

## 常用事件类型

不需要一开始就处理所有事件。大多数应用先关注任务完成、任务失败和页面进度即可，再根据业务需要增加其他
类型。

| 分类 | 常用事件 | 说明 |
| --- | --- | --- |
| 任务 | `TURN_STARTED`、`TURN_COMPLETED`、`TURN_FAILED`、`TURN_CANCELLED` | 任务开始和最终结果 |
| 执行步骤 | `STEP_STARTED`、`STEP_COMPLETED` | Runner 开始或结束一步处理 |
| 模型 | `MODEL_STARTED`、`MODEL_TEXT_DELTA`、`MODEL_COMPLETED` | 模型调用和文字输出 |
| 工具 | `TOOL_STARTED`、`TOOL_PROGRESS`、`TOOL_COMPLETED`、`TOOL_FAILED` | 本地工具的执行过程 |
| 等待与恢复 | `TURN_SUSPENDED`、`TURN_RESUMED` | 任务暂停等待，或收到结果后继续 |
| 审批与表单 | `TOOL_APPROVAL_REQUESTED`、`TOOL_INPUT_REQUESTED` | 需要人工操作 |
| 外部工具 | `EXTERNAL_TOOL_REQUESTED`、`EXTERNAL_TOOL_COMPLETED`、`EXTERNAL_TOOL_FAILED` | 工具交给外部系统执行 |
| 重试与限制 | `RETRY_SCHEDULED`、`BUDGET_EXCEEDED`、`MAX_ITERATIONS_REACHED`、`MAX_STEPS_REACHED` | 等待重试或达到运行限制 |
| 上下文压缩 | `CONTEXT_COMPRESSION_STARTED`、`CONTEXT_COMPRESSION_COMPLETED`、`CONTEXT_COMPRESSION_FAILED` | 长对话压缩过程 |
| 任务保存 | `SNAPSHOT_SAVED` | 最新任务进度已经保存 |

`SNAPSHOT_SAVED` 只表示某次进度保存成功，不代表整个任务已经完成。判断任务正常完成应监听
`TURN_COMPLETED`，或者查询 `AgentTurn` 的状态。

任务也可能以其他状态结束。需要统一处理所有结束情况时，还应关注 `TURN_FAILED`、`TURN_CANCELLED`、
`BUDGET_EXCEEDED`、`MAX_ITERATIONS_REACHED` 和 `MAX_STEPS_REACHED`。这些事件分别表示执行失败、任务
取消、运行预算耗尽、模型调用次数达到上限和执行步骤达到上限。

## 一个任务中的事件顺序

一次简单任务的事件过程可以理解为：

```text
任务开始
   ↓
开始一个执行步骤
   ↓
调用模型，接收文字或工具请求
   ↓
根据需要执行工具
   ↓
结束当前步骤
   ↓
任务完成、失败，或者暂停等待
```

对应的事件大致如下：

```text
TURN_STARTED
STEP_STARTED
MODEL_STARTED → MODEL_TEXT_DELTA → MODEL_COMPLETED
TOOL_STARTED → TOOL_PROGRESS → TOOL_COMPLETED    （如果调用了工具）
STEP_COMPLETED
TURN_COMPLETED / TURN_FAILED / TURN_SUSPENDED
```

任务调用工具后，通常还会进入下一个步骤，再次请求模型生成最终回答。审批、表单、重试和取消会产生不同的
分支，因此业务逻辑不应假设每种事件都会出现，也不应只根据固定的事件位置判断最终状态。

## 监听器的执行方式

默认情况下，监听器会在事件发布的线程中执行。这种方式配置简单，也能保持事件调用顺序，但监听器执行太慢
会拖慢 Agent 任务。

监听器中适合进行的操作包括：

- 组装一条简短日志；
- 更新内存计数器；
- 将事件快速放入内部队列；
- 调用能够立即返回的推送接口。

不建议直接进行耗时的数据分析、长时间网络请求或递归调用 `runner.run(...)`。

如果确实需要异步处理，可以为 Runner 配置事件执行器：

```java
ExecutorService eventExecutor =
    Executors.newSingleThreadExecutor();

AgentRunnerOptions runnerOptions = AgentRunnerOptions.builder()
    .eventExecutor(eventExecutor)
    .build();

AgentRunner runner = AgentRunner.builder()
    .options(runnerOptions)
    .build()
    .addEventListener(listener);
```

单线程执行器可以让监听逻辑离开 Agent 执行线程，同时保持提交顺序。执行器由业务系统创建，也应由业务系统
在应用关闭时调用 `shutdown()`。如果改用多线程执行器，不同事件可能并行处理，业务代码不能再依赖接收顺序。

无论使用同步还是异步方式，同一个监听器都可能处理不同任务的事件。有共享集合、计数器或缓存时，应使用
线程安全的数据结构。

## 事件的保存与可靠投递

Agents-Flex 不会自动保存事件。监听器只存在于当前应用进程中，应用重启前发生的事件不会重新发送；监听器
抛出异常时，Runner 会记录问题并继续执行任务，不会因此让 Agent 任务失败。

这意味着不同场景应采用不同处理方式：

| 使用场景 | 推荐方式 |
| --- | --- |
| 调试日志、临时页面进度 | 监听后直接记录或推送 |
| 普通监控指标 | 监听后交给指标系统 |
| 必须保留的业务审计 | 写入业务自己的审计库或可靠消息系统 |
| 必须可靠触发的后续业务 | 使用事务 Outbox、消息队列或补偿扫描 |

对于计费、合规审计、退款完成通知等不能丢失的数据，不要只依赖进程内监听器。可以使用事务 Outbox：先把
“待发送的消息”与业务数据一起保存到数据库，再由独立程序负责重试和投递；同时使用 `eventId` 或业务消息
ID 做去重。

`sequence` 只表示当前 Runner 进程内同一任务的先后编号。应用重启或由其他 Runner 恢复任务后，编号可能
重新开始，因此不能把它直接当作数据库的永久游标。

## 数据安全

事件数据可能包含模型输出、工具名称和参数、表单结构、错误信息或业务 metadata。将事件发送到浏览器、日志
或第三方监控平台前，应根据业务要求完成：

- 删除密码、Token、身份证号等敏感字段；
- 限制模型输出、工具参数和错误信息的长度；
- 校验当前用户是否有权查看对应 `turnId`；
- 对推理内容和内部错误使用更严格的展示规则；
- 为调试日志设置采样和保留期限。

框架会把事件数据复制成只读结构，避免监听器意外修改 Runner 内部状态，但不会自动完成业务脱敏和权限校验。

## 与 Middleware 的区别

事件监听器和 Middleware 都能接触运行过程，但用途不同：

| 能力 | 事件监听器 | Middleware |
| --- | --- | --- |
| 记录日志、指标和审计 | 适合 | 可以，但不是主要用途 |
| 向页面发送进度 | 适合 | 通常不需要 |
| 修改模型或工具参数 | 不支持 | 支持 |
| 阻止或替换一次调用 | 不支持 | 支持 |
| 监听已经发生的结果 | 支持 | 支持 |

简单来说，事件监听器负责“知道发生了什么”，Middleware 负责“参与执行过程”。

## 使用建议

- 刚开始只监听完成、失败和需要展示的进度事件；
- 监听器应快速返回，耗时处理交给独立线程或消息系统；
- 页面断线重连后，从 Store 查询任务最新状态，不依赖事件补全历史；
- 关键业务使用可靠存储和幂等消费，不把内存监听器当作消息队列；
- 对外发送事件前进行脱敏、长度限制和权限校验；
- 需要改变执行行为时使用 Middleware，而不是事件监听器。

## 相关文档

- [可观测性](./observability)：设计日志、指标、Trace 和健康检查
- [AgentTurn](./agent-turn)：查看任务状态和最终结果
- [任务快照持久化](./store)：保存并查询任务最新进度
- [工具运行上下文](./tool-context)：从工具内部上报执行进度
- [Middleware](./middleware)：修改或控制模型与工具的执行过程
- [人工审批](./human-approval)：处理需要人工确认的工具调用
- [表单输入](./form-input)：请求用户补充结构化信息
