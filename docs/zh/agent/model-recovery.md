---
title: 模型故障恢复
description: 当模型因额度、限流、Token 上限或服务异常暂时不可用时，保留任务进度并继续执行。
---

# 模型故障恢复

## 概述

Agent 在执行任务时，不一定只调用一次模型。一个看似简单的请求，背后可能经历多轮模型推理和多次工具调用。例如：

1. 用户要求“汇总今天的客户反馈”；
2. Agent 查询工单、聊天记录和统计系统；
3. 十个数据源都已经返回结果；
4. Agent 准备调用模型生成最终总结；
5. 此时模型账户余额不足，或者 AI 服务限流。

如果框架在第 5 步直接把任务判定为失败，那么前面已经完成的工作就很难继续利用。应用往往只能重新创建任务，用户也可能需要重新输入问题，已经执行过的工具甚至可能被再次调用。

Agents-Flex 的模型故障恢复就是为了解决这个问题：**模型暂时不可用时，先保存当前任务的完整进度，等条件恢复后从原位置继续。**

这里的“一次任务”在 API 中叫作 `AgentTurn`。一个 `AgentTurn` 不只是模型的一次请求，它还保存本次任务已经产生的消息、工具结果、当前执行位置和等待原因。

当 Runner 识别到可恢复的模型故障时，会把当前 `AgentTurn` 设置为 `WAITING_FOR_MODEL`：

- 任务没有完成，但也没有被判定为最终失败；
- 已经完成的模型调用和工具结果会被保留；
- 应用可以在修复模型后重试；
- 用户也可以直接发送新消息，让 Agent 带着原有上下文继续处理。

## 它解决了什么问题

模型不可用的原因很多，处理方式也不同：

| 场景 | 以前常见的处理 | 使用模型故障恢复后 |
| --- | --- | --- |
| 账户余额或配额耗尽 | 整个任务失败，充值后重新开始 | 保存任务，充值后继续原任务 |
| 请求过于频繁 | 请求线程等待或直接报错 | 先自动重试，仍失败则等待恢复 |
| 模型服务过载 | 用户反复提交同一问题 | 保留原任务，服务恢复后继续 |
| Token 超过限制 | 丢失此前工具结果并重建任务 | 保留上下文，调整模型或压缩策略后继续 |
| 用户在等待期间改变要求 | 只能取消旧任务并新建任务 | 把新消息追加到原任务，让模型重新规划 |

这项能力的重点不是“把所有错误都无限重试”，而是把**暂时不可用**和**最终失败**区分开：

- `WAITING_FOR_MODEL` 表示任务仍然可以继续；
- `FAILED` 表示任务已经结束，不能再按原执行位置恢复；
- 普通业务异常、程序错误或无法识别的运行时异常仍会进入 `FAILED`，不会被伪装成模型故障。

## 整体流程

一次模型故障通常会经历以下过程：

```text
正常执行
   |
   | 模型调用失败
   v
自动重试（可选）
   |
   | 重试次数耗尽，或没有配置自动重试
   v
WAITING_FOR_MODEL
   |
   | 模型恢复后重试，或用户发送新消息
   v
从保存的位置继续执行
   |
   +--> COMPLETED：任务完成
   +--> WAITING_FOR_*：等待审批、用户输入、工具或模型
   +--> FAILED：遇到不可恢复错误
```

`WAITING_FOR_MODEL` 是一个稳定的等待状态。Runner 在返回这个状态前已经保存任务快照，所以 Web 请求不需要一直保持连接，服务也不需要在内存里挂起一个线程等待模型恢复。

## 快速开始

下面先使用同步 API 跑通最小流程。假设 `chatModel` 已经完成配置：

```java
Agent agent = Agent.builder("customer-service-agent")
    .instructions("根据用户问题提供准确、简洁的帮助。")
    .chatModel(chatModel)
    .build();

AgentRunner runner = new AgentRunner();
AgentTurn turn = runner.run(agent, "请汇总今天的客户反馈");
```

