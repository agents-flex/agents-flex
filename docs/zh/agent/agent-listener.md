---
title: AgentListener
description: 使用粗粒度生命周期回调接入日志、指标和业务通知，并理解回调边界。
---

# AgentListener

## 概述

`AgentListener` 提供面向运行生命周期的同步回调，适合统计任务完成率、记录关键日志和触发轻量通知。它关注“模型开始、工具完成、Run 暂停或结束”等稳定节点，而不是流式 Token。

如果需要文本增量、工具进度和 step 细节，应使用[事件机制](./events)中的 Runtime Event；如果需要跨进程审计和断点消费，应使用持久化 Run Event。

## 注册 Listener

```java
AgentRunner runner = AgentRunner.builder().build();
runner.addListener(new AgentListener() {
    @Override
    public void onRunStart(AgentRun run) {
        System.out.println("started: " + run.getId());
    }

    @Override
    public void onRunComplete(AgentRun run) {
        System.out.println("completed: " + run.getFinalOutput());
    }

    @Override
    public void onRunFailed(AgentRun run, Throwable error) {
        System.err.println("failed: " + error.getMessage());
    }
});
```

Listener 在 Runner 上注册，对该 Runner 推进的所有 Run 生效。

## 回调分类

接口提供默认空实现，业务只需覆盖关心的方法。主要回调包括：

- Run：开始、完成、失败、取消、达到迭代或预算边界。
- 模型：调用开始与完成。
- 工具：开始、完成、失败、请求审批。
- 控制流：暂停、恢复、安排重试。
- 规划：子 Run 启动以及计划、任务状态变化。

具体方法签名以当前版本 `AgentListener` 为准。升级时应重新检查新增默认方法是否需要接入。

## 回调语义

- 回调在触发执行的线程同步运行，应快速返回。
- Listener 列表使用线程安全容器，但 Listener 实现自身仍需线程安全。
- 单个 Listener 的运行时异常会被 Runner 记录并隔离，不应改变 Agent 主状态机。
- 回调是观察接口，不是可靠消息队列；进程退出时可能来不及送达外部系统。
- `onRunStart` 对同一个 Run 只发送一次，即使 Run 经历暂停与恢复。

## 指标示例

```java
public final class MetricsAgentListener implements AgentListener {
    private final MeterRegistry registry;

    public MetricsAgentListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onRunComplete(AgentRun run) {
        registry.counter("agent.run.completed",
            "agent", run.getAgent().getId()).increment();
    }

    @Override
    public void onToolError(AgentRun run, ToolCall call, Throwable error) {
        registry.counter("agent.tool.failed",
            "agent", run.getAgent().getId(),
            "tool", call.getName()).increment();
    }
}
```

不要把 `runId`、用户 ID 等高基数字段作为指标标签；这些信息更适合日志和 Trace。

## 与 Middleware 的区别

Listener 观察已经发生的生命周期，不应修改输入输出；Middleware 位于执行责任链中，可以包装、短路或替换当前调用。鉴权、参数检查、缓存等影响执行结果的逻辑应放在 Middleware 或工具层，统计与通知放在 Listener。

## 可靠集成

需要可靠发送到 Kafka、审计库或计费系统时，消费 `AgentRunEventStore` 中的持久化事件，并保存消费游标。Listener 可用于低延迟提示，但不应作为唯一事实来源。
