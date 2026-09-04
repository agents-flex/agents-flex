---
title: 挂起和恢复
description: 让 Agent 任务在等待用户、审批或外部结果时暂时停止，并在条件满足后继续执行。
---

# 挂起和恢复

## 概述

有些 Agent 任务无法在一次请求中立即完成。例如：

- 创建工单前，需要用户补充影响范围；
- 执行退款前，需要负责人审批；
- 调用浏览器或移动端能力后，需要等待设备返回结果；
- 第三方服务暂时不可用，需要过一段时间再重试。

这些情况的共同点是：Agent 当前无法继续，但任务也没有失败。此时可以暂时停止任务，保存已经完成的
进度，并向业务系统返回“正在等待”的状态。所需信息或结果到达后，再继续原来的任务。

这个过程称为“挂起和恢复”：

1. Agent 正常执行任务。
2. 遇到必须等待的外部条件时，任务进入等待状态并返回。
3. 业务系统展示表单、发起审批、调用外部设备，或者等待重试时间。
4. 条件满足后，业务系统提交对应结果，Agent 继续执行。

等待期间不需要让 HTTP 请求或工作线程一直保持运行。即使填写表单和继续任务发生在两个不同的请求
中，也可以通过同一个任务 ID 找回并继续之前的工作。

## 适用场景

| 场景 | 等待的内容 | 条件满足后的处理 |
| --- | --- | --- |
| 信息不完整 | 用户填写的字段或补充说明 | 将用户输入交回原任务 |
| 高风险操作 | 审批人的批准或拒绝 | 根据审批结果执行或放弃操作 |
| 外部能力 | 浏览器、移动端或其他系统的执行结果 | 将成功结果或错误交回原任务 |
| 临时故障 | 下一次允许重试的时间 | 到期后重新尝试失败步骤 |

普通聊天不一定需要挂起。如果 Agent 只需要回答一句“请提供订单号”，并且下一条消息可以作为新的对话
轮次处理，使用正常多轮对话即可。只有需要保留同一次任务的执行进度时，才需要挂起和恢复。

## 快速开始

下面演示一个最简单的场景：业务系统收到“查询订单物流”的请求，但发现用户没有提供订单号，因此先
让任务等待；用户补充订单号后，再继续原任务。

示例中的 `chatModel` 表示已经创建好的大模型客户端，`orderService` 表示应用已有的订单服务。

### 1. 创建 Agent 和业务工具

```java
Tool queryOrderTool = Tool.builder(
        "query_order_delivery",
        "根据订单号查询物流状态")
    .addParameter(Parameter.builder()
        .name("orderId")
        .type("string")
        .description("需要查询的订单号")
        .required(true)
        .build())
    .function(arguments ->
        orderService.queryDelivery(
            String.valueOf(arguments.get("orderId"))))
    .build();

Agent agent = Agent.builder("order-agent")
    .instructions("查询物流时必须使用 query_order_delivery 工具，不得猜测物流状态。")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .build();

AgentRunner runner = new AgentRunner();
```

这里的 `Agent` 定义任务使用的大模型、指令和工具，`AgentRunner` 负责创建和执行具体任务。

### 2. 创建任务并让它等待

```java
// start(...) 只创建任务，暂时不调用模型。
AgentTurn turn = runner.start(agent, "帮我查询订单物流");

// 业务系统已经知道缺少订单号，因此让任务等待用户补充。
AgentTurn waiting = runner.suspend(
    turn,
    AgentSuspension.userInput("请提供需要查询的订单号")
);
```

| 配置 | 作用 |
| --- | --- |
| `start(...)` | 创建并保存任务，但不立即执行 |
| `suspend(...)` | 暂时停止指定任务，并保存等待原因 |
| `AgentSuspension.userInput(...)` | 表示当前任务正在等待用户补充文本 |

业务系统可以读取等待状态和提示语：