`run(...)` 会一直执行到任务完成，或者遇到一个需要外部处理的等待状态。因此，调用结束后不要只读取最终文本，应先检查状态：

```java
if (turn.getStatus() == AgentTurnStatus.COMPLETED) {
    System.out.println(turn.getFinalOutput());
} else if (turn.getStatus() == AgentTurnStatus.WAITING_FOR_MODEL) {
    System.out.println("模型暂时不可用，任务已保存：" + turn.getId());
}
```

### 模型恢复后继续原任务

修复额度、限流或模型配置后，可以使用当前故障的 `failureId` 继续：

```java
if (turn.getStatus() == AgentTurnStatus.WAITING_FOR_MODEL) {
    String failureId = turn.getModelFailure().getFailureId();

    AgentTurn result = runner.resume(
        turn,
        AgentResumeCommand.retryModel(failureId)
    );

    if (result.getStatus() == AgentTurnStatus.COMPLETED) {
        System.out.println(result.getFinalOutput());
    }
}
```

`retryModel(...)` 的意思是：**外部条件已经修复，请使用原来的上下文再次调用模型。** 它不会创建新的 `AgentTurn`，也不会主动重新执行已经完成的工具。

`failureId` 用来确认恢复请求针对的是当前这次故障。旧页面或重复请求携带过期的 `failureId` 时，Runner 会拒绝它，避免误恢复后来发生的另一项等待。

这就是最小的模型恢复流程：

1. 调用 `runner.run(...)`；
2. 发现状态为 `WAITING_FOR_MODEL`；
3. 修复模型条件；
4. 使用 `retryModel(failureId)` 继续。

## 用户可以直接发送新消息吗

可以。进入 `WAITING_FOR_MODEL` 后，应用不必强制用户点击“重试”按钮。用户可以继续发送“继续”、补充信息，或者修改要求。

要让 Runner 找到并复用原来的阻塞任务，需要使用同一个 `conversationId`，并为 Runner 配置 `ChatMemoryProvider`：

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .chatMemoryProvider(chatMemoryProvider)
    .build();

String conversationId = "conversation-1001";

AgentTurn waiting = runner.run(
    agent,
    conversationId,
    "请查询十个数据源并汇总"
);

// 假设 waiting 此时因为模型额度不足进入 WAITING_FOR_MODEL。
AgentTurn result = runner.run(
    agent,
    conversationId,
    "继续，并把结果压缩成三点"
);
```

如果这个会话中存在一个正在等待的活跃 `AgentTurn`，第二次 `run(...)` 会：

1. 找到原来的 `AgentTurn`；
2. 把新消息追加到它的上下文；
3. 清除旧的模型等待；
4. 立即调用模型继续处理。

因此 `result.getId()` 与 `waiting.getId()` 相同。之前已经完成的十个工具结果仍然存在，模型会同时看到这些结果和用户刚发送的新要求。

如果产品不允许用户在等待期间输入，可以在 UI 或业务接口层禁用输入框。Runner 默认允许继续发消息，是为了让不同产品自行决定交互方式。

## 选择哪一种继续方式

模型恢复后，通常有三种处理方式：

| 用户意图 | 推荐 API | 实际行为 |
| --- | --- | --- |
| “条件已修复，按原计划继续” | `retryModel(failureId)` | 不增加用户消息，重新执行原模型步骤 |
| “继续，并补充或修改要求” | 会话 `run(...)` / `submitMessage(...)` | 追加新消息，模型结合原上下文重新规划 |
| “无论正在等什么，都放弃旧操作并改做别的事” | `replanWithMessage(...)` | 中断当前等待，追加新消息并重新规划 |

### 1. 重试原模型调用

适用于原问题和上下文都没有变化，只是模型恢复可用：

```java
AgentModelFailure failure = waiting.getModelFailure();

