---
title: 对话拦截器 ChatInterceptor
description: 使用 ChatInterceptor 在模型调用前后实现请求调整、条件激活、认证、审计、流式监听和响应处理。
---

# 对话拦截器 ChatInterceptor

## 概述

`ChatInterceptor` 用于在 Chat 请求执行前后插入自定义逻辑，适合处理多个模型调用都需要的通用能力，例如：

- 调整 Prompt、模型参数或请求头
- 注入用户、租户和认证信息
- 记录日志、耗时和审计数据
- 根据当前请求动态启用某项能力
- 缓存命中、权限拒绝等请求短路
- 处理同步响应或监听流式响应

拦截器采用责任链模式。调用 `chain.proceed(...)` 会继续执行后续拦截器，并最终请求大模型；同步调用还可以在 `proceed(...)` 返回后处理响应。

```text
Interceptor A before
  Interceptor B before
    ChatModel
  Interceptor B after
Interceptor A after
```

同步和流式请求分别对应两个入口：

| 请求方式 | 拦截方法 | 结果处理方式 |
| --- | --- | --- |
| `chat(...)` | `intercept(...)` | 返回 `AiMessageResponse` |
| `chatStream(...)` | `interceptStream(...)` | 包装 `StreamResponseListener` |

## 快速上手

下面用一个最小示例，在请求模型前统一调整 `temperature`，并记录同步调用耗时。

### 1. 创建拦截器

```java
public class TimingChatInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        long startNanos = System.nanoTime();
        context.getOptions().setTemperature(0.2f);

        try {
            return chain.proceed(chatModel, context);
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            System.out.println("Chat elapsed: " + elapsedNanos + " ns");
        }
    }
}
```

### 2. 注册拦截器

```java
OpenAIChatModel chatModel = new OpenAIChatModel(config);
chatModel.addInterceptor(new TimingChatInterceptor());
```

### 3. 发起请求

```java
String result = chatModel.chat("介绍一下 Agents-Flex");
```

调用 `chat(...)` 时，`TimingChatInterceptor` 会先调整参数，再继续执行模型请求。`try/finally` 可以确保请求成功或抛出异常时都记录耗时。

::: warning
如果拦截器没有调用 `chain.proceed(...)`，后续拦截器和模型请求都不会执行。只有在缓存命中、权限拒绝等需要主动中断的场景中，才应跳过该调用。
:::

## 完整 Demo

下面以多租户客服为例：

- 从 `ChatContext` 读取租户信息
- 为请求添加租户 Header
- 为高级套餐调整模型参数
- 仅在匹配当前套餐时执行策略拦截器

### 定义租户拦截器

```java
public class TenantHeaderInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        String tenantId = (String) context.getAttribute("tenantId");
        context.getRequestSpec().addHeader("X-Tenant-Id", tenantId);
        return chain.proceed(chatModel, context);
    }
}
```

### 定义高级套餐策略

```java
public class PremiumModelInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        context.getOptions().setModel("qwen-plus");
        context.getOptions().setTemperature(0.1f);
        context.getOptions().setMaxTokens(1200);
        return chain.proceed(chatModel, context);
    }
}
```

### 注册并调用

```java
OpenAIChatModel chatModel = new OpenAIChatModel(config);

chatModel.addInterceptor(new TenantHeaderInterceptor());

chatModel.addInterceptorRegistration(
    ChatInterceptorRegistration.builder(
            "premium-model-policy",
            new PremiumModelInterceptor()
        )
        .matcher(context ->
            "premium".equals(context.getAttribute("plan"))
        )
        .order(ChatInterceptorOrders.DEFAULT)
        .build()
);

Map<String, Object> attributes = new HashMap<>();
attributes.put("tenantId", "tenant-01");
attributes.put("plan", "premium");

ChatOptions options = ChatOptions.builder()
    .contextAccountId("user-1001")
    .contextAttributes(attributes)
    .build();

String result = chatModel.chat("帮我查询订单状态", options);
```

普通 `ChatInterceptor` 默认对所有请求生效。`PremiumModelInterceptor` 使用 Registration 注册，只有 `plan` 为 `premium` 时才会进入责任链。

## 典型场景

### 修改 Prompt 和 ChatOptions

拦截器可以修改当前请求的 Prompt 和模型参数：