```java
if (waiting.getStatus() == AgentTurnStatus.WAITING_FOR_USER) {
    String turnId = waiting.getId();
    String message = waiting.getSuspension().getMessage();

    // 将 turnId 和 message 返回给页面。
}
```

`turnId` 标识这一次任务，后续必须使用它提交用户输入。`message` 是应当展示给用户的说明。

### 3. 提交信息并继续任务

用户输入订单号后，恢复原任务：

```java
AgentTurn result = runner.resume(
    turnId,
    AgentResumeCommand.userInput("订单号是 O-1001")
        .withMetadata("submittedBy", "user-1001")
);
```

| 配置 | 作用 |
| --- | --- |
| `userInput(...)` | 创建一条用户补充信息 |
| `resume(turnId, ...)` | 将信息提交给指定任务，并在当前线程继续执行 |
| `withMetadata(...)` | 附加提交人、来源等信息，为后续审计提供上下文 |

恢复后，Agent 会同时看到最初的查询要求和后来补充的订单号，然后调用订单查询工具。不要为补充信息
创建新的任务，否则之前的执行进度和等待原因无法正确关联。

::: tip 何时使用表单？
上面的方式适合补充一个简单文本。如果需要同时收集订单号、退款原因和联系方式等多个字段，应使用
[表单输入](./form-input)，由页面按照固定字段收集和校验数据。
:::

## 常见等待状态

`AgentTurn.getStatus()` 表示任务当前所处阶段。与挂起和恢复有关的状态如下：

| 任务状态 | 正在等待 | 常用恢复命令 |
| --- | --- | --- |
| `WAITING_FOR_USER` | 用户补充文本或表单 | `userInput(...)` |
| `WAITING_FOR_APPROVAL` | 审批人批准或拒绝工具操作 | `approveTool(...)` / `rejectTool(...)` |
| `WAITING_FOR_TOOL` | 外部设备或系统返回工具结果 | `toolResult(...)` / `toolError(...)` |
| `RETRY_SCHEDULED` | 到达下一次重试时间 | 通常由 Worker 自动处理 |

这些状态都表示“任务尚未结束，但当前不能继续”。`COMPLETED`、`FAILED`、`CANCELLED` 等状态表示任务
已经结束，不能再通过恢复命令重新打开。

## 读取等待信息

任务处于等待状态时，可以通过 `getSuspension()` 读取等待原因：

```java
AgentSuspension waitingInfo = turn.getSuspension();

AgentSuspensionType type = waitingInfo.getType();
String message = waitingInfo.getMessage();
String correlationId = waitingInfo.getCorrelationId();
long requestedAt = waitingInfo.getRequestedAt();
long timeoutMillis = waitingInfo.getTimeoutMillis();
```

可以把 `AgentSuspension` 理解为一份“等待说明”，它本身不会执行审批、展示页面或调用外部系统。

| 字段 | 含义 |
| --- | --- |
| `type` | 等待用户、等待审批、等待外部工具或等待重试 |
| `message` | 可以展示给用户或操作人员的说明 |
| `correlationId` | 当前等待事项的关联 ID；提交表单、审批或工具结果时使用 |
| `requestedAt` | 开始等待的时间 |
| `timeoutMillis` | 最长等待时间；`0` 表示没有设置期限 |

不同场景还有各自的专用信息：

| 场景 | 可读取的信息 |
| --- | --- |
| 表单输入 | `getFormKey()`、`getSchema()` |
| 工具审批 | `getToolName()`、`getApprovalCode()`、`getApprovalReason()` |
| 外部工具 | `getToolName()`、`getArguments()` |
| 延迟重试 | `getNextRunnableAt()` |

页面和接口只需要读取当前场景真正需要的字段。工具参数、个人信息和支付信息等敏感内容应经过筛选和脱敏
后再展示。

## 提交不同类型的结果

恢复命令必须与当前等待状态匹配。不能用用户输入命令批准工具，也不能把外部工具结果提交给正在等待
表单的任务。