AgentTurn result = runner.resume(
    waiting.getId(),
    AgentResumeCommand.retryModel(failure.getFailureId())
);
```

### 2. 把新消息交给当前会话

适用于用户补充信息或改变输出要求：

```java
AgentTurn result = runner.run(
    agent,
    conversationId,
    "继续，但不要生成表格"
);
```

### 3. 明确中断当前等待并重新规划

普通用户消息在某些状态下会被当作当前问题的回答。例如，Agent 正在等待用户填写目的地时，“东京”应该是表单答案，而不是新任务。

如果按钮的明确含义是“放弃当前操作，改做别的事”，使用 `replanWithMessage(...)`：

```java
AgentTurn result = runner.resume(
    waiting.getId(),
    AgentResumeCommand.replanWithMessage(
        "不用创建工单了，改为查询现有工单"
    )
);
```

该命令在所有阻塞状态下都表示强制重新规划，不会尝试把这段文字解释成正在等待的表单答案或审批结果。

## 同步继续和异步继续

前面的 `run(...)` 和 `resume(...)` 都是同步方法：它们会在当前线程继续调用模型和执行工具，直到完成或再次进入等待状态。

Web 接口、消息队列消费者或长任务通常不适合在请求线程中继续执行，可以改用提交型 API：

```java
AgentTurn runnable = runner.submitResume(
    turnId,
    AgentResumeCommand.retryModel(failureId)
);
```

或者提交一条新的会话消息：

```java
AgentTurn runnable = runner.submitMessage(
    agent,
    conversationId,
    "继续，并给出简短结论"
);
```

`submitResume(...)` 和 `submitMessage(...)` 只保存“任务现在可以继续”的状态，不在当前请求线程调用模型。之后由业务线程、消息队列或调度器显式调用 Runner：

```java
runner.runUntilBlocked(turnId);
```

常用 API 的差异如下：

| API | 是否立即继续执行 | 适用场景 |
| --- | --- | --- |
| `run(..., conversationId, message)` | 是 | 同步聊天接口；新建任务或复用会话中的阻塞任务 |
| `resume(..., command)` | 是 | 同步提交模型重试、审批、表单或工具结果 |
| `submitMessage(...)` | 否 | 异步聊天接口或消息队列入口 |
| `submitResume(..., command)` | 否 | Web 回调、控制面接口，由业务调度器后台继续 |
| `start(...)` | 否 | 明确创建一个新任务；会话已有活跃任务时会拒绝创建 |

不带 `conversationId` 的 `run(agent, message)` 每次都会创建新的 `AgentTurn`，无法自动定位之前阻塞的任务。

## 自动重试与等待模型的区别

限流和服务过载通常会自行恢复。应用可以先配置自动重试：

```java
AgentRetryPolicy retryPolicy = AgentRetryPolicy.builder()
    .maxRetries(3)
    .initialDelayMillis(1_000)
    .multiplier(2.0)
    .maxDelayMillis(30_000)
    .build();

AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.builder()
    .retryPolicy(retryPolicy)
    .build();

Agent agent = Agent.builder("customer-service-agent")
    .chatModel(chatModel)
    .executionPolicy(executionPolicy)
    .build();
```

发生可重试错误后，状态会先变为 `RETRY_SCHEDULED`。这个状态带有下一次可执行时间，业务调度器到期后可以显式调用 `resume(turnId, AgentResumeCommand.retry())` 继续。

如果自动重试次数耗尽，模型仍不可用，结构化模型异常会进入 `WAITING_FOR_MODEL`。这个状态没有默认的自动唤醒时间，需要满足以下任一条件后继续：

- 外部系统修复模型条件并提交 `retryModel(...)`；
- 用户向同一会话发送新消息；
- 运维或调度系统根据自己的策略再次发起恢复。

没有配置自动重试时，可识别的模型故障会直接进入 `WAITING_FOR_MODEL`。

```text
RETRY_SCHEDULED = 已安排何时自动再试
WAITING_FOR_MODEL = 任务已保存，等待外部条件改变
```

自动重试的完整配置和退避规则请参阅[错误处理与重试](./retry)。

## Runner 能识别哪些模型故障

Runner 会把常见模型异常归一化为以下类型：

| `AgentModelFailureType` | 常见场景 | 通常如何处理 |
| --- | --- | --- |
| `QUOTA_EXCEEDED` | 余额不足、项目或组织配额耗尽 | 补充额度或切换可用账户后重试 |
| `RATE_LIMITED` | 请求频率或 Token 速率超过限制 | 等待限流窗口结束，优先自动重试 |
| `TOKEN_LIMIT_EXCEEDED` | 输入、输出或总上下文超过模型上限 | 缩短上下文、降低输出上限或切换模型 |
| `OVERLOADED` | 模型服务暂时过载 | 稍后重试或切换端点 |
| `UNAVAILABLE` | 网络、端点、鉴权配置或通用模型故障 | 检查错误信息和模型配置后决定是否重试 |

当前等待中的故障可以通过 `turn.getModelFailure()` 获取：

```java
AgentModelFailure failure = turn.getModelFailure();

