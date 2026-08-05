---
title: Agent Middleware
description: 使用责任链包装 Agent step、模型调用和工具调用，并实现鉴权、治理与审计。
---

# Agent Middleware

## 概述

`AgentMiddleware` 是位于 Agent 执行主链中的扩展点，可以包装三个层次：整个 step、一次模型调用和一次工具调用。它适合实现租户鉴权、限流、Prompt 临时增强、模型降级、工具参数校验、缓存和 Trace。

Middleware 会影响执行结果；只观察生命周期时应使用 Listener 或事件。

## 注册 Middleware

```java
Agent agent = Agent.builder("order-agent")
    .chatModel(chatModel)
    .middleware(new TimingMiddleware())
    .middleware(new TenantGuardMiddleware())
    .build();
```

Middleware 是 Agent 定义的一部分，按注册顺序形成责任链。第一个注册项最先进入、最后退出。

## 三个切入点

```java
public final class TimingMiddleware implements AgentMiddleware {
    @Override
    public AgentStepResult aroundStep(
        AgentMiddlewareContext context, AgentStepChain chain) {
        long start = System.nanoTime();
        try {
            return chain.proceed(context);
        } finally {
            record("step", System.nanoTime() - start);
        }
    }

    @Override
    public AiMessageResponse aroundModelCall(
        AgentMiddlewareContext context, AgentModelCallChain chain) {
        return chain.proceed(context);
    }

    @Override
    public Object aroundToolCall(
        AgentMiddlewareContext context, AgentToolCallChain chain) {
        return chain.proceed(context);
    }
}
```

- `aroundStep`：覆盖预算检查之后的模式推进，适合 step 级治理。
- `aroundModelCall`：访问当前 Prompt，适合模型路由、缓存和临时 Prompt 处理。
- `aroundToolCall`：通过非空的 `context.getToolContext()` 访问已解析的 `Tool` 与原始
  `ToolCall`，适合工具授权和审计。其他 Middleware 阶段的 `getToolContext()` 返回 `null`。

## 责任链规则

实现通常必须调用且只调用一次 `chain.proceed(context)`：

- 不调用表示短路，必须返回符合当前状态机的有效结果。
- 多次调用可能重复请求模型或执行有副作用工具。
- 在 `finally` 中做耗时上报仍会增加请求延迟。
- Middleware 被多个 Turn 共享，实例字段必须线程安全。

## 使用 Turn Metadata

```java
@Override
public Object aroundToolCall(AgentMiddlewareContext context,
                             AgentToolCallChain chain) {
    String tenantId = String.valueOf(
        context.getRun().getMetadata().get("tenantId"));
    if (!permissionService.allowed(
        tenantId, context.getToolContext().getToolName())) {
        throw new SecurityException("tool access denied");
    }
    return chain.proceed(context);
}
```

metadata 会随 Snapshot 持久化，因此 Worker 恢复后仍可读取租户等业务标识。鉴权所需的 `permissionService` 由 Middleware 自身通过依赖注入持有，并且必须能够安全地被多个 Turn 并发复用。密钥和服务对象不能放入 metadata。

## 临时修改 Prompt

`AgentMiddlewareContext.setPrompt(...)` 只替换当前责任链中的模型 Prompt，不直接替换 Turn 的持久化 Prompt。这适合添加一次性的路由提示或脱敏视图；需要跨恢复保存的消息修改，应使用 Context Manager 或 Turn 的受控状态。

## 与 ToolInterceptor 的关系

执行顺序为 Agent Tool Middleware 链，然后进入核心 `ToolExecutor` 与 Agent 配置的 `ToolInterceptor`，最后调用 Tool 函数。Middleware 能访问 Turn 和 Agent 上下文；ToolInterceptor 更接近通用工具执行层。跨 Agent 的通用工具治理可放在 ToolInterceptor，任务级策略放在 Middleware。

## 短路示例

模型缓存可以直接返回缓存响应，但必须保证响应与正常模型协议等价，并考虑 ToolCall、Token usage 和事件语义。工具缓存只适用于无副作用且参数完全决定结果的工具。对写工具绝不能用简单缓存代替业务幂等。

## 错误处理

Middleware 抛出的运行时异常会进入 Runner 的统一失败/重试逻辑。参数与权限错误应使用确定性异常，避免自动重试；瞬时依赖失败可以让 Runner 按策略调度重试。不要捕获异常后返回伪造成功结果。

## 测试建议

为每个 Middleware 验证正常链、短路、异常、并发复用和恢复场景；特别检查 `proceed` 只调用一次。包含外部副作用时，用稳定 toolCallId 断言重试不会重复写入。