### 提交结构化表单

```java
Map<String, Object> formData = new LinkedHashMap<>();
formData.put("affectedSystem", "登录系统");
formData.put("impactScope", "ALL_USERS");

runner.resume(
    turnId,
    AgentResumeCommand.userInput(correlationId, formData)
);
```

结构化表单需要携带当前等待信息中的 `correlationId`。完整配置见[表单输入](./form-input)。

### 提交审批结果

批准操作：

```java
runner.resume(
    turnId,
    AgentResumeCommand.approveTool(correlationId)
        .withMetadata("approverId", "manager-1001")
);
```

拒绝操作：

```java
runner.resume(
    turnId,
    AgentResumeCommand.rejectTool(
        correlationId, "操作超出当前审批额度")
        .withMetadata("approverId", "manager-1001")
);
```

完整的审批规则和页面要求见[人工审批](./human-approval)。

### 提交外部工具结果

浏览器、移动端或其他系统成功完成操作后，可以提交结构化结果：

```java
Map<String, Object> location = new LinkedHashMap<>();
location.put("latitude", 31.2304);
location.put("longitude", 121.4737);

runner.resume(
    turnId,
    AgentResumeCommand.toolResult(correlationId, location)
);
```

外部操作失败时，应提交明确的错误代码和说明：

```java
runner.resume(
    turnId,
    AgentResumeCommand.toolError(
        correlationId,
        "PERMISSION_DENIED",
        "用户未授予定位权限")
);
```

成功与失败都应提交结果，避免任务一直停留在等待状态。

### 延迟重试

`RETRY_SCHEDULED` 表示暂时性错误已经安排稍后重试。大多数应用不需要手动恢复这类任务，由
`AgentWorker` 在到达 `getNextRunnableAt()` 指定的时间后继续执行即可。

需要人工强制提前继续时，可以使用：

```java
runner.resume(turnId, AgentResumeCommand.continueTurn());
```

自动重试的次数、间隔和适用错误应通过重试策略配置，详见[错误处理与重试](./retry)。

## 同步与异步恢复

`resume(...)` 会在提交结果的当前线程中继续任务，适合本地程序、内部服务或执行时间较短的请求。

```java
AgentTurn result = runner.resume(turnId, command);
```

Web 接口通常需要尽快返回，可以使用 `submitResume(...)`：

```java
AgentTurn runnable = runner.submitResume(turnId, command);
```

`submitResume(...)` 只接受并保存外部结果，不会在当前请求中继续调用模型或工具。之后需要由已配置的
`AgentWorker` 在后台继续任务。

| 方式 | 是否立即继续执行 | 适用场景 |
| --- | --- | --- |
| `resume(...)` | 是 | 命令行、本地程序、短任务 |
| `submitResume(...)` | 否 | Web 接口、消息回调、后台任务 |

无论使用哪一种方式，都必须继续原来的 `turnId`。后台执行方式见 [Worker](./worker)。

## 主动挂起与自动挂起

快速开始中的 `runner.suspend(...)` 适合业务系统已经明确知道需要等待什么的简单流程。例如，接口层已经
发现订单号缺失，可以直接请求用户补充文本。

更多情况下，框架会根据配置自动让任务进入等待状态：

| 配置的能力 | 自动等待的条件 |
| --- | --- |
| 表单输入 | Agent 选择已注册表单，或业务工具请求补充字段 |
| 人工审批 | 审批规则要求高风险工具先获得批准 |
| 外部工具 | 工具被配置为由浏览器、移动端或其他系统执行 |
| 错误重试 | 可恢复错误满足自动重试条件 |

业务代码通常不需要手工创建审批、外部工具或重试类型的等待信息，应优先使用对应的正式配置。这样可以
确保等待信息、关联 ID 和恢复命令保持一致。

## 工具如何感知任务已经恢复