System.out.println("故障类型：" + failure.getType());
System.out.println("HTTP 状态：" + failure.getHttpStatus());
System.out.println("错误代码：" + failure.getErrorCode());
System.out.println("建议等待：" + failure.getRetryAfterMillis());
```

Runner 会沿异常的 `cause` 链查找 `ModelException`。因此，即使模型异常被 HTTP 客户端或 Middleware 包装，只要异常链中仍保留原始 `ModelException`，通常也可以被识别。

`AgentModelFailure` 是可持久化的故障摘要，常用字段包括：

| 字段 | 含义 |
| --- | --- |
| `failureId` | 本次模型故障的唯一 ID，用于校验恢复请求 |
| `type` | 归一化后的故障类型 |
| `exceptionType` | 底层模型异常类名 |
| `message` | 底层错误说明，对外展示前应脱敏 |
| `httpStatus` | HTTP 状态码，底层未提供时为 `0` |
| `errorCode` / `errorType` | 模型供应商返回的错误代码和类型 |
| `retryAfterMillis` | 供应商建议的等待时间，没有时为 `null` |
| `tokenLimitPhase` | Token 超限发生在输入、输出还是总上下文阶段 |
| `modelAttempt` | 当前任务已经发起的模型调用次数 |
| `occurredAt` | 故障发生时间 |

## Token 超限为什么需要单独处理

`TOKEN_LIMIT_EXCEEDED` 与限流不同。限流等待一段时间后可能自行恢复，但上下文过长不会因为等待而变短。

直接发送“继续”还会增加一条用户消息，可能让上下文更长，再次触发同一个错误。应根据 `tokenLimitPhase` 处理：

| 阶段 | 建议 |
| --- | --- |
| `INPUT_CONTEXT` / `TOTAL_CONTEXT` | 启用上下文压缩、减少历史消息，或切换到更大上下文窗口的模型 |
| `OUTPUT` | 降低最大输出 Token，或让用户要求更短的结果 |
| `UNKNOWN` | 结合供应商错误代码和模型请求日志判断限制来源 |

调整完成后，再使用 `retryModel(failureId)`；如果希望同时改变任务要求，也可以发送“请只输出三点摘要”之类的新消息。

上下文压缩的配置参阅[上下文压缩](./context-compression)。

## 恢复时会保留什么

进入 `WAITING_FOR_MODEL` 前，Runner 会保存当前 `AgentTurn` 的快照。恢复时会继续使用：

- 最初的用户请求；
- 本次任务中已经产生的模型消息；
- 已完成工具对应的结果消息；
- 当前执行位置和待处理工具；
- 重试次数、资源消耗和模型故障历史；
- 与任务关联的 Agent ID 和版本。

例如，模型故障发生前已经成功执行了十个查询工具，那么恢复后的模型会继续看到这十个工具结果。仅仅调用 `retryModel(...)` 或发送新消息，不会让这十个工具自动重跑。

需要注意一个更严格的边界：如果工具已经产生外部副作用，但进程在保存工具结果前崩溃，恢复时仍有可能再次执行该工具。因此付款、退款、发货、发布等写入类工具仍必须使用业务幂等键。详见[错误处理与重试](./retry)。

## 新消息遇到其他等待状态时会怎样

会话 API 不只允许在 `WAITING_FOR_MODEL` 时发送消息。一个任务等待审批、用户输入、外部工具或自动重试时，也可能收到用户的新消息。

Runner 会根据当前状态解释普通消息：

| 当前状态 | 收到普通用户消息后的行为 |
| --- | --- |
| `WAITING_FOR_MODEL` | 追加消息，清除当前模型故障，从模型阶段继续 |
| 模型阶段的 `RETRY_SCHEDULED` | 取消原重试时间，追加消息，立即重新调用模型 |
| 手工 `WAITING_FOR_USER` | 优先把消息当作当前问题的回答 |
| `request_user_input` 产生的 `WAITING_FOR_USER` | 纯文本作为对应输入请求的结果；结构化数据仍使用 `userInput(callId, data)` |
| 业务工具结构化表单的 `WAITING_FOR_USER` | 不猜测字段，把旧调用标记为中断，再根据新消息重新规划 |
| `WAITING_FOR_APPROVAL` | 中断待审批工具，再根据新消息重新规划 |
| `WAITING_FOR_TOOL` | 请求取消外部工具，拒绝迟到结果，再根据新消息重新规划 |
| 工具阶段的 `RETRY_SCHEDULED` | 取消待重试工具，闭合旧工具调用，再根据新消息重新规划 |

如果你不希望普通消息因状态不同而具有不同含义，请把“取消当前操作”设计成单独按钮，并在该按钮中使用 `replanWithMessage(...)`。

## 为什么中断工具时还要补一条结果消息

这一节解释 Runner 内部一个重要但容易困惑的行为。只使用基本模型恢复时，可以先跳过。

支持 Tool Calling 的模型通常要求消息成对出现：

1. 模型消息声明“我要调用工具 A”；
2. 后续必须有一条对应的工具结果消息。

假设 Agent 已经请求执行“发布版本”，正在等待人工审批。此时用户发送“先不要发布，改为检查状态”。如果 Runner 直接在工具调用后追加用户消息，消息历史中就会留下一个没有结果的工具调用，许多模型 API 会拒绝这段上下文。

因此 Runner 会按以下顺序处理：

1. 为每个尚未完成的工具调用补充一条“已被用户新消息中断”的工具结果；
2. 清空旧的待处理工具；
3. 追加用户的新消息；
4. 再让模型规划下一步。

这条中断结果不代表工具执行成功，也不会伪造工具完成事件。它只负责保持模型上下文完整，并明确告诉模型旧计划已经终止。

中断文本可以配置：

```java
AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.builder()
    .interruptedToolMessageTemplate(
        "工具 {toolName} 未完成，因为用户提交了新的要求：{reason}"
    )
    .build();
