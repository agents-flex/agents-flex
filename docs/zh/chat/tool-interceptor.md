---
title: ToolInterceptor 工具拦截器
description: 在 Tool 执行前后统一完成权限、参数校验、审计、缓存和异常处理。
---

# ToolInterceptor 工具拦截器

## 概述

`ToolInterceptor` 是 Tool 真正执行时的责任链扩展点。它与 `ChatInterceptor` 的作用阶段不同：后者包围模型请求，前者包围 Java 工具调用。

两者不要混用：`ToolInterceptor` 处理“工具已经被模型选中之后如何执行”，`ChatInterceptor` 处理“模型请求如何构建和发送”。
`ChatInterceptorRegistration`、`ChatInterceptorMatcher` 和 `ChatInterceptorProvider` 都属于 Chat 请求拦截机制，
完整说明见 [对话拦截器](./chat-interceptor.md)。

```text
模型返回 ToolCall
  -> ToolExecutor
  -> 内置可观测拦截器
  -> 全局 ToolInterceptor
  -> 本次执行的 ToolInterceptor
  -> Tool.invoke(args)
```

## 适用场景

- 在创建订单、发邮件等有副作用的工具执行前校验用户权限。
- 为所有工具记录耗时、审计人和调用结果。
- 校验、补充或规范化模型生成的参数。
- 对只读工具增加缓存、超时或业务降级。
- 把底层异常转换为稳定的业务错误。

只影响模型请求的逻辑应使用 [对话拦截器](./chat-interceptor.md)；只影响某个工具的业务规则可直接写在工具实现中。

## Tool 与 ChatInterceptorProvider

一个 Tool 可以同时实现 `ToolInterceptor` 和 `ChatInterceptorProvider`，分别扩展两个阶段：

```java
public final class SearchTool implements Tool, ChatInterceptorProvider {

    @Override
    public List<ChatInterceptorRegistration> getChatInterceptorRegistrations() {
        return Collections.singletonList(
            ChatInterceptorRegistration.builder(
                    "search-request-preparation",
                    new SearchRequestInterceptor())
                .order(ChatInterceptorOrders.REQUEST_PREPARATION)
                .matcher(context -> context.getPrompt().getTools().stream()
                    .anyMatch(tool -> "search".equals(tool.getName())))
                .build()
        );
    }

    // ToolInterceptor 仍然只包围 SearchTool 的真实执行。
    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        checkPermission(context);
        return chain.proceed(context);
    }
}
```

当 `SearchTool` 通过 `prompt.addTool(searchTool)` 加入 Prompt 时，ChatModel 会自动发现 Provider，
只把该 Provider 的 Registration 加入当前请求；不会修改 ChatModel 的共享拦截器配置。Matcher 可以根据
当前 `ChatContext` 判断是否启用，例如账号、模型、会话属性或 Prompt 内容。Tool 被真正调用时，才进入
`ToolInterceptor` 链。

如果只需要为某个 Prompt 添加 Chat 拦截器，也可以显式注册：

```java
prompt.addChatInterceptorProvider(new ChatInterceptorProvider() {
    @Override
    public List<ChatInterceptor> getChatInterceptors() {
        return Collections.singletonList(new SearchRequestInterceptor());
    }
});
```

简单 Provider 返回 `getChatInterceptors()` 即可；需要稳定名称、条件或顺序时覆盖
`getChatInterceptorRegistrations()`。多个来源发现同一个拦截器实例时，框架会按实例去重。

| 机制 | 作用阶段 | 典型职责 |
| --- | --- | --- |
| `ChatInterceptorProvider` | 当前 Chat 请求组装 | 让 Prompt、Tool 或 ToolGroup 按需贡献 ChatInterceptor |
| `ChatInterceptorRegistration` | Chat 责任链注册 | 为拦截器声明名称、Matcher 和 order |
| `ChatInterceptorMatcher` | Chat 请求运行时 | 判断当前请求是否启用 Registration |
| `ToolInterceptor` | Tool 实际执行 | 权限、参数校验、审计、缓存和异常转换 |

## 快速开始

下面在执行工具前拒绝没有账号信息的请求，并记录耗时：

```java
public class ToolAuditInterceptor implements ToolInterceptor {
    @Override
    public Object intercept(ToolContext context, ToolChain chain)
        throws Exception {

        String accountId = context.getAttribute("accountId");
        if (accountId == null) {
            throw new SecurityException("accountId is required");
        }

        long start = System.nanoTime();
        try {
            return chain.proceed(context);
        } finally {
            audit(context.getTool().getName(), accountId,
                System.nanoTime() - start);
        }
    }
}
```

