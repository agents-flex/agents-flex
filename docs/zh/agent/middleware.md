---
title: Agent Middleware
description: 在步骤、模型调用和工具调用三个层级实现权限、观测、限流、缓存和 Prompt 处理。
---

# Agent Middleware

## 概述

Middleware 是 Agent 执行链的可组合扩展点。它可以读取当前 Run 和 Invocation Context，在不改 Runner 状态机的情况下包裹整个 step、单次模型调用或单次工具调用。

例如多租户平台可以在模型调用前检查额度，在工具调用前检查权限，在 step 外层记录一次状态推进的耗时。

## 快速开发

```java
AgentMiddleware middleware = new AgentMiddleware() {
    @Override
    public AiMessageResponse aroundModelCall(
        AgentMiddlewareContext context,
        AgentModelCallChain chain) {

        quotaService.check(context.getInvocationContext().getTenantId());
        return chain.proceed(context);
    }

    @Override
    public Object aroundToolCall(
        AgentToolCallContext context,
        AgentToolCallChain chain) {

        permissionService.check(
            context.getInvocationContext().getUserId(),
            context.getTool().getName());
        return chain.proceed(context);
    }
};

Agent agent = Agent.builder("secured-agent")
    .chatModel(chatModel)
    .middleware(middleware)
    .build();
```

## 三个拦截层级

| 方法 | 覆盖范围 | 常见用途 |
| --- | --- | --- |
| `aroundStep` | 一次执行模式推进 | step 耗时、状态指标、统一异常映射 |
| `aroundModelCall` | 一次 ChatModel 调用 | 配额、Prompt 调整、模型缓存、Trace |
| `aroundToolCall` | 一个 ToolCall | 权限、审计、限流、业务熔断 |

Middleware 按 Agent 中的注册顺序组成调用链。调用 `chain.proceed(context)` 才会进入后续 Middleware 和实际操作；直接返回可以短路执行。

## 修改 Prompt

```java
@Override
public AiMessageResponse aroundModelCall(
    AgentMiddlewareContext context,
    AgentModelCallChain chain) {

    Prompt prompt = promptPolicy.decorate(
        context.getPrompt(),
        context.getInvocationContext());
    context.setPrompt(prompt);
    return chain.proceed(context);
}
```

不要直接修改 Agent 的 instructions 来承载请求级数据，因为 Agent 是共享不可变定义。动态身份、区域和权限应来自 Invocation Context。

## Middleware 与 ToolInterceptor

Agent Middleware 能看到 AgentRun、规划父子关系和 Invocation Context，适合 Agent 级控制。ToolInterceptor 属于 Core Tool 调用体系，适合参数转换、通用日志和独立于 Agent 的工具治理。两者可以同时使用，顺序为 Agent Middleware 在外，ToolInterceptor 在内。

## 异常与状态

Middleware 抛出的异常会进入 Runner 的统一重试或失败流程。不要在 Middleware 中自行修改 Run 状态或直接写 Store；需要暂停、完成或失败的自定义控制流应实现 `AgentExecutionMode`，通过 `AgentExecutionContext` 使用受控操作。

## 线程与性能

Middleware 在执行线程中同步运行。不要在其中进行无界阻塞，也不要保存当前 Run 到实例字段。Middleware 对象会被多个 Run 复用，内部可变状态必须线程安全。

