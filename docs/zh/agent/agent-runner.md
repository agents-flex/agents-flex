---
title: AgentRunner
description: 理解 AgentRunner 的执行入口、step 循环、阻塞边界、依赖与定制方式。
---

# AgentRunner

## 概述

`AgentRunner` 是 Agent 状态机执行器。它创建、推进、暂停和恢复 `AgentTurn`，并统一处理 Snapshot、预算、重试、规划、父子 Turn、Middleware 与生命周期事件。

Runner 自身不把 Turn 保存在实例字段中，适合作为应用级对象复用；真正的持久状态位于 `AgentTurnStore`。同一个 `AgentTurn` 对象不应由两个线程同时直接推进。

## 整体执行流程

下面的流程图覆盖内置 ToolCall 状态机从创建 Turn 到终止或阻塞的完整路径。图中的每个 Snapshot 都是可跨线程、跨请求或跨进程恢复的稳定边界。

```mermaid
flowchart TD
    Input["用户输入 / UserMessage"] --> Entry{"调用入口"}
    Entry -->|"run(...)"| Create["创建 AgentTurn"]
    Entry -->|"start(...)"| Create
    Entry -->|"恢复已有 Turn"| Restore["从 TurnStore 加载 Snapshot"]

    Create --> Ready["READY<br/>保存初始 Snapshot"]
    Ready --> StartMode{"执行方式"}
    StartMode -->|"turn: 当前线程"| Loop["runUntilBlocked"]
    StartMode -->|"start: 后台任务"| ReturnReady["返回 READY Turn"]
    ReturnReady --> Worker["AgentWorker 领取 Lease"]
    Worker --> Restore
    Restore --> LoadAgent["AgentLoader 按 ID + 版本装配 Agent"]
    LoadAgent --> Loop

    Loop --> Guard["step 通用检查<br/>Lease / 取消 / 预算 / maxSteps"]
    Guard -->|"取消或达到限制"| Terminal["保存终态 Snapshot"]
    Guard -->|"Turn 已阻塞"| Blocked["返回阻塞 Turn"]
    Guard -->|"可继续"| Planning{"有可推进的任务计划？"}

    Planning -->|"是"| ChildFlow["创建或等待子 AgentTurn"]
    ChildFlow --> Blocked
    Planning -->|"否"| Middleware["Agent Step Middleware"]
    Middleware --> Phase{"内置状态机<br/>当前 Phase"}

    Phase -->|"MODEL"| Context["构造只读消息窗口<br/>maxAttachedMessages"]
    Context --> ModelChain["Model Middleware -> ChatModel"]
    ModelChain --> ModelResult{"模型响应"}
    ModelResult -->|"最终 AiMessage"| Complete["写入最终消息<br/>COMPLETED"]
    Complete --> Terminal

    ModelResult -->|"包含 ToolCall"| SaveCalls["保存 pendingToolCalls<br/>Phase = TOOLS<br/>Snapshot"]
    SaveCalls --> ToolLoop["按顺序处理 ToolCall"]
    Phase -->|"TOOLS"| ToolLoop

    ToolLoop --> Resolve["解析 Tool + 检查工具预算"]
    Resolve --> Approval{"审批策略"}
    Approval -->|"需要审批"| ApprovalWait["WAITING_FOR_APPROVAL<br/>保存 Suspension"]
    ApprovalWait --> Blocked
    Approval -->|"拒绝"| Rejected["写入拒绝 ToolMessage<br/>Snapshot"]
    Approval -->|"允许"| ToolChain["Tool Middleware -> Interceptor -> Tool"]
    ToolChain --> ToolResult{"工具结果"}
    ToolResult -->|"成功"| SaveResult["写入 ToolMessage<br/>移除当前 pending call<br/>Snapshot"]
    ToolResult -->|"可交给模型处理的错误"| ErrorMessage["写入结构化错误 ToolMessage"]
    ErrorMessage --> SaveResult
    ToolResult -->|"可重试异常"| RetryWait["RETRY_SCHEDULED<br/>保存 nextRunnableAt"]
    RetryWait --> Blocked
    ToolResult -->|"不可恢复异常"| Failed["FAILED"]
    Failed --> Terminal

    Rejected --> MoreCalls{"还有 pending ToolCall？"}
    SaveResult --> MoreCalls
    MoreCalls -->|"是"| ToolLoop
    MoreCalls -->|"否"| BackModel["Phase = MODEL<br/>保存 Snapshot"]
    BackModel --> Loop

    Blocked --> External{"外部条件到达"}
    External -->|"审批 / 用户输入"| Command["AgentResumeCommand<br/>resume 或 submitResume"]
    External -->|"重试到期"| Worker
    External -->|"子 Turn 终止"| ParentResume["结果写回父 Turn"]
    Command --> Resume["校验 Suspension 与 correlationId<br/>恢复原 Phase"]
    ParentResume --> Resume
    Resume --> Loop
```

### 主路径说明

1. `run(...)` 与 `start(...)` 都先创建 `READY` Turn 并保存初始 Snapshot；区别在于前者立即进入循环，后者等待 Worker 领取。
2. 每次 `step` 都先检查 Lease、持久化取消信号、预算和 Runner step 上限，再处理任务规划和 Middleware。
3. MODEL 阶段一次最多调用模型一次。模型直接回答时 Turn 完成；模型返回 ToolCall 时，Runner 先保存全部待处理调用，再进入 TOOLS 阶段。
4. TOOLS 阶段按模型给出的顺序逐个处理调用。每个工具结果都单独写入 `ToolMessage` 并保存 Snapshot，因此后续恢复能准确知道哪些调用已经确认。
5. 审批、用户输入、子 Turn 和延迟重试都会返回阻塞 Turn，不会占用线程等待。
6. 恢复命令必须匹配当前 Suspension。Runner 恢复其记录的 Phase，例如工具审批通过后回到 TOOLS，而不是重新请求模型生成参数。

