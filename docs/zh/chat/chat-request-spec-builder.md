---
title: ChatRequestSpecBuilder 请求构建器
description: 将 Prompt、ChatOptions 和 BaseChatConfig 转换为传输配置与最终请求 Body。
---

# ChatRequestSpecBuilder 请求构建器

<div v-pre>

## 概述

`ChatRequestSpecBuilder` 把框架内部对象转换为模型协议请求。当前接口明确分为两步：

1. 责任链开始前，`buildRequestSpec(...)` 构建 URL、Header 和重试参数。
2. 所有 ChatInterceptor 调用 `proceed(...)` 后，`buildRequestBody(...)` 基于最终 Context 构建 Body。

`ChatRequestSpec` 不保存 Body。这一设计让拦截器在请求发出前仍可修改 Prompt、Options 和 Config。

## 适用场景

- 接入 Claude、Gemini 或自研的非 OpenAI 请求格式。
- 为兼容接口增加固定 Header、URL 规则或厂商参数。
- 自定义消息和 Tool Schema 的序列化方式。
- 为模型扩展特有的 thinking、response format 等 Body 字段。

某个请求临时增加参数时，使用 `ChatOptions.extraBody`；动态 Header 使用 `ChatInterceptor` 修改 `context.getRequestSpec()`。

## 快速开始

OpenAI 兼容协议可直接复用默认实现：

```java
OpenAIChatRequestSpecBuilder builder =
    new OpenAIChatRequestSpecBuilder();

model.setChatRequestSpecBuilder(builder);
```

只替换消息格式时注入 Serializer：

```java
OpenAIChatRequestSpecBuilder builder =
    new OpenAIChatRequestSpecBuilder(myMessageSerializer);
model.setChatRequestSpecBuilder(builder);
```

## 核心接口

```java
public interface ChatRequestSpecBuilder {
    ChatRequestSpec buildRequestSpec(
        Prompt prompt, ChatOptions options, BaseChatConfig config);

    String buildRequestBody(
        Prompt prompt, ChatOptions options, BaseChatConfig config);
}
```

`OpenAIChatRequestSpecBuilder.buildRequestSpec(...)` 使用 `config.getFullUrl()`、Bearer API Key，并合并 Options 与 Config 中的重试开关、次数和初始延迟。

## Body 构建

默认 OpenAI Body 包含：

- `model`：Options 优先，Config 兜底。
- `messages`：由 `ChatMessageSerializer` 序列化。
- `tools`：来自 `Prompt.getTools()`，非空时加入。
- `tool_choice`：Prompt 设置且 tools 存在时加入。
- `top_p`、`temperature`、`max_tokens`、`stop`、`response_format`。
- 流式请求默认加入 `stream_options.include_usage=true`。
- 最后合并 `ChatOptions.extraBody`，同名 key 会覆盖之前的值。

## 自定义实现

```java
public class MyRequestBuilder implements ChatRequestSpecBuilder {
    @Override
    public ChatRequestSpec buildRequestSpec(
        Prompt prompt, ChatOptions options, BaseChatConfig config) {
        return new ChatRequestSpec(
            config.getFullUrl(),
            Map.of("X-API-Key", config.getApiKey()),
            0,
            0
        );
    }

    @Override
    public String buildRequestBody(
        Prompt prompt, ChatOptions options, BaseChatConfig config) {
        return JSON.toJSONString(Map.of(
            "model", options.getModelOrDefault(config.getModel()),
            "input", serialize(prompt.getMessages())
        ));
    }
}
```

如果只是 OpenAI 的超集，继承 `OpenAIChatRequestSpecBuilder` 并覆盖 Header、URL 或基础 Body 构建方法通常更省代码。

## 与拦截器协作

责任链开始时 RequestSpec 已存在，因此拦截器可以修改 URL 和 Header。Body 会在链尾重建，所以修改 `context.getPrompt()` 或 `context.getOptions()` 会生效；试图给 RequestSpec 设置 Body 没有对应 API。

## 生产建议

1. Builder 可能被并发复用，不保存请求级状态。
2. 不在构建阶段执行远程调用；动态凭证可由拦截器按请求注入。
3. 明确 `extraBody` 的覆盖行为，避免调用方绕过受控参数。
4. 为同步与流式 Body 分别写快照测试，尤其覆盖 Tool、多模态与 thinking。

## 常见问题

### 为什么拦截器修改 Prompt 后 Body 仍能更新？

Body 在责任链末端才调用 `buildRequestBody(...)`，使用的是最终 Context。

### ChatRequestSpec 为什么没有 body？

它只保存责任链开始前需要的传输设置；Body 延迟构建是为了支持拦截器改写输入。

## 下一步

- [ChatMessageSerializer](./chat-message-serializer.md)
- [对话拦截器](./chat-interceptor.md)
- [ChatClient](./chat-client.md)

</div>
