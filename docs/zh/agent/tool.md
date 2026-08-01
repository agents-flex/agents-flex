---
title: Tool 调用、审批与幂等
description: 定义 Agent Tool，利用 metadata 做结构化审批，并处理副作用、重试和大型结果。
---

# Tool 调用、审批与幂等

## 概述

Tool 把 Java 函数、内部服务或外部 API 变成模型可以选择的结构化能力。模型只负责生成工具名和参数，Runner 负责定位当前 Agent 绑定的 Tool、执行控制链并把结果作为 `ToolMessage` 返回模型。

当 Tool 会退款、发布或删除数据时，模型提出调用不等于业务授权。审批策略必须在副作用发生前给出允许、拒绝或等待外部批准的结构化决策。

## 快速开发

```java
Tool queryOrder = Tool.builder("query_order", "查询订单实时状态")
    .addParameter(Parameter.builder()
        .name("orderId")
        .type("string")
        .description("订单编号")
        .required(true)
        .build())
    .metadata("domain", "order")
    .metadata("sideEffect", false)
    .function(arguments -> orderService.query(
        String.valueOf(arguments.get("orderId"))))
    .build();

Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .tool(queryOrder)
    .build();
```

工具描述应该告诉模型何时调用，参数描述应该说明格式和业务含义。函数内部仍需做服务端校验，不能把模型生成的参数视为可信输入。

## Tool metadata

metadata 描述工具本身，不描述一次调用：

```java
Tool deploy = Tool.builder("deploy_service", "部署指定服务版本")
    .metadata("sideEffect", true)
    .metadata("riskLevel", "HIGH")
    .metadata("binding", "production-platform")
    .function(arguments -> deploymentService.deploy(arguments))
    .build();
```

工具由 AgentLoader 组装并直接绑定到 Agent。Run 恢复时加载原 Agent 版本，再按工具名定位，因此 metadata 也来自该版本，不需要额外 Tool Reference 或 Tool Loader。

## 结构化审批

```java
ToolApprovalPolicy approvalPolicy = (run, call, tool) -> {
    if (Boolean.TRUE.equals(tool.getMetadata().get("sideEffect"))) {
        return ToolApprovalDecision.requireApproval()
            .code("HIGH_RISK_OPERATION")
            .message("该操作需要负责人确认")
            .reason("工具会修改外部业务状态")
            .metadata("riskLevel", tool.getMetadata().get("riskLevel"))
            .build();
    }
    return ToolApprovalDecision.ALLOW;
};

Agent agent = Agent.builder("release-assistant")
    .chatModel(chatModel)
    .tool(deploy)
    .toolApprovalPolicy(approvalPolicy)
    .build();
```

决策有三种结果：

| Outcome | 运行行为 |
| --- | --- |
| `ALLOW` | 立即进入工具执行链 |
| `DENY` | 不执行工具，并按拒绝结果继续或结束 |
| `REQUIRE_APPROVAL` | 保存 pending ToolCall，进入 `WAITING_FOR_APPROVAL` |

code 用于程序判断，message 面向审批人展示，reason 和 metadata 用于审计。Tool 内部鉴权仍然不可省略，因为审批只控制 Agent 调用入口。

## 批准和拒绝

同步处理：

```java
AgentRun waiting = runner.run(agent, "发布 order-api 2.4.0");
String callId = waiting.getSuspension().getCorrelationId();

AgentRun completed = runner.resume(
    waiting.getId(),
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", "admin-1001")
);
```

异步审批接口只提交命令，不执行工具：

```java
runner.submitCommand(
    approvalRequestId,
    runId,
    AgentResumeCommand.rejectTool(callId, "变更窗口已关闭")
        .withMetadata("approverId", currentUserId)
);
```

Worker 消费 Command Inbox 后恢复 Run。correlationId 必须与当前等待的 ToolCall 匹配，避免重复或迟到审批影响新的调用。

## 工具调用身份与幂等

副作用工具可以读取：

```java
AgentToolInvocation invocation = AgentToolInvocation.current();
String idempotencyKey = invocation.getIdempotencyKey();
```

默认键由 `runId:toolCallId` 组成，跨重试和进程恢复保持稳定。外部服务应保存这个键并拒绝重复副作用，因为存在“工具已成功、Checkpoint 尚未提交时进程退出”的故障窗口，Agent 工具执行应按至少一次语义设计。

## 调用链顺序

```text
Agent Middleware aroundToolCall
    -> Agent 级 ToolInterceptor
    -> Tool 自身/全局 ToolInterceptor
    -> Tool function
```

Middleware 适合读取 Run 和 Invocation Context；ToolInterceptor 适合复用已有 Tool 层的参数处理、日志和观测能力。

## 错误和结果

`ToolErrorStrategy.FAIL_RUN` 会先应用 RetryPolicy，耗尽后结束 Run；`RETURN_ERROR_TO_MODEL` 会把结构化错误作为 ToolMessage 返回模型，让模型修正参数或换一种方案。

大型结果不适合完整放入后续 Prompt。配置 `ToolResultOffloadPolicy` 后，Runner 把原文写入 Artifact Store，ToolMessage 只保留引用、摘要和校验信息。具体见[上下文与多模态](./context-management.md)。

