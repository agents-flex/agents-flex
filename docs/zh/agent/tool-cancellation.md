---
title: 工具停止控制（AgentToolCancellation）
description: 面向初学者介绍如何让 Agent 工具响应用户的停止操作，并释放子进程、HTTP 请求等外部资源。
---

# 工具停止控制（AgentToolCancellation）

## 先从一个常见场景开始

你在自己的应用里接入了一个 AI 助手。用户输入问题后，助手可能需要先调用天气查询、数据库搜索或
文件转换等工具，最后再组织答案。大多数时候这很方便，但也会遇到几种情况：

- AI 输出的方向不对，用户想马上换一个问题；
- 工具处理的数据太多，等待时间超过了用户的预期；
- 工具启动了命令行程序或网络请求，单纯停止屏幕上的文字并不能让这些工作停下来。

因此，应用通常需要一个“停止”按钮。点击后，用户可以立即重新输入问题，而后台正在进行的模型请求、
工具调用和工具创建的资源也应该尽快停止并清理。

## 先认识三个词

如果你刚接触 Agents-Flex，可以先把它理解成下面的关系：

- **Agent**：负责根据用户目标安排步骤的 AI 助手；
- **Tool**：Agent 可以调用的一段 Java 业务代码，例如查数据库、调用 HTTP 服务或执行脚本；
- **一次 Turn**：Agent 从收到一条用户消息，到给出结果或被停止的这一轮工作。

`AgentRunner` 是执行 Agent 的组件。应用在用户点击停止时调用 `runner.stop(turnId)`，其中 `turnId`
只是用来标识“要停止哪一轮工作”的 ID。你不需要先理解 Runner 的内部状态机，使用层面只需要知道：
停止请求会沿着当前这一轮工作传递给模型和本地 Tool。这里的“本地 Tool”指直接运行在当前 Java 应用
进程中的 Tool。

例如，界面上的停止按钮可以把当前对话保存的 `turnId` 传给 Runner：

```java
public void onStopButtonClicked(String turnId) {
    runner.stop(turnId);
}
```

这一步只负责发出停止请求；Tool 是否还需要关闭子进程、网络请求等资源，是下面
`AgentToolCancellation` 要解决的问题。

## 为什么还需要 AgentToolCancellation？

停止一轮工作通常包含三个层次：

1. 停止模型继续生成文字；
2. 让正在运行的 Tool 尽快结束；
3. 关闭 Tool 已经创建的资源。

前两层由 Runner 负责。第三层只有 Tool 自己知道如何完成：Java 线程被中断，并不一定会自动关闭子进程、
HTTP Call、数据库 Statement 或异步 Future。

`AgentToolCancellation` 就是 Runner 和 Tool 之间的一条轻量通知通道。Tool 可以把自己的资源关闭动作注册
进去；用户调用 `stop` 时，Runner 会通知这些动作，然后再尝试中断 Tool。这样既保留了框架统一的停止流程，
又让每个 Tool 能够清理自己真正拥有的资源。

它只在当前一次 Tool 调用期间有效，不会保存到任务快照，也不会成为全局监听器。Tool 调用结束后，Runner
会自动清理本次调用剩余的注册动作。

## 先判断是否需要它

`AgentToolCancellation` 是可选能力，不需要在 `Agent` 或 `Tool` 配置中开启。

| Tool 类型 | 建议 |
| --- | --- |
| 短时、无外部资源的查询 | 不需要注册停止回调 |
| 批量处理、分页读取、文件转换 | 在批次边界检查取消或线程中断 |
| 持有子进程、HTTP Call、数据库 Statement、异步 Future | 注册 `onStop(...)` 主动关闭资源 |

即使 Tool 不注册任何回调，Runner 仍然会尝试中断其执行线程。只有 Tool 自己掌握的资源无法靠线程中断
释放时，才需要增加停止回调。

