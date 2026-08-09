---
title: AiMessageParser AI 消息解析器
description: 将同步或流式模型响应解析为统一的 AiMessage，包括正文、推理、ToolCall 和 Token 用量。
---

# AiMessageParser AI 消息解析器

<div v-pre>

## 概述

`AiMessageParser<T>` 把模型服务的原始响应对象转换为统一 `AiMessage`。这样 Tool 执行、Memory、流式监听和上层业务无需知道不同厂商的 JSON 字段位置。

```text
HTTP/SSE 原始 JSON -> AiMessageParser -> AiMessage -> AiMessageResponse
```

## 适用场景

- OpenAI 兼容服务把正文放在自定义字段。
- 模型返回 `reasoning`、`reasoning_content` 或专有 Token 字段。
- ToolCall 的名称和 arguments 结构不同。
- 接入完全不同的响应对象类型，而不仅是 Fastjson `JSONObject`。

请求格式不同应修改 Serializer 或 RequestSpecBuilder；不要在 Parser 中修正请求。

## 快速开始

字段结构接近 OpenAI 时，可复用默认解析器并调整 JSONPath：

```java
DefaultAiMessageParser parser =
    DefaultAiMessageParser.getOpenAIMessageParser();
parser.setContentPath(
    JSONUtil.getJsonPath("$.result.message.content")
);

OpenAIChatClient client = new OpenAIChatClient(model);
client.setAiMessageParser(parser);
model.setChatClient(client);
```

如果同步和流式字段不同，应同时配置 `contentPath` 与 `deltaContentPath`。

## 核心接口

```java
public interface AiMessageParser<T> {
    AiMessage parse(T response, ChatContext context);
}
```

Parser 通过 `context.isStreaming()` 判断当前分支。流式时每次解析的是一个增量 `AiMessage`，后续由 `BaseStreamClientListener` 聚合完整内容与 ToolCall。

## DefaultAiMessageParser

默认实现可配置：

- 同步与流式正文 JSONPath。
- 多个同步与流式 reasoning 候选路径，按顺序取第一个非 null 值。
- index、finish reason、stop reason。
- prompt、completion、total Token。
- 同步与流式 ToolCall 数组路径及 `callsParser`。
- 是否解析 OpenAI 的 id、model、service tier、logprobs 和 usage details。

若没有 total token，但 prompt 与 completion token 都存在，解析器会计算二者之和。

## ToolCall 解析

`getOpenAIMessageParser()` 的 `callsParser` 支持 arguments 为 JSON 字符串或 Map。自定义协议必须构造包含正确 name、id 和 arguments 的 `ToolCall`，否则 `AiMessageResponse` 无法按名称找到 Tool，或 ToolMessage 无法关联调用 ID。

```java
parser.setCallsParser(array -> {
    List<ToolCall> calls = new ArrayList<>();
    // 从厂商结构读取 id、name、arguments
    return calls;
});
```

## 完全自定义解析器

```java
AiMessageParser<JSONObject> parser = (json, context) -> {
    AiMessage message = new AiMessage();
    message.setContent(json.getString("answer"));
    message.setFinishReason(json.getString("status"));
    return message;
};
```

流式自定义解析尤其要处理空心跳、结束分片、arguments 被拆成多段以及 usage 只在最后一帧出现的情况。

## 生产建议

1. 为同步和流式真实响应各保留脱敏 fixture 测试。
2. 字段缺失时返回部分 AiMessage，不要因可选 usage 缺失而丢失正文。
3. 对 JSON 类型做防御性判断，厂商可能把 arguments 从字符串改为对象。
4. Parser 不保存跨请求状态；流式累计由 Listener 负责。
5. 原始响应仍保存在同步 `AiMessageResponse.rawText`，日志输出前需要脱敏。

## 常见问题

### 为什么同步有内容，流式为空？

检查 `deltaContentPath`，流式协议通常使用 `choices[0].delta.content` 而不是 message.content。

### 为什么 totalTokens 是 null？

只有 total 路径存在，或 prompt/completion 两者都解析成功时才能得到总量。

### 为什么 ToolCall 参数不完整？

流式 arguments 常被拆分到多个分片。确认 Stream Listener 的聚合路径和 delta ToolCall parser 都与服务格式一致。

## 下一步

- [ChatClient](./chat-client.md)
- [Message 消息](./message.md)
- [Function Call](./function-call.md)

</div>
