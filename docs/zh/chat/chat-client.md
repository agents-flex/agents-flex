---
title: ChatClient 对话客户端
description: 理解 ChatClient 在模型协议层中的职责，并扩展同步 HTTP、流式传输和响应解析。
---

# ChatClient 对话客户端

<div v-pre>

## 概述

`ChatClient` 是模型协议的传输与响应处理层。`BaseChatModel` 先完成 Prompt、Options、拦截器和请求 Body 构建，再把最终 Body 交给 Client：

```text
ChatModel -> ChatInterceptor -> RequestSpecBuilder
          -> ChatClient -> HTTP/SSE -> AiMessageParser
```

普通业务只调用 `ChatModel`，不需要直接创建 Client。只有接入新协议、替换网络实现或编写模型适配器时才使用本篇 API。

## 适用场景

- 服务端不是 OpenAI 兼容协议，需要自定义传输与错误解析。
- 需要使用公司统一的 HTTP Client、代理、证书和网络观测。
- 流式协议是 NDJSON、WebSocket，而不是 SSE。
- 响应 JSON 结构不同，需要注入自定义 `AiMessageParser`。

只需修改 URL、Header 或 Body 时，优先扩展 `ChatRequestSpecBuilder` 或使用 `ChatInterceptor`。

## 快速开始

OpenAI 兼容模型已经由 `OpenAIChatClient` 处理，通常只需通过 `ChatModel` 调用：

```java
OpenAIChatModel model = new OpenAIChatModel(config);
AiMessageResponse response = model.chat(new SimplePrompt("你好"));
```

要替换同步网络实现，可在模型创建后注入：

```java
OpenAIChatClient client = new OpenAIChatClient(model);
client.setHttpClient(new AgentsFlexHttpClient(customOkHttpClient));
model.setChatClient(client);
```

## 核心接口

```java
public abstract class ChatClient {
    public abstract AiMessageResponse chat(String body);
    public abstract void chatStream(
        String body,
        StreamResponseListener listener
    );
}
```

Client 不持有请求级 `ChatContext`。实现类在调用期间通过 `ChatContextHolder.currentContext()` 读取 URL、Header、重试参数和 Prompt；因此方法必须由 `BaseChatModel` 的上下文范围内调用。

## OpenAIChatClient

同步路径使用 `AgentsFlexHttpClient.post(...)`，解析 JSON 错误或成功响应；流式路径每次调用新建 `SseClient`，并由 `BaseStreamClientListener` 聚合增量消息。

- 空同步响应返回 error `AiMessageResponse`。
- 非法 JSON 返回 `invalid json response` 错误响应。
- 响应含 `error` 对象时保留 message、type 和 code。
- 成功时使用 `AiMessageParser`，并补充本地 Token 估算。

可以注入解析器：

```java
OpenAIChatClient client = new OpenAIChatClient(model);
client.setAiMessageParser(customParser);
model.setChatClient(client);
```

`getStreamClient()` 当前每次返回新的 `SseClient`。若要支持其他流协议，应继承 Client 并覆盖该方法或实现完整的 `chatStream(...)`。

## 自定义 Client

```java
public class MyChatClient extends ChatClient {
    public MyChatClient(BaseChatModel<?> model) {
        super(model);
    }

    @Override
    public AiMessageResponse chat(String body) {
        ChatContext context = ChatContextHolder.currentContext();
        ChatRequestSpec spec = context.getRequestSpec();
        String raw = myTransport.post(spec.getUrl(), spec.getHeaders(), body);
        return parse(raw, context);
    }

    @Override
    public void chatStream(String body, StreamResponseListener listener) {
        // 连接流、解析分片，并完整转发 onMessage/onStop/onFailure。
    }
}
```

自定义实现必须把协议错误转换为一致的错误响应或 Listener 失败事件，并确保网络资源在正常结束、异常和主动停止时都能关闭。

## 生产建议

1. Client 实例会被模型复用，字段必须线程安全；请求状态放在 `ChatContext` 或局部变量中。
2. 不要在 Client 中再次序列化 Prompt，Body 已由责任链末端构建。
3. 对同步与流式路径分别测试 2xx、4xx、5xx、空响应、非法 JSON 和中途断流。
4. 日志中不要输出 Authorization 和未经脱敏的完整 Body。

## 常见问题

### 为什么直接调用 `client.chat(body)` 报上下文错误？

Client 依赖 `ChatContextHolder`，应由 `ChatModel` 调用。如果你需要独立 HTTP Client，请直接使用传输层 API。

### 可以在一个 Client 中保存当前 Context 吗？

不应这样做。模型可能被并发调用，请求级 Context 不能保存在共享字段。

## 下一步

- [ChatRequestSpecBuilder](./chat-request-spec-builder.md)
- [AiMessageParser](./ai-message-parser.md)
- [错误重试](./retry.md)

</div>