```java
public class RequestRewriteInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        ChatOptions options = context.getOptions();
        options.setModel("qwen-plus");
        options.setTemperature(0.1f);
        options.setMaxTokens(800);

        Prompt rewritten = rewritePrompt(context.getPrompt());
        context.setPrompt(rewritten);

        return chain.proceed(chatModel, context);
    }
}
```

适合用于统一系统提示词、模型路由、输出长度限制和内容预处理。

### 动态认证

认证信息通常与当前账号或租户相关，可以在请求发出前动态写入 Header：

```java
public class AuthHeaderInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        String token = tokenService.load(context.getAccountId());
        context.getRequestSpec().addHeader(
            "Authorization",
            "Bearer " + token
        );
        return chain.proceed(chatModel, context);
    }
}
```

如果同步和流式请求都需要认证，应同时实现 `intercept(...)` 和 `interceptStream(...)`，或者将添加 Header 的逻辑提取为两个入口共用的方法。

### 按条件启用拦截器

`ChatInterceptorRegistration` 可以根据完整的 `ChatContext` 决定当前请求是否启用拦截器：

```java
ChatInterceptorRegistration registration =
    ChatInterceptorRegistration.builder(
            "admin-model-policy",
            new AdminModelPolicyInterceptor()
        )
        .matcher(context -> {
            String model = context.getOptions().getModelOrDefault(
                context.getConfig().getModel()
            );

            return "admin".equals(context.getAttribute("role"))
                && model.startsWith("qwen");
        })
        .build();

chatModel.addInterceptorRegistration(registration);
```

Matcher 可以读取 Prompt、模型、账号、会话、业务属性和流式状态：

```java
.matcher(context ->
    context.getAccountId() != null
        && context.getOptions().isStreaming()
        && containsSearchIntent(context.getPrompt())
)
```

Matcher 应只负责判断，避免在其中修改 `ChatContext`。需要补充上下文时，应使用一个执行顺序更早的拦截器。

### 缓存命中或主动短路

同步拦截器可以直接返回结果，不再请求模型：

```java
public class CacheChatInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        AiMessageResponse cached = cache.get(context.getPrompt());
        if (cached != null) {
            return cached;
        }

        AiMessageResponse response = chain.proceed(chatModel, context);
        cache.put(context.getPrompt(), response);
        return response;
    }
}
```

权限拒绝、内容检查失败等场景也可以不调用 `proceed(...)`，直接返回业务结果或抛出异常。

### 修改同步响应

调用 `proceed(...)` 后，可以基于模型响应创建新的 `AiMessageResponse`：

```java
AiMessageResponse response = chain.proceed(chatModel, context);
AiMessage filtered = filter(response.getMessage());

return new AiMessageResponse(
    response.getContext(),
    response.getRawText(),
    filtered
);
```

适合进行敏感信息脱敏、统一格式化或输出内容检查。

### 处理流式响应

流式内容通过回调异步到达。统计完整流式耗时或处理最终状态时，应包装 `StreamResponseListener`：

```java
public class StreamTimingInterceptor implements ChatInterceptor {

    @Override
    public void interceptStream(
        BaseChatModel<?> chatModel,
        ChatContext context,
        StreamResponseListener listener,
        StreamChain chain
    ) {
        long startNanos = System.nanoTime();

        StreamResponseListener wrapped = new StreamResponseListener() {
            @Override
            public void onStart(StreamContext streamContext) {
                listener.onStart(streamContext);
            }

            @Override
            public void onMessage(
                StreamContext streamContext,
                AiMessageResponse response
            ) {
                listener.onMessage(streamContext, response);
            }

            @Override
            public void onStop(StreamContext streamContext) {
                try {
                    listener.onStop(streamContext);
                } finally {
                    recordLatency(System.nanoTime() - startNanos);
                }
            }

            @Override
            public void onFailure(
                StreamContext streamContext,
                Throwable throwable
            ) {
                try {
                    listener.onFailure(streamContext, throwable);
                } finally {
                    recordFailure(throwable);
                    recordLatency(System.nanoTime() - startNanos);
                }
            }
        };

        chain.proceed(chatModel, context, wrapped);
    }
}
```

包装 Listener 时应完整代理 `onStart`、`onMessage`、`onStop` 和 `onFailure`，避免上层无法正确结束请求或释放资源。

## 进阶使用

### ChatInterceptor 接口

同步和流式方法都有默认透传实现，只需要覆盖实际使用的入口：