```

中断记录还可以通过 `turn.getToolInterruptions()` 查询，用于审计和页面状态更新。

## 外部工具取消与迟到结果

`WAITING_FOR_TOOL` 表示工具在 Runner 之外执行，例如浏览器、设备或第三方服务。用户发来新消息后，Runner 会记录中断，并发布 `EXTERNAL_TOOL_CANCEL_REQUESTED` 事件，通知外部执行器尽力取消。

这种取消是**协作式取消**：Runner 可以发出请求，但不能保证第三方系统一定能撤销已经发生的操作。例如，付款请求已经被银行受理时，取消本地等待状态并不等于退款。

原工具结果稍后返回时，`toolResult(...)` 或 `toolError(...)` 会因为任务状态或关联 ID 不再匹配而被拒绝。外部工具仍应做到：

- 使用稳定的业务幂等键；
- 支持自己的取消协议；
- 对付款、发布、发货等副作用记录最终业务状态；
- 不把 Runner 的“已取消等待”误认为外部副作用已经撤回。

Runner 还会保证同一 `AgentTurn` 内的 ToolCall ID 唯一，降低迟到结果错误匹配到新工具调用的风险。

## 防止用户消息被重复处理

网络超时后，客户端可能不知道消息是否已经提交，于是重发同一请求。`UserMessage.messageId` 就是用户消息的幂等键。

```java
UserMessage message = new UserMessage("继续，并给出简短结论");
message.setMessageId("message-20260911-001");

