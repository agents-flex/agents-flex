---
title: 工具执行与审批
description: 配置 Agent 工具、错误策略、工具审批和跨 Runner 恢复，避免副作用被重复执行。
---

# 工具执行与审批

<div v-pre>

## Agent 如何使用 Tool

Agent 复用对话模块的 `Tool`、`Parameter`、`ToolCall`、`ToolMessage` 和 `ToolInterceptor`。模型返回 ToolCall 后，Runner 按以下顺序处理：

```text
保存 pending ToolCall
  → 冻结 AgentToolReference
  → 解析 Tool
  → 判断审批策略
  → 执行 ToolInterceptor 链
  → 调用 Tool
  → 生成 ToolMessage
  → 保存 Checkpoint
```

先保存 pending ToolCall 很重要：进程在工具执行前退出时，新的 Runner 可以从 TOOLS 阶段继续，而不是重新调用模型。

## 定义工具

```java
Tool cancelOrder = Tool.builder("cancel_order", "取消指定订单")
    .addParameter(Parameter.builder()
        .name("orderNo")
        .type("string")
        .required(true)
        .description("订单号")
        .build())
    .metadata("riskLevel", "HIGH")
    .metadata("sideEffect", true)
    .metadata("provider", "application")
    .function(args -> orderService.cancel(String.valueOf(args.get("orderNo"))))
    .build();
```

`Tool.getMetadata()` 返回只读 Map，可用于工具绑定、审批、展示和审计。Builder 会复制传入的 Map，避免调用方后续修改工具定义。由于 metadata 会进入待执行工具的 Checkpoint，只能保存 JSON 可序列化的非敏感值：

- 可以保存字符串、数字、布尔值、简单列表和 Map；
- 可以保存 provider、serverId、风险等级、是否有副作用等配置；
- 不应保存 API Key、Token、Tool 实例、客户端、连接、Bean、线程或 Lambda。

建议工具：

- 名称稳定、语义单一；
- 参数描述明确；
- 返回可 JSON 序列化结果；
- 副作用操作支持业务幂等键；
- 自己配置底层网络超时。

## 工具错误策略

### FAIL_RUN

```java
.executionPolicy(AgentExecutionPolicy.builder()
    .toolErrorStrategy(ToolErrorStrategy.FAIL_RUN)
    .build())
```

工具失败会进入 Agent 重试策略；没有可用重试时 Run 进入 FAILED。适合支付、写数据库等必须由业务明确处理的错误。

### RETURN_ERROR_TO_MODEL

```java
.executionPolicy(AgentExecutionPolicy.builder()
    .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
    .build())
```

Runner 把异常转换成结构化 ToolMessage，再让模型决定修改参数、选择其他工具或解释失败。

::: warning 副作用工具
不要让模型通过“再次调用”盲目重试非幂等写操作。即使使用审批和 Checkpoint，进程也可能在外部系统完成写入后、结果 Checkpoint 提交前崩溃。工具仍需使用业务幂等键。
:::

### AgentToolInvocation

AgentRunner 执行 Tool 时会把当前调用的持久化身份放入 ToolContext。Tool 或 ToolInterceptor 可以类型安全地读取：

```java
AgentToolInvocation invocation = AgentToolInvocation.current();

String runId = invocation.getRunId();
String rootRunId = invocation.getRootRunId();
String toolCallId = invocation.getToolCallId();
String idempotencyKey = invocation.getIdempotencyKey();
AgentToolReference reference = invocation.getToolReference();
```

默认幂等键为 `runId + ":" + toolCallId`，跨 Checkpoint 和 Worker 恢复保持稳定。外部服务应保存该键与第一次执行结果，重复请求返回相同结果。非 AgentRunner 发起的普通 Tool 调用中，`AgentToolInvocation.current()` 返回 `null`。

## ToolApprovalPolicy

策略返回结构化的 `ToolApprovalDecision`。`outcome` 有三种取值：

| 决定 | 行为 |
| --- | --- |
| `ALLOW` | 立即执行工具 |
| `REQUIRE_APPROVAL` | 保存等待状态，等待外部命令 |
| `DENY` | 不执行工具，向模型返回 `tool_rejected` ToolMessage |

示例：只审批高风险工具。

```java
ToolApprovalPolicy policy = (run, call, tool) -> {
    if ("cancel_order".equals(call.getName())
        || "send_payment".equals(call.getName())) {
        return ToolApprovalDecision.requireApproval()
            .code("ORDER_CANCEL_REVIEW")
            .message("取消订单需要人工确认")
            .reason("工具会改变订单状态")
            .metadata("riskLevel", "HIGH")
            .build();
    }
    return ToolApprovalDecision.ALLOW;
};

Agent agent = Agent.builder("order-agent")
    .chatModel(chatModel)
    .tools(tools)
    .toolApprovalPolicy(policy)
    .build();
```