```java
public interface ChatInterceptor {

    default AiMessageResponse intercept(
        BaseChatModel<?> chatModel,
        ChatContext context,
        SyncChain chain
    ) {
        return chain.proceed(chatModel, context);
    }

    default void interceptStream(
        BaseChatModel<?> chatModel,
        ChatContext context,
        StreamResponseListener listener,
        StreamChain chain
    ) {
        chain.proceed(chatModel, context, listener);
    }
}
```

| 参数 | 说明 |
| --- | --- |
| `chatModel` | 当前 `BaseChatModel`，可访问模型配置和客户端 |
| `context` | 当前请求的上下文，可以读取或修改请求信息 |
| `chain` | 责任链的下一个节点 |
| `listener` | 流式响应监听器，仅流式入口具有此参数 |

### ChatContext

拦截器通过 [ChatContext](./chat-context.md) 获取当前请求及业务上下文：

| 入口 | 用途 |
| --- | --- |
| `getPrompt()` / `setPrompt()` | 读取或替换 Prompt |
| `getOptions()` / `setOptions()` | 调整 model、temperature、maxTokens、thinking 等选项 |
| `getConfig()` / `setConfig()` | 读取或替换本次请求使用的模型配置 |
| `getRequestSpec()` | 调整 URL、Header 和重试配置 |
| `getAccountId()` | 当前账号 ID |
| `getConversationId()` | 当前会话 ID |
| `getBotId()` | 当前业务 Bot ID |
| `getTurnId()` | 当前轮次 ID |
| `getAttributes()` | 在拦截器之间共享请求级业务数据 |

`attributes` 的生命周期仅限当前请求。不要缓存 `ChatContext`，也不要把请求级状态保存在拦截器实例字段中。

### ChatInterceptorRegistration

普通 `ChatInterceptor` 会被包装为始终匹配、`order` 为 `0` 的 Registration。需要条件激活或调整顺序时，可以显式创建 Registration。

| 属性 | 作用 |
| --- | --- |
| `name` | Registration 的稳定名称，用于识别和诊断 |
| `interceptor` | Matcher 命中后执行的拦截器 |
| `matcher` | 根据当前 `ChatContext` 判断是否执行 |
| `order` | 责任链顺序，数值越小越早进入 |

```java
ChatInterceptorRegistration.builder(
        "premium-audit",
        new AuditChatInterceptor()
    )
    .matcher(context ->
        "premium".equals(context.getAttribute("plan"))
    )
    .order(ChatInterceptorOrders.DEFAULT)
    .build();
```

Matcher 在责任链到达当前 Registration 时执行。因此，它可以读取顺序更早的拦截器已经写入 `ChatContext` 的数据。

### 执行顺序

每次请求会合并 Framework、Global 和 Instance Registration，再按 `order` 从小到大稳定排序。

| 常量 | 当前值 | 默认用途 |
| --- | ---: | --- |
| `ChatInterceptorOrders.OBSERVABILITY` | `-10000` | OpenTelemetry 可观测性 |
| `ChatInterceptorOrders.DEFAULT` | `0` | 普通应用拦截器 |
| `ChatInterceptorOrders.REQUEST_PREPARATION` | `10000` | Tool Group 等请求准备逻辑 |

这些值是推荐值，不是边界。应用可以根据需要使用任意整数：

```java
// 在框架可观测性之前加载 Trace Context
.order(ChatInterceptorOrders.OBSERVABILITY - 100)

// 在 Tool Group 解析后检查最终 Prompt
.order(ChatInterceptorOrders.REQUEST_PREPARATION + 100)
```

相同 `order` 的 Registration 按注册先后保持稳定顺序。默认情况下，同值时的来源顺序为 Framework、Global、Instance，各来源内部保持注册顺序。

### 注册范围

#### 实例级注册

实例级拦截器只作用于当前 ChatModel：

```java
OpenAIChatModel chatModel = new OpenAIChatModel(config);
chatModel.addInterceptor(new TimingChatInterceptor());
chatModel.addInterceptorRegistration(registration);
```

也可以在构造模型时传入多个普通拦截器：

```java
List<ChatInterceptor> interceptors = Arrays.asList(
    new AuthHeaderInterceptor(),
    new TimingChatInterceptor()
);

OpenAIChatModel chatModel = new OpenAIChatModel(config, interceptors);
```

