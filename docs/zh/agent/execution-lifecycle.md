---
title: 自定义执行模式
---

# 自定义执行模式

## 概述

`AgentExecutionMode` 定义一次 step 如何推进 AgentRun。默认的 `ToolCallingAgentExecutionMode` 已覆盖绝大多数应用：调用模型、接收结构化 ToolCall、执行工具、把 ToolMessage 返回模型，直到得到最终消息或进入等待状态。

只有当产品确实需要反思、监督者或领域协议等不同推进规则时，才需要自定义执行模式。它是 Agent 运行循环的扩展点，不是通用工作流图，也不负责业务页面上的“模式配置表”。

## 快速开发

```java
public final class ReviewExecutionMode implements AgentExecutionMode {
    @Override
    public String getId() {
        return "review";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public AgentStepResult step(AgentExecutionContext context) {
        int reviewCount = (int) context.getRun().getModeState()
            .getOrDefault("reviewCount", 0);

        if (reviewCount == 0) {
            context.getRun().getModeState().put("reviewCount", 1);
            return context.checkpointAndContinue();
        }
        return context.executeToolCallingStep();
    }
}
```

然后在 Agent 上配置该模式：

```java
Agent agent = Agent.builder()
    .id("review-agent")
    .version("1")
    .chatModel(chatModel)
    .executionMode(new ReviewExecutionMode())
    .build();
```

## 受控执行上下文

模式通过 `AgentExecutionContext` 操作 Run，而不是直接修改内部生命周期：

| 方法 | 作用 |
| --- | --- |
| `executeToolCallingStep()` | 复用默认模型与工具闭环 |
| `checkpoint()` | 保存当前稳定状态 |
| `checkpointAndContinue()` | 保存后继续下一 step |
| `suspend(...)` | 保存并进入等待状态 |
| `complete(...)` | 以最终 AI 消息结束 |
| `fail(...)` | 进入统一重试或失败策略 |

模型调用、工具审批、Middleware、预算和事件仍由 Runner 统一处理。自定义模式不应绕过这些基础设施直接调用 ChatModel 或 Tool。

## modeState

模式自己的游标、计数器和中间决策应写入 `modeState`，它会随 Snapshot 持久化。值必须可由当前 `AgentStoreSerializer` 编解码，推荐使用基本值、列表和 Map。

不要把请求级认证信息或 Service 放入 `modeState`。这些对象属于 `AgentInvocationContext`，恢复时由 Provider 重建。

## ID 与版本

执行模式的 ID 和版本会保存在 Snapshot。恢复时，Runner 校验当前 Agent 提供的模式是否匹配，以防部署后用不同逻辑解释旧状态。

修改状态语义、推进顺序或恢复规则时应升级模式版本；只调整日志文字或不影响状态的内部实现通常无需升级。平台如果允许用户选择模式，应把业务配置映射到一个已注册、经过校验的模式实现，不要根据数据库中的任意类名动态实例化代码。

## 校验与边界

`validate(Agent)` 可在 Agent 构建时校验所需模型、工具和策略。例如领域模式可以要求存在特定工具。自定义模式仍受 `maxSteps`、时间和 Token 预算约束，这能避免模式自身逻辑错误形成无限循环。

如果需求本质是固定节点编排、并行网关或复杂条件流转，应交给工作流模块；Agent 模式适合由模型和运行状态动态决定下一步的认知循环。