如果你只是使用 Agents-Flex 自带的 Tool，或者自己写的 Tool 很快就能返回，通常不需要做任何额外配置。
下面的内容主要面向编写长时间运行 Tool 的开发者。

## 示例 1：让长任务主动检查停止

Tool 执行时，Agents-Flex 会提供一个 `AgentToolContext`。它可以理解为“这次 Tool 调用的运行信息”，其中
包含了停止控制器。通过 `AgentToolContext.current()` 可以在 Tool 内取得它；这个方法只应在 Tool 正在执行
时调用。

对于分页、循环或批处理任务，在每个安全边界调用 `throwIfRequested()`：

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.agent.tool.AgentToolCancellation;
import com.agentsflex.agent.tool.AgentToolContext;

Tool tool = Tool.builder("long_task", arguments -> {
    AgentToolContext context = AgentToolContext.current();
    AgentToolCancellation cancellation = context.getCancellation();

    for (int page = 1; page <= 100; page++) {
        cancellation.throwIfRequested();
        processPage(page);
    }

    return "处理完成";
});
```

`throwIfRequested()` 会同时检查本地停止请求和当前线程的中断标记。检测到停止后，它会保留中断标记并
抛出异常，让 Runner 结束这次 Tool 调用。

短时 Tool 不需要为了使用这个 API 而强行增加轮询。检查频率应与业务的最小安全操作单元匹配，例如每页、
每批或每个文件，而不是每一条记录都检查。

## 示例 2：注册资源停止回调

如果 Tool 打开了需要主动关闭的资源，就用 `onStop(...)` 登记一个动作。这个动作就是“收到停止通知后要做
什么”，例如取消网络请求或终止子进程：

```java
AgentToolCancellation.Registration registration =
    context.getCancellation().onStop(() -> closeResource());

try {
    return useResource();
} finally {
    registration.close();
}
```

回调句柄有以下特性：

- 每个回调最多执行一次；
- 停止已经发生后再注册，会立即执行回调；
- 主动 `close()` 后，后续停止不会再触发该回调；
- Tool 正常返回、抛异常或被取消后，Runner 会自动清理本次调用剩余的回调。

因此，`Registration.close()` 是资源提前释放时的主动注销手段，Runner 的作用域清理是最终兜底。监听器不会
随着历史 Turn 永久累积。

## 示例 3：停止子进程

`stop` 不会自动终止 Tool 创建的操作系统进程。Tool 应在停止回调中发出终止信号，并在自己的主执行路径
中等待和确认进程退出：

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentsflex.agent.tool.AgentToolCancellation;
import com.agentsflex.agent.tool.AgentToolContext;

AgentToolContext context = AgentToolContext.current();
AtomicReference<Process> processRef = new AtomicReference<>();

AgentToolCancellation.Registration registration =
    context.getCancellation().onStop(() -> {
        Process process = processRef.get();
        if (process != null && process.isAlive()) {
            // 回调只发送停止信号，不在控制线程中长时间等待。
            process.destroy();
        }
    });

try {
    Process process = new ProcessBuilder("my-command")
        .redirectErrorStream(true)
        .start();
    processRef.set(process);

    while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
        context.getCancellation().throwIfRequested();
    }
    return "执行完成，退出码：" + process.exitValue();
} catch (InterruptedException error) {
    // Runner.stop 会中断等待线程；恢复标记后交给 Runner 识别取消。
    Thread.currentThread().interrupt();
    throw new IllegalStateException("子进程执行被停止", error);
} finally {
    registration.close();
    Process process = processRef.get();
    if (process != null && process.isAlive()) {
        process.destroy();
    }
}
```

如果命令会派生多个子进程，单独调用 `Process.destroy()` 可能只终止外层 Shell。生产环境应使用进程组、
容器 Job、Windows Job Object 或其他进程树管理能力。

## 示例 4：处理 HTTP、数据库和异步任务

不同资源需要调用各自的取消 API：

```java
AgentToolCancellation.Registration registration =
    context.getCancellation().onStop(() -> httpCall.cancel());
```