#### 全局注册

全局拦截器适合统一认证、审计和租户解析等应用级逻辑：

```java
GlobalChatInterceptors.addRegistration(
    ChatInterceptorRegistration.builder(
            "global-auth",
            new AuthHeaderInterceptor()
        )
        .order(-100)
        .build()
);
```

简单注册方式也可以直接使用：

```java
GlobalChatInterceptors.addInterceptor(new LoggingInterceptor());
GlobalChatInterceptors.addInterceptors(Arrays.asList(
    new AuthHeaderInterceptor(),
    new TimingChatInterceptor()
));
```

全局 Registration 在创建 `BaseChatModel` 时复制到模型实例。之后新增的全局 Registration 只影响后续创建的模型。`GlobalChatInterceptors.clear()` 主要用于测试清理，不建议在生产请求期间调用。

#### 框架内置注册

框架内置 Registration 由 `FrameworkChatInterceptors` 管理，目前包括：

- `chat-observability`：根据配置动态启用 OpenTelemetry。
- `tool-group-resolver`：存在 Tool Group 时，解析并挂载匹配的工具和系统提示词。

它们与应用 Registration 使用同一套排序规则。应用可以通过 `order` 将自己的拦截器放在这些框架节点之前或之后。

### 完整执行流程

```text
创建 ChatContext
    ↓
合并 Framework、Global、Instance Registration
    ↓
按 order 从小到大稳定排序
    ↓
依次执行 Matcher 和命中的 ChatInterceptor
    ↓
调用 ChatModel
    ↓
同步返回响应 / 流式触发 Listener
```

顺序较小的同步拦截器更早执行前置逻辑，并更晚执行后置逻辑。Matcher 只会看到排在它之前的拦截器已经完成的前置修改。

## 最佳实践

1. Interceptor 应保持无状态或线程安全，请求数据放在局部变量或 `ChatContext` 中。
2. Matcher 只负责判断，不在其中修改 Context。
3. 使用 `order` 表达执行顺序；相同 `order` 时依赖注册顺序。
4. 同步后置逻辑使用 `try/finally` 包裹 `chain.proceed(...)`。
5. 流式结束逻辑通过完整包装 `StreamResponseListener` 实现。
6. 全局注册在应用初始化阶段完成，避免请求期间修改共享注册表。
7. 不记录 API Key、Authorization、完整 Prompt 等敏感信息。

## 实现说明

`ChatRequestSpec` 只保存 URL、Header 和重试配置，不包含请求 Body。需要调整模型输入时，应修改 `ChatContext` 中的 `Prompt`、`ChatOptions` 或 `BaseChatConfig`。

模型请求 Body 会在拦截器链执行到末端时，基于最终的 Prompt、Options 和 Config 构建。因此，请在调用 `chain.proceed(...)` 之前完成请求信息修改；不需要手动刷新或重建 `ChatRequestSpec`。

流式状态由框架根据 `chat(...)` 或 `chatStream(...)` 设置。即使拦截器替换了整个 `ChatOptions`，最终请求也会使用正确的 streaming 值。

## 常见问题

### 为什么修改 Prompt 后没有生效？

确认修改发生在 `chain.proceed(...)` 之前，并检查后续拦截器是否再次替换了 Prompt。

### 为什么 Matcher 没有命中？

检查 Matcher 使用的数据是否已经写入 `ChatContext`，并确认提供这些数据的拦截器具有更小的 `order`。

### 为什么同步调用生效，流式调用没有生效？

`intercept(...)` 和 `interceptStream(...)` 是两个独立入口。如果需要支持两种请求方式，应分别实现；未覆盖的方法会使用默认透传逻辑。

### 为什么全局 Registration 没有影响已有 ChatModel？

全局 Registration 在 ChatModel 创建时复制。请在创建模型前完成全局注册，或者直接对已有模型调用 `addInterceptorRegistration(...)`。

### 可以调整 Observability 和 Tool Group 的执行位置吗？

可以。框架内置 Registration 与应用 Registration 使用相同的 `order` 排序规则。调整时需要同时考虑可观测范围以及 Tool Group 的解析时机。

## 相关文档

- [ChatContext 对话上下文](./chat-context.md)
- [ChatModel](./chat-model.md)
- [Tool 工具调用](./tool.md)
- [Tool 拦截器](./tool-interceptor.md)
- [可观测性](../observability/observability.md)