恢复命令由业务接口提交，但有些本地工具还需要知道自己为什么再次开始执行。例如，工具需要读取用户刚提交
的表单、判断当前是不是错误重试，或者取得稳定幂等键。

Runner 执行本地工具时，会提供 `AgentToolContext`。其中的 `AgentToolResumeInfo` 记录最近一次恢复来源、
恢复次数以及相关业务信息：

```java
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentToolResumeInfo;
import com.agentsflex.agent.tool.AgentToolResumeType;

AgentToolContext context = AgentToolContext.current();

if (context != null && context.isResumed()) {
    AgentToolResumeInfo resumeInfo = context.getResumeInfo();

    AgentToolResumeType resumeType = resumeInfo.getType();
    int resumeCount = resumeInfo.getResumeCount();
    int executionAttempt = context.getExecutionAttempt();

    // 用于日志、监控和了解本次工具为何执行。
}
```

业务代码通常不需要直接操作 `AgentToolResumeInfo`，只有当前工具确实关心恢复来源时才读取。

不同等待方式恢复后，并不都会重新进入同一个本地工具：

| 恢复场景 | 是否进入原本地工具 | 工具中可以读取的信息 |
| --- | --- | --- |
| 普通文本补充 | 否，补充内容直接交给模型 | 没有对应的工具恢复上下文 |
| `request_user_input` 表单 | 否，表单结果先交给模型 | 后续业务工具属于新的工具调用 |
| 工具审批通过 | 是，第一次真正执行原工具 | `isApprovalResumed()` 为 `true`，审批 metadata 可读取 |
| 业务工具请求表单 | 是，从头再次执行原工具 | `isFormInputResumed()` 为 `true`，可读取提交字段 |
| 工具错误自动重试 | 是，从头再次执行原工具 | `isRetryResumed()` 为 `true`，可读取重试序号和前次错误 |
| 外部工具返回结果 | 否，结果直接作为原工具结果交给模型 | 不会执行本地工具函数 |

“任务已经恢复”和“工具已经重放”不是同一个概念。审批发生在工具执行之前，所以审批通过后
`isResumed()` 为 `true`，但 `isReplay()` 为 `false`；表单恢复和错误重试会再次进入执行过的工具，因此
`isReplay()` 为 `true`。

这些标记适合日志和流程判断，但不能代替业务幂等。退款、扣款、发货等操作仍应使用
`context.getIdempotencyKey()` 作为稳定调用标识，并由业务系统保证重复请求只生效一次。完整说明见
[AgentToolContext](./tool-context)。

## 生产环境注意事项

`new AgentRunner()` 默认使用进程内存保存任务，适合本地学习。生产环境中的等待可能持续几分钟甚至几
天，需要配置持久化存储，确保应用重启或切换服务器后仍能恢复任务，详见 [任务快照持久化](./store)。

恢复接口还应遵守以下要求：

1. 校验当前用户、租户和操作权限。
2. 同时校验 `turnId`、当前任务状态和 `correlationId`。
3. 对重复表单提交、重复审批和重复外部回调进行幂等处理。
4. 拒绝已经过期、已经结束或与当前等待事项不匹配的结果。
5. 对退款、发布等有外部影响的工具实现业务级防重复执行。
6. 为长期等待设置合理期限，并明确超时后的失败、取消或拒绝策略。

不要使用 `Thread.sleep(...)` 占住线程等待人工操作或重试时间，也不要通过创建新任务来“恢复”旧任务。
等待期限与超时后的处理方式见[超时控制](./timeouts)。

## 相关文档

- 收集多个结构化字段：[表单输入](./form-input)
- 为高风险操作增加授权确认：[人工审批](./human-approval)
- 配置自动重试：[错误处理与重试](./retry)
- 配置等待期限：[超时控制](./timeouts)
- 了解后台任务处理：[Worker](./worker)
- 了解生产环境的任务保存方式：[任务快照持久化](./store)
- 读取工具的调用身份和恢复来源：[AgentToolContext](./tool-context)