### 异常与终止路径

模型或工具异常统一进入失败处理：可恢复异常保存为 `RETRY_SCHEDULED`，确定性错误进入 `FAILED`。取消、预算、最大模型迭代和最大 Runner step 分别使用独立终态，调用方不需要从通用异常消息推断原因。

## 构建 Runner

```java
ChatMemoryProvider memoryProvider = conversationId -> loadChatMemory(conversationId);

AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .chatMemoryProvider(memoryProvider) // 可选
    .build();
```

Turn Store 和 AgentLoader 未提供时使用内存实现。`ChatMemoryProvider` 只负责根据稳定的
conversationId 定位业务系统维护的 ChatMemory；`chatMemoryProvider` 是可选能力，不需要业务会话时
无需配置，原有 `run(agent, message)` 与显式传入历史消息的 API 保持不变。生产环境的 Store 替换要求
见 [Store 持久化](./store)。

## 三组执行入口

### `run(...)`

创建 Turn 并在当前线程持续执行到终止或阻塞：

```java
AgentTurn turn = runner.run(agent, "查询订单 1001");
```

适合同步请求和短任务。它不保证一定完成，审批、用户输入、子 Agent 或重试都会返回阻塞 Turn。

### `start(...)`

只创建并保存 `READY` Snapshot：

```java
AgentTurn queued = runner.start(agent, "生成月度报告");
```

适合提交到后台，之后由 `AgentWorker` 领取。

### `step(...)` 与 `runUntilBlocked(...)`

```java
AgentStepResult oneStep = runner.step(turn);
AgentTurn blockedOrDone = runner.runUntilBlocked(turn);
```

`step` 只推进一次稳定执行步骤，适合调试、外部调度或实现细粒度 UI。`runUntilBlocked` 会循环调用 step。

## 执行层次

一次标准推进包含三层：

1. `runUntilBlocked` 判断是否继续循环。
2. `step` 处理取消、Lease、预算、规划、上下文和 Middleware。
3. 内置 ToolCall 状态机根据 Phase 进入 MODEL 或 TOOLS 阶段。

模型返回 ToolCall 时，Runner 先记录 `pendingToolCalls` 并保存 Snapshot，随后才审批和执行。这个顺序是恢复一致性的关键。

## Step 结果

`AgentStepResult` 只返回本步骤直接产生的内容：

- `getResponse()`：本步骤调用模型得到的响应；未调用模型时为 `null`。
- `getToolMessages()`：本步骤执行工具后写入的结果消息；没有工具结果时为空列表。
- `getError()`：本步骤触发重试或失败时关联的异常；正常步骤通常为 `null`。

运行是否继续、阻塞或终止，以及是完成、取消、达到 `maxSteps` 还是预算耗尽，统一读取
`AgentTurn.getStatus()`。这样 Step 结果不会再维护一套与 `AgentTurnStatus` 重复的状态类型。

## 恢复与取消

```java
AgentTurn restored = runner.restore(turnId);
AgentTurn resumed = runner.resume(turnId, AgentResumeCommand.approveTool(callId));
AgentTurn cancelled = runner.requestCancellation(turnId);
```

取消是协作式的：Store 先保存单调取消标志，Runner 在安全边界转换为 `CANCELLED`。它不保证立即中断已经发出的 HTTP 请求或工具函数。

## 业务会话入口

需要让页面时间线与 Agent 长任务自动衔接时，可以配置 ChatMemory Provider：

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .chatMemoryProvider(conversationId -> chatMemoryRepository.load(conversationId))
    .build();

AgentTurn turn = runner.run(agentId, conversationId,
    new UserMessage("继续上一个问题"));
```

Runner 会从 `ChatMemory.getModelMessages(agent.getMaxAttachedMessages())` 分页读取最近的模型可见历史，
不会把完整业务会话复制进 Turn。Snapshot 成功保存后，再把本轮新增消息幂等投影到 ChatMemory；恢复
Turn 时也会重试投影。ChatMemory 写入失败不会把已经正确保存的 Turn 改成失败，`AgentTurnStore` 始终是
执行状态的事实来源。

不配置 Provider 时，仍可显式传入历史：

```java
AgentTurn turn = runner.run(agent, history,
    new UserMessage("继续上一个问题"));
```

该入口只复制传入的历史，不会修改外部 ChatMemory。两种模式都由业务系统维护 conversationId、会话与
当前未结束 turnId 的关系，并防止同一会话并发开始互相冲突的 Turn。

## 外部恢复边界

同步 `resume(...)` 适合同一服务内立即恢复。只更新状态并交给 Worker 时使用：

```java
AgentTurn ready = runner.submitResume(
    turnId,
    AgentResumeCommand.approveTool(callId));
```

跨服务回调应先进入业务系统自己的数据库 Inbox 或消息队列。业务层完成幂等、重试和审计后调用 `submitResume`，Framework 不保存或消费外部恢复事件。

## 监听扩展

```java
runner.addEventListener(eventListener);
```

统一事件监听器覆盖生命周期、模型增量和工具进度；持久化审计由业务监听器自行实现。监听器应快速返回，不能把业务主流程依赖在“监听一定成功”上。

## 自定义建议

- 应用层共享一个配置完整的 Runner，不要为每个请求创建一套内存 Store。
- 短任务使用 `run`，长任务使用 `start + AgentWorker`。
- 不要绕过 Runner 直接修改快照；状态转换、事件和版本更新必须保持一致。
- 工具副作用使用 `AgentToolContext.current().getIdempotencyKey()` 返回的稳定调用 ID 实现业务幂等。