为某次 ToolCall 注册：

```java
List<ToolMessage> messages = response
    .executeToolCallsAndGetToolMessages(new ToolAuditInterceptor());
```

或者直接创建执行器：

```java
ToolExecutor executor = new ToolExecutor(
    tool,
    toolCall,
    List.of(new ToolAuditInterceptor())
);
Object result = executor.execute();
```

::: warning
不调用 `chain.proceed(context)` 会跳过后续拦截器和真实 Tool。只有权限拒绝、缓存命中或明确降级时才应短路。
:::

## 核心 API

```java
public interface ToolInterceptor {
    Object intercept(ToolContext context, ToolChain chain) throws Exception;
}
```

`ToolContext` 在一次执行期间提供：

| 数据 | 说明 |
| --- | --- |
| `getTool()` | 当前可执行 Tool |
| `getToolCall()` | 模型返回的调用 ID、名称和原始参数 |
| `getArgsMap()` | 由 `ToolCall` 持有的参数 Map |
| `setAttribute/getAttribute` | 仅在本次拦截链中共享临时数据 |

`ToolContextHolder.currentContext()` 使用 `ThreadLocal` 暴露当前上下文，只在 `ToolExecutor.execute()` 的同一线程和执行范围内有效，不会自动传播到线程池任务。

## 全局注册

应用级通用策略可在启动阶段注册：

```java
GlobalToolInterceptors.addInterceptor(new PermissionInterceptor());
GlobalToolInterceptors.addInterceptor(new AuditInterceptor());
```

执行器创建时会复制当时的全局列表，之后再注册不会影响已经创建的 `ToolExecutor`。全局拦截器按注册顺序执行，本次执行传入的拦截器排在其后。

`ToolExecutor` 还会确保内置 `ToolObservabilityInterceptor` 位于链首；如果链中已经存在该类型，则不会重复添加。

## 典型场景

### 参数校验

```java
public Object intercept(ToolContext context, ToolChain chain) throws Exception {
    if ("create_order".equals(context.getTool().getName())) {
        Object amount = context.getArgsMap().get("amount");
        if (!(amount instanceof Number)
            || ((Number) amount).doubleValue() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
    return chain.proceed(context);
}
```

### 只读缓存

```java
public Object intercept(ToolContext context, ToolChain chain) throws Exception {
    String key = context.getTool().getName() + ":" + context.getArgsMap();
    Object cached = cache.get(key);
    if (cached != null) {
        return cached;
    }
    Object result = chain.proceed(context);
    cache.put(key, result);
    return result;
}
```

缓存只适合确定性的只读工具。不要缓存支付、创建、删除等副作用操作。

### 拦截器之间传值

```java
context.setAttribute("traceId", UUID.randomUUID().toString());
String traceId = context.getAttribute("traceId");
```

这些 attributes 不会自动来自 `ChatContext`。若 Tool 需要账号或租户信息，应在创建拦截器时显式传入可信依赖，或建立受控的应用上下文桥接，不要让模型通过 Tool 参数声明身份。

## 生产建议

1. 拦截器实例可能被并发调用，应保持无状态或线程安全。
2. 权限校验必须发生在 `proceed(...)` 前，且不能只依赖模型提示词。
3. 日志和 Span 中对 token、密码、身份证号等字段脱敏。
4. 修改 `getArgsMap()` 会直接影响后续 Tool 看到的参数；若要规范化参数，先复制并评估同一 Map 被其他逻辑读取的影响。
5. 对异常保留服务端诊断信息，但只向模型返回必要的业务说明。

## 常见问题

### 为什么拦截器没有执行？

确认工具是通过 `ToolExecutor` 或 `AiMessageResponse` 的辅助方法执行。直接调用 `tool.invoke(...)` 会绕过责任链。

### 如何跳过真实工具调用？

直接返回缓存或降级结果，不调用 `chain.proceed(...)`。

### 可以调整全局与实例拦截器顺序吗？

当前没有 order 字段。顺序固定为内置可观测性、全局注册顺序、本次执行传入顺序。

## 下一步

- [Function Call](./function-call.md)
- [对话拦截器](./chat-interceptor.md)
- [对话上下文](./chat-context.md)