数据库查询可以注册 `Statement.cancel()`，异步任务可以注册 `future.cancel(true)`，浏览器或 SDK 客户端
可以注册它们提供的 `close()`、`abort()` 或 `cancel()` 方法。

如果 Tool 自己创建了子任务，不能只取消父 Tool 的 Future：

```java
Future<Result> future = executor.submit(this::doWork);
AgentToolCancellation.Registration registration =
    context.getCancellation().onStop(() -> future.cancel(true));

try {
    return future.get();
} catch (InterruptedException error) {
    future.cancel(true);
    Thread.currentThread().interrupt();
    throw new IllegalStateException("子任务被停止", error);
} finally {
    registration.close();
}
```

`AgentToolContext` 不会自动传播到你创建的子线程。子线程不要依赖
`AgentToolContext.current()` 获取上下文；应显式传递必要的只读数据，并由父 Tool 负责取消和等待子任务。

## 进阶：生命周期和竞态

停止回调属于当前 Tool 调用，不属于任务快照（用于恢复任务的持久化记录）：

```text
Tool 开始
  └─ 创建 AgentToolCancellation
       ├─ 注册资源回调
       ├─ Runner.stop -> request -> 触发回调
       ├─ Future.cancel(true) -> 中断 Tool 线程
       └─ Tool finally -> 关闭资源并清理作用域
```

实现时需要考虑以下竞态：

1. `stop` 先发生、Tool 后注册回调：后注册的回调必须立即执行；
2. 回调正在执行时 Tool 进入 `finally`：关闭操作必须幂等；
3. Tool 已经正常结束后收到迟到的 stop：已清理的回调不能再次执行；
4. 回调执行失败：不能阻断 Runner 持久化取消和中断 Java 线程。

框架已经处理这些控制器级竞态。业务回调只需要保证自身的 `destroy()`、`cancel()` 或 `close()` 可以安全
重复调用。

## 进阶：`cancel` 和 `stop` 的区别

| 操作 | 本地回调 | Java 线程中断 | 适用场景 |
| --- | --- | --- | --- |
| `cancel(turnId)` | 不保证立即触发 | 不保证立即触发 | 持久化取消、跨进程执行 |
| `stop(turnId)` | 立即尝试触发 | 立即尝试触发 | 用户点击“停止生成” |

两者都会写入持久化取消标记。跨进程执行时，本地 Tool 取消控制器不存在，远端执行线程只能在安全边界
读取取消状态并自行收束。

## 进阶：副作用和 ToolMessage

停止通知只能停止尚未完成的执行，不能回滚已经提交到外部系统的副作用。退款、扣款、发货、发送消息等
Tool 必须使用 `context.getIdempotencyKey()` 实现幂等，并把业务操作划分为可安全提交的最小单元。

当模型要求调用 Tool 时，会先产生一条 ToolCall；Tool 完成后，模型协议还需要一条带有相同调用 ID 的
ToolMessage 作为结果。停止过程中，Tool 不需要自己补写消息，Runner 会统一生成中断结果，保证这两条消息
仍然能够配对，下一次对话也不会因为协议不完整而出错。

## 最佳实践清单

- 只为确实需要主动关闭的资源注册 `onStop(...)`；
- 回调只发送停止信号，不在回调中等待很长时间；
- 在循环、分页和批处理边界检查 `throwIfRequested()`；
- 正确处理 `InterruptedException`，恢复中断标记后结束 Tool；
- 不创建脱离父 Tool 管理的后台线程或孤儿进程；
- 使用客户端自身的超时和取消 API，不能只依赖 Java 线程中断；
- 所有外部写操作使用稳定幂等键；
- 在 `finally` 中注销回调并确认资源退出；
- 不让停止回调直接修改 Runner 状态或写入 ToolMessage。

`AgentToolContext` 的调用身份、幂等键、进度和恢复数据见[工具运行上下文](./tool-context)。