AgentTurn runnable = runner.submitMessage(
    agent,
    conversationId,
    message
);
```

同一个 `messageId` 再次提交时，Runner 会返回当前任务，而不会重复追加消息或重复生成工具中断记录。

消息生产方在重试请求时必须复用原来的 `messageId`。如果每次重试都新建消息并生成新 ID，Runner 会把它视为一条新的业务输入。

## 查看当前故障和历史故障

`getModelFailure()` 只返回**当前正在等待处理的模型故障**。恢复后任务不再等待该故障，因此该方法会返回 `null`。

历史记录不会丢失，可以使用：

```java
for (AgentModelFailure failure : turn.getModelFailureHistory()) {
    System.out.println(failure.getOccurredAt() + " " + failure.getType());
}
```

常用统计字段如下：

| API | 含义 |
| --- | --- |
| `getModelFailureHistory()` | 当前任务发生过的全部结构化模型故障，包括自动重试期间的故障 |
| `getIterationCount()` | 实际发起的模型请求总数，包括失败请求 |
| `getModelInvocationFailureCount()` | 没有得到有效模型响应的请求数 |
| `getSuccessfulModelInvocationCount()` | 成功模型回合数，也是 `maxIterations` 使用的计数 |
| `getRetryCount()` | 当前任务累计安排过的自动重试次数 |
| `getConsecutiveRetryCount()` | 当前连续失败链已经消耗的重试次数 |

`maxIterations` 按成功模型回合计算。这样，模型在最后一次允许的模型回合前因为额度问题失败，修复后仍有机会完成；实际请求次数仍可以通过 `getIterationCount()` 监控。

## 页面和接口应该如何展示

普通用户通常不需要看到异常类名、供应商原始响应或堆栈。页面可以把 `WAITING_FOR_MODEL` 展示为可理解的等待状态，并根据故障类型提供操作：

- 限流或过载：显示“模型繁忙，请稍后重试”；
- 额度不足：对管理员显示充值或切换模型入口；
- Token 超限：建议缩短问题或输出；
- 通用不可用：提供重试按钮并记录内部错误编号。

一个简化的业务接口响应可以是：

```json
{
  "turnId": "turn-id",
  "status": "WAITING_FOR_MODEL",
  "failureId": "failure-id",
  "failureType": "QUOTA_EXCEEDED",
  "retryAfterMillis": null,
  "message": "模型当前不可用，请稍后重试"
}
```

服务端应保留真实错误信息，只向普通用户返回脱敏后的说明。恢复接口还应校验租户、操作人、`turnId`、当前状态和 `failureId`，并记录操作审计。

## 生产环境的持久化要求

`new AgentRunner()` 使用内存中的 Store 和 AgentLoader，适合本地试用和单元测试。进程退出后，内存快照会丢失，无法跨重启恢复。

生产环境应配置持久化的 `AgentTurnStore` 和能够按版本加载 Agent 的 `AgentLoader`：

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(persistentTurnStore)
    .agentLoader(versionedAgentLoader)
    .chatMemoryProvider(chatMemoryProvider)
    .build();
```

之后可以只保存 `turnId`，在其他请求或业务线程中恢复：

```java
AgentTurn waiting = runner.restore(turnId);

if (waiting.getStatus() == AgentTurnStatus.WAITING_FOR_MODEL) {
    AgentTurn result = runner.resume(
        turnId,
        AgentResumeCommand.retryModel(
            waiting.getModelFailure().getFailureId()
        )
    );
}
```

多实例部署时，`AgentTurnStore` 还需要保证：

1. `findActiveTurn(conversationId)` 能找到会话中唯一的非终态任务；
2. `save(snapshot, expectedVersion)` 使用乐观锁或事务条件更新，防止并发覆盖；
3. 数据库层限制同一个 `conversationId` 只能存在一个活跃任务；
4. 快照、会话索引和任务版本在同一事务边界内保持一致。

