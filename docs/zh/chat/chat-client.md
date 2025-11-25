# ChatClient 对话客户端
<div v-pre>


##  概述

`ChatClient` 是 Agents-Flex 中用于对接大语言模型（LLM）服务的**协议调用抽象层**。它定义了统一的同步与流式调用接口，使上层 `BaseChatModel` 能够解耦底层通信协议（如 HTTP、gRPC、WebSocket 等）。

目前提供的典型实现是 `OpenAIChatClient`，它封装了对 OpenAI 兼容 API（包括官方 OpenAI 及其衍生模型服务）的 HTTP 同步请求和 SSE 流式响应处理。


> 注意： 当只有开发者扩展定义一个全新的模型协议时，才关注本章节。


## `ChatClient` 结构与职责

### 1 抽象基类：`ChatClient`

```java
public abstract class ChatClient {
    protected BaseChatModel<?> chatModel;
    protected ChatContext context;

    public ChatClient(BaseChatModel<?> chatModel, ChatContext context) {
        this.chatModel = chatModel;
        this.context = context;
    }

    public abstract AiMessageResponse chat();               // 同步调用
    public abstract void chatStream(StreamResponseListener listener); // 流式调用
}
```

- **职责**：
    - 持有当前模型配置 (`chatModel`) 与请求上下文 (`context`)
    - 定义两个核心方法：同步响应和流式回调
- **使用方式**：作为协议适配器，由具体模型实现类（如 `OpenAIChatClient`）继承并实现具体通信逻辑。


### 2 实现类：`OpenAIChatClient`

封装了对 OpenAI 兼容 API 的完整调用逻辑：

- 使用 `HttpClient` 发起同步 POST 请求
- 使用 `SseClient`（实现自 `StreamClient`）处理 Server-Sent Events (SSE) 流式响应
- 集成重试机制 (`Retryer`)
- 支持自定义响应解析器 (`AiMessageParser`)
- 自动计算并设置 Token 消耗（通过 `LocalTokenCounter`）

#### 核心属性（支持 setter 注入，便于测试或定制）

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `httpClient` | `HttpClient` | `new HttpClient()` | 用于同步请求 |
| `streamClient` | `StreamClient` | `new SseClient()` | 用于流式请求（SSE） |
| `aiMessageParser` | `AiMessageParser<JSONObject>` | `DefaultAiMessageParser.getOpenAIMessageParser()` | 解析 OpenAI JSON 响应为 `AiMessage` |

> 💡 所有 getter 方法均采用懒初始化（Lazy Initialization），确保首次调用时才创建实例。


## 核心 API 说明

### 1 `AiMessageResponse chat()`

**功能**：执行一次完整的同步对话请求，返回结构化响应。


### 2 `void chatStream(StreamResponseListener listener)`

**功能**：以流式方式发起对话，通过回调逐片段返回结果。


## 扩展与定制

### 1 替换 HTTP 客户端

若需使用自定义 HTTP 客户端（如 OkHttp、Apache HttpClient）：

```java
OpenAIChatClient client = new OpenAIChatClient(model, context);
client.setHttpClient(new MyCustomHttpClient());
```

只要实现 `com.agentsflex.core.model.client.HttpClient` 接口即可。

### 2 替换流式客户端

支持 WebSocket 或其他流协议：

```java
client.setStreamClient(new WebSocketStreamClient());
```

需实现 `StreamClient` 接口，并确保能按 OpenAI SSE 格式解析数据流。

### 3 自定义消息解析器

若服务端返回格式与标准 OpenAI 不同（如本地模型返回字段不同）：

```java
AiMessageParser<JSONObject> customParser = (json, ctx) -> {
    String content = json.getString("result");
    return new AiMessage(content);
};
client.setAiMessageParser(customParser);
```


## 与责任链模型的集成

`ChatClient` 并非直接由用户调用，而是作为 **责任链的末端执行者**，由 `BaseChatModel` 在拦截器链执行完毕后调用：

```text
chat() → 构建 ChatContext → 执行拦截器链 → 调用 chatClient.chat()
```


## 错误处理与日志

- 所有 API 调用错误（如 429、500、无效模型）会被解析为 `AiMessageResponse` 的 `error` 状态
- 原始响应体（`rawText`）始终保留，便于调试
- 请求/响应自动记录到 `ChatMessageLogger`（便于审计或监控）




</div>
