---
title: AgentExecutionMode
description: 理解默认 Tool Calling 模式，并实现可持久化、可版本化的自定义 Agent 执行模式。
---

# AgentExecutionMode

## 概述

`AgentExecutionMode` 决定一次 `step` 如何推进。框架默认使用 `ToolCallingAgentExecutionMode`，完成“模型决策、工具执行、结果回传”的原生闭环。平台可以实现反思、监督者、领域状态机等模式，同时复用 Runner 的预算、重试、Checkpoint、Middleware 和事件能力。

## 接口

```java
public interface AgentExecutionMode {
    String getId();
    String getVersion();
    default void validate(Agent agent) {}
    AgentStepResult step(AgentExecutionContext context);
}
```

ID 和版本会写入 Checkpoint。恢复时必须与当前 Agent 的模式一致，防止运行逻辑静默漂移。

## 默认模式

```java
Agent agent = Agent.builder("assistant")
    .chatModel(chatModel)
    .executionMode(ToolCallingAgentExecutionMode.INSTANCE)
    .build();
```

默认值就是该模式。它根据 Run phase 调用模型或执行 pending ToolCall，并由 Runner 完成审批、错误策略和消息写入。

## 自定义模式示例

下面的模式先执行默认 Tool Calling，再要求一次领域复核：

```java
public final class ReviewMode implements AgentExecutionMode {
    @Override
    public String getId() { return "review"; }

    @Override
    public String getVersion() { return "1"; }

    @Override
    public AgentStepResult step(AgentExecutionContext context) {
        AgentRun run = context.getRun();
        Object stage = run.getModeState().get("stage");

        if (stage == null) {
            run.putModeState("stage", "working");
            return context.checkpointAndContinue();
        }

        return context.executeToolCallingStep();
    }
}
```

实际模式应为每个状态定义明确转换，并保证每次 step 要么前进、阻塞、完成或失败，不能无限返回 `PROGRESSED` 而不改变持久化状态。

## AgentExecutionContext

模式通过受控上下文使用 Runner：

| 方法 | 用途 |
| --- | --- |
| `executeToolCallingStep()` | 复用默认模型/工具推进 |
| `checkpoint()` | 保存当前 Run |
| `checkpointAndContinue()` | 保存并返回 `PROGRESSED` |
| `suspend(...)` | 保存暂停点并返回 `BLOCKED` |
| `complete(...)` | 统一完成与发布事件 |
| `fail(error)` | 进入统一取消、重试或失败处理 |

不要通过反射或包外技巧直接修改 Run 内部状态，这会绕过事件和 Checkpoint 约束。

## Mode State

`run.putModeState(key, value)` 保存模式私有状态。键和值会进入 Snapshot，必须可序列化且可向后兼容。建议使用扁平、稳定的字段，并在模式版本变更时提供迁移策略。

## 构建期校验

在 `validate(Agent)` 中检查模式依赖，例如必须存在某个工具、禁止开启某类规划或要求特定 Agent attribute。这样配置错误在 Agent 发布时暴露，而不是运行中途失败。

## 设计原则

- 一次 step 只完成一个清晰的可恢复转换。
- 外部副作用前后设置 Checkpoint，并使用稳定幂等键。
- 等待外部事件时使用 Suspension，不占用线程。
- 异常交给 `context.fail`，不要绕过统一重试。
- 同时设置 `maxSteps`，防止自定义模式逻辑循环。
- ID 一旦发布保持稳定；不兼容状态变更升级 version。

## 测试

为每个 Mode State 转换测试进程重启恢复、重复 step、版本不匹配、预算终止、取消、Middleware 包装和异常重试。自定义模式的可靠性取决于每个边界是否能从快照独立重建。