## 审批接口

首次执行：

```java
AgentRun waiting = runner.run(agent, "取消订单 A1001");

if (waiting.getStatus() == AgentRunStatus.WAITING_FOR_APPROVAL) {
    AgentSuspension suspension = waiting.getSuspension();
    String callId = suspension.getCorrelationId();
    String toolName = String.valueOf(suspension.getMetadata().get("toolName"));
}
```

等待审批时，`code`、`message`、`reason` 和 metadata 会进入 `AgentSuspension.metadata`，平台无需根据
工具名重新推断审批原因。直接拒绝时，相同信息会进入返回模型的 `tool_rejected` ToolMessage。

批准：

```java
AgentRun completed = runner.resume(
    waiting.getId(),
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", currentUserId)
        .withMetadata("approvalSource", "agent-console")
);
```

拒绝：

```java
AgentRun completed = runner.resume(
    waiting.getId(),
    AgentResumeCommand.rejectTool(callId, "订单已进入发货流程")
);
```

拒绝原因会保存到 Run metadata，并作为 `tool_rejected` ToolMessage 的 message 返回模型。Resume Command metadata 会以 `lastResumeCommandMetadata` 保存到 Checkpoint，可由 Event Enricher 选择审批人、来源等非敏感字段写入审计事件。

## 审批与 Worker 解耦

审批 HTTP 接口通常不应该直接修改 Run 或同步执行长任务。推荐把命令写入持久化收件箱：

```java
runner.submitCommand(
    approvalRequestId,
    runId,
    AgentResumeCommand.approveTool(callId)
);
```

命令 ID 是幂等键。Worker 先从 `AgentRunCommandStore` 领取命令，将处理标记和 Run 恢复状态保存到同一个
Checkpoint，再从 RunStore 领取 `RUNNING` 状态的 Run：

```java
worker.pollAndRun(10);
```

进程如果在 Run Checkpoint 成功后、Command ack 前退出，命令会重新投递；Runner 看到 Snapshot 中的
处理标记后只执行 ack，不会再次应用审批或用户输入。

## 工具进度与大型结果

长工具可以从 `ToolContext` 读取 `AgentToolProgressEmitter`：

```java
AgentToolProgressEmitter progress = ToolContextHolder.currentContext()
    .getAttribute(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE);

progress.emit(
    "已处理一半数据",
    Collections.singletonMap("percent", 50)
);
```

进度会成为 `TOOL_PROGRESS` 实时事件。

大型结果可以配置外置策略：

```java
Agent agent = Agent.builder("data-agent")
    .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32_000))
    .build();
```

Runner 把完整结果保存到 `AgentArtifactStore`，ToolMessage 只保留 artifactId、mediaType、size 和
checksum。生产实现应对 Artifact 做租户隔离、访问授权、加密、生命周期和内容类型管理。

## AgentToolRegistry

跨进程恢复时，Registry 使用持久化的 `AgentToolReference` 重新绑定 Tool。静态工具可以直接使用默认引用；动态工具目录可以补充 binding 信息：

```java
public final class ApplicationToolRegistry implements AgentToolRegistry {
    @Override
    public AgentToolReference register(
        String agentId,
        String agentVersion,
        Tool tool
    ) {
        AgentToolReference reference = AgentToolReference.builder(
                agentId, agentVersion, tool.getName())
            .bindingId(String.valueOf(tool.getMetadata().get("serverId"))
                + "/" + tool.getName())
            .bindingVersion("2")
            .metadata(tool.getMetadata())
            .build();
        toolCache.put(reference, tool);
        return reference;
    }

    @Override
    public Tool resolve(AgentToolReference reference) {
        return toolCatalog.resolve(
            reference.getBindingId(),
            reference.getBindingVersion(),
            reference.getMetadata()
        );
    }
}
```

Runner 在模型确定 ToolCall 时复制 Tool metadata 并保存 Reference；恢复后不会依赖原进程中的 Tool 对象。生产实现还应校验 Reference 中的 Agent 身份是否有权使用对应工具，不能只按全局名称任意返回。

`register(...)` 必须为同一 Agent 版本和 Tool 返回语义稳定的 Reference，并且可以安全地重复调用。`resolve(reference)` 是恢复工具的唯一入口；无法解析时返回 `null`，Runner 会将 Run 置为失败状态。

## 下一步

- [Checkpoint 与中断恢复](./checkpoint-resume.md)
- [持久化工具审批 Demo](./demos/durable-tool-agent.md)
- [Human-in-the-loop 场景](./scenarios/human-in-the-loop.md)

</div>