Agents-Flex 会在下一次模型或工具副作用之前保存恢复状态，但跨进程的一致性最终依赖 Store 的实现。具体契约参阅[任务快照持久化](./store)。

### Agent 版本也必须能够恢复

快照会保存创建任务时使用的 `agentId` 和 `agentVersion`。恢复旧任务时，Runner 会加载这个精确版本，而不是自动改用当前最新 Agent。

这样可以避免新版本改变工具定义后，错误地解释旧任务中尚未完成的 ToolCall。相应地，应用也必须保留可恢复的历史 Agent 版本，或者设计明确的版本迁移流程。

如果为了修复故障而切换模型，需要确保旧版本 Agent 加载出来的模型配置也已经更新或仍然可用。

## 监控建议

监听 `TURN_SUSPENDED` 事件可以发现模型等待。事件中的 `suspensionType` 为 `MODEL`，并携带结构化的 `modelFailure`。

建议至少记录以下指标：

- 故障类型、模型供应商和模型名称；
- HTTP 状态与供应商错误代码；
- 自动重试次数；
- 进入 `WAITING_FOR_MODEL` 的任务数和等待时长；
- 最终恢复方式：原模型重试、用户新消息或人工重规划；
- 恢复成功率和重复故障次数。

不要把密钥、完整 Prompt、工具敏感结果或未经脱敏的供应商响应直接写入日志。

## 常见问题

### `WAITING_FOR_MODEL` 会占用一个线程一直等待吗？

不会。Runner 保存快照后就会返回。同步调用的线程已经结束，之后可以通过恢复 API 或业务调度器继续。

### 模型恢复后会从任务开头重新执行吗？

不会。Runner 从快照保存的执行位置继续，已经写入快照的模型消息和工具结果会保留。

### 已经执行过的工具一定不会重复执行吗？

已完成且结果已经保存的工具不会因为模型恢复本身而重新执行。但如果工具已经产生副作用、进程却在保存结果前崩溃，仍可能重试，所以写入类工具必须具备业务幂等性。

### 用户只发送“继续”，之前的上下文会带给模型吗？

会。使用相同 `conversationId` 向阻塞会话发送消息时，Runner 会复用原 `AgentTurn`。之前的模型消息、已保存的工具结果和新消息会一起提交给模型。

### 为什么发送新消息后不再需要原来的 `failureId`？

`failureId` 用于表达“重试当前这次模型故障”。普通用户消息表达的是“在现有上下文上处理一个新要求”，Runner 会主动结束旧的模型等待并重新规划，因此不使用 `retryModel(...)` 的关联校验。

### 用户发消息时任务正在自动重试，会发生什么？

如果重试发生在模型阶段，Runner 会取消原定的重试时间，追加用户消息并让模型尽快重新处理；使用 `submitMessage(...)` 时由业务代码显式调用 Runner。工具阶段的重试会先中断旧工具调用，再重新规划。

### 所有模型异常都会进入 `WAITING_FOR_MODEL` 吗？

不会。只有能够识别为 `ModelException` 的模型故障才会保存为模型等待。普通代码错误、业务异常或无法识别的运行时异常通常进入 `FAILED`。

### 为什么 Token 超限后再次点击重试仍然失败？

因为上下文或输出限制没有改变。应先压缩上下文、减少历史消息、降低输出上限或切换到更大上下文模型，再恢复任务。

### 服务重启后还能继续吗？

使用持久化 `AgentTurnStore` 和可按版本加载 Agent 的 `AgentLoader` 时可以。默认内存实现会在进程退出后丢失数据。

## 相关文档

- 了解 Runner 和任务的基本使用：[AgentRunner](./agent-runner)
- 了解所有等待状态和恢复命令：[挂起和恢复](./suspend-resume)
- 配置自动重试和退避：[错误处理与重试](./retry)
- 处理超长上下文：[上下文压缩](./context-compression)
- 跨进程保存和恢复任务：[任务快照持久化](./store)
