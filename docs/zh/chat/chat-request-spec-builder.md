# ChatRequestSpecBuilder 消息构建器
<div v-pre>



## 概述

`ChatRequestSpecBuilder` 是 Agents-Flex 中用于**构建 LLM（大语言模型）请求规范**的核心接口。其职责是将高层的 `Prompt`、`ChatOptions` 和 `ChatConfig` 转换为底层通信所需的完整请求数据（包括 URL、Headers 和请求体 Body）。

该设计实现了 **协议与模型的解耦**：不同 LLM 服务（如 OpenAI、Qwen、Ollama）只需提供各自的 `ChatRequestSpecBuilder` 实现，即可无缝集成到统一的聊天模型架构中。


## 核心接口定义

```java
public interface ChatRequestSpecBuilder {
    ChatRequestSpec buildRequest(Prompt prompt, ChatOptions options, ChatConfig config);
}
```

**输入**：
- `Prompt`：包含对话历史消息（`Message` 列表）
- `ChatOptions`：运行时参数（如 temperature、streaming、max_tokens 等）
- `ChatConfig`：模型配置（如 apiKey、endpoint、defaultModel 等）

**输出**：`ChatRequestSpec`（包含 `url`, `headers`, `body` 三个字段）

> 最终生成的 `ChatRequestSpec` 会被 `ChatClient` 用于实际网络调用。


## 默认实现：`OpenAIChatRequestSpecBuilder`

这是最常用的实现，兼容 OpenAI 官方及所有 OpenAI-compatible API（如 Azure OpenAI、Ollama、LocalAI、DeepSeek 等）。

### 1 构建流程

```java
@Override
public ChatRequestSpec buildRequest(Prompt prompt, ChatOptions options, ChatConfig config) {
    String url = buildRequestUrl(...);      // 通常为 config.getFullUrl()
    Map<String, String> headers = buildRequestHeaders(...); // 包含 Authorization
    String body = buildRequestBody(...);    // JSON 序列化的请求体
    return new ChatRequestSpec(url, headers, body);
}
```

### 2 各部分详解

#### URL 构建
```java
protected String buildRequestUrl(Prompt prompt, ChatOptions options, ChatConfig config) {
    return config.getFullUrl(); // 例如: https://api.openai.com/v1/chat/completions
}
```

#### Headers 构建
```java
protected Map<String, String> buildRequestHeaders(...) {
    headers.put("Content-Type", "application/json");
    headers.put("Authorization", "Bearer " + config.getApiKey());
    return headers;
}
```
> 支持通过子类扩展（如添加 `X-Custom-Header`）。

#### Body 构建
```java
protected String buildRequestBody(...) {
    List<Message> messages = prompt.getMessages();
    UserMessage userMessage = MessageUtil.findLastUserMessage(messages);

    Maps map = buildBaseParamsOfRequestBody(...);

    return map
        .set("messages", chatMessageSerializer.serializeMessages(messages, config))
        .setIfNotEmpty("tools", chatMessageSerializer.serializeTools(userMessage, config))
        .setIfContainsKey("tools", "tool_choice", userMessage != null ? userMessage.getToolChoice() : null)
        .toJSON();
}
```

- 使用 `ChatMessageSerializer` 将 `Message` 对象序列化为 OpenAI 格式的 JSON 数组
- **自动处理工具调用（Function Calling）**：
    - 仅当存在工具时才添加 `tools` 字段
    - 仅当 `tools` 存在且用户指定了 `tool_choice` 时才添加该字段，避免无效参数

#### 基础参数构建（`buildBaseParamsOfRequestBody`）

```java
return Maps.of("model", options.getModelOrDefault(config.getModel()))
    .setIf(options.isStreaming(), "stream", true)
    .setIfNotNull("top_p", options.getTopP())
    .setIfNotNull("temperature", options.getTemperature())
    .setIfNotNull("max_tokens", options.getMaxTokens())
    .setIfNotEmpty("stop", options.getStop());
```

- 使用 **条件设置**（`setIf`, `setIfNotNull`, `setIfNotEmpty`）避免发送 `null` 或空值
- `model` 优先使用 `options.getModel()`，否则回退到 `config.getModel()`

### 3 可定制点

- **消息序列化器**：可通过构造函数或 setter 注入自定义 `ChatMessageSerializer`
  ```java
  builder.setChatMessageSerializer(new MyCustomSerializer());
  ```


## 扩展示例：`QwenRequestSpecBuilder`

阿里通义千问（Qwen）在 OpenAI 协议基础上增加了专属参数（如 `enable_search`, `thinking_budget` 等）。

```java
public class QwenRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {
    @Override
    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options, ChatConfig config) {
        Maps params = super.buildBaseParamsOfRequestBody(prompt, options, config);
        if (options instanceof QwenChatOptions) {
            QwenChatOptions op = (QwenChatOptions) options;
            params.setIf(CollectionUtil.hasItems(op.getModalities()), "modalities", op.getModalities());
            params.setIf(op.getPresencePenalty() != null, "presence_penalty", op.getPresencePenalty());
            params.setIf(op.getEnableSearch() != null, "enable_search", op.getEnableSearch());
            // ... 其他 Qwen 专属参数
        }
        return params;
    }
}
```

> **最佳实践**：通过继承 + 类型检查扩展特定模型参数，保持对 OpenAI 基础协议的兼容性。

## 与整体架构的集成

在 `BaseChatModel` 中，`ChatRequestSpecBuilder` 的调用时机如下：

```java
public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
    // 1. 构建请求规范
    ChatRequestSpec request = getChatRequestSpecBuilder().buildRequest(prompt, options, config);

    // 2. 放入 ChatContext，供 ChatClient 和拦截器使用
    try (ChatContextHolder.ChatContextScope scope =
             ChatContextHolder.beginChat(prompt, options, request, config)) {
        // 3. 执行责任链 → 最终由 ChatClient 使用 request 发起调用
    }
}
```

- **完全解耦**：`ChatClient` 不关心参数如何生成，只消费 `ChatRequestSpec`
- **可拦截修改**：在责任链中，拦截器可通过 `context.getRequestSpec()` 修改 URL/Headers/Body（例如添加签名、切换 endpoint）



## 自定义 `ChatRequestSpecBuilder` 指南

若要支持新模型（如 Gemini、Claude、自研 LLM），请按以下步骤：

### 步骤 1：实现接口或继承现有类
```java
public class MyLLMRequestSpecBuilder implements ChatRequestSpecBuilder {
    @Override
    public ChatRequestSpec buildRequest(Prompt prompt, ChatOptions options, ChatConfig config) {
        // 自定义构建逻辑
    }
}
```

### 步骤 2：处理消息序列化（可选）
- 若消息格式与 OpenAI 不同，实现 `ChatMessageSerializer`
- 否则可复用 `OpenAIChatMessageSerializer`

### 步骤 3：注册到模型实例
```java
MyChatModel model = new MyChatModel(config);
model.setChatRequestSpecBuilder(new MyLLMRequestSpecBuilder());
```


## 工具类支持：`Maps`

Agents-Flex 提供了轻量级的 `Maps` 工具类，用于构建 JSON 兼容的 Map：

```java
Maps.of("key", "value")
    .setIf(condition, "conditionalKey", value)
    .setIfNotNull("nullableKey", someValue)
    .toJSON(); // 返回 JSON 字符串
```

- 避免手写 `if-null` 判断
- 保证最终 JSON 不含无意义的 `null` 字段（部分 LLM 服务会报错）





</div>
