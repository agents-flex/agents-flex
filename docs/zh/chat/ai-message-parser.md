# AiMessageParser AI 消息解析器
<div v-pre>


## 概述

`AiMessageParser` 是 Agents-Flex 中用于**将 LLM（大语言模型）原始响应解析为统一内部消息模型 `AiMessage`** 的核心组件。它解决了不同模型返回格式差异的问题，使上层逻辑无需关心底层协议细节。

该解析器同时支持：

- **同步响应**（完整 JSON 对象）
- **流式响应**（增量 JSON 片段，如 SSE）
- **多字段提取**：内容、推理内容、工具调用、Token 统计、结束原因等
- **灵活路径配置**：通过 `JSONPath` 动态指定字段位置


## 核心接口

```java
public interface AiMessageParser<T> {
    AiMessage parse(T jsonObject, ChatContext context);
}
```

- **泛型 `T`**：表示原始响应类型（如 `JSONObject`、`JsonNode` 等）
- **输入**：
    - `jsonObject`：LLM 返回的原始数据（已解析为对象）
    - `ChatContext`：包含请求上下文（如是否流式、模型配置等）
- **输出**：标准化的 `AiMessage` 对象

> ✅ 最终 `AiMessage` 会被封装进 `AiMessageResponse`，供应用层消费。


## 默认实现：`DefaultAiMessageParser`

这是 Agents-Flex 提供的**高度可配置 JSON 解析器**，专为基于 JSON 的 LLM API（如 OpenAI、Ollama、Qwen 等）设计。

### `DefaultAiMessageParser` 核心设计：路径驱动（Path-Driven）

所有字段提取均通过 `JSONPath` 配置，实现**格式无关**：

| 字段 | JSONPath 示例（OpenAI） | 说明 |
|------|------------------------|------|
| `content` | `$.choices[0].message.content` | 主要文本内容 |
| `deltaContent` | `$.choices[0].delta.content` | 流式增量内容 |
| `toolCalls` | `$.choices[0].message.tool_calls` | 工具调用列表 |
| `deltaToolCalls` | `$.choices[0].delta.tool_calls` | 流式工具调用 |
| `finishReason` | `$.choices[0].finish_reason` | 结束原因（stop/length/tool_calls） |
| `promptTokens` | `$.usage.prompt_tokens` | 输入 Token 数 |
| `completionTokens` | `$.usage.completion_tokens` | 输出 Token 数 |

> 💡 若某字段在响应中不存在（如 Ollama 不返回 `total_tokens`），解析器会自动跳过或计算（`total = prompt + completion`）。


###  流式 vs 同步处理

解析器根据 `ChatContext.getOptions().isStreaming()` 自动选择路径：

```java
if (context.getOptions().isStreaming()) {
    // 使用 delta 路径（如 delta.content）
    aiMessage.setContent((String) deltaContentPath.eval(rootJson));
} else {
    // 使用完整消息路径（如 message.content）
    aiMessage.setContent((String) contentPath.eval(rootJson));
}
```

- **流式响应**：每次只解析增量片段（可能为 `null` 或空字符串）
- **同步响应**：解析完整响应体

> 在流式场景中，`BaseStreamClientListener` 负责**合并所有增量**到完整消息（`fullMessage.merge(delta)`）。


### 工具调用解析（`callsParser`）

工具调用结构因模型而异，因此使用**可插拔的 `JSONArrayParser`**：

```java
aiMessageParser.setCallsParser(toolCalls -> {
    List<ToolCall> toolInfos = new ArrayList<>();
    for (JSONObject callJson : toolCalls) {
        ToolCall call = new ToolCall();
        call.setId(callJson.getString("id"));
        call.setName(functionJson.getString("name"));
        call.setArgsString(...); // 支持 String 或 Map
        toolInfos.add(call);
    }
    return toolInfos;
});
```

- **输入**：`tool_calls` 数组（`JSONArray`）
- **输出**：标准化 `List<ToolCall>`
- **灵活性**：可适配 OpenAI (`function`)、Claude (`tools`) 等不同格式

---

### Token 统计容错

部分模型（如 Ollama）**不返回 `total_tokens`**，解析器自动补偿：

```java
if (totalTokensPath != null) {
    aiMessage.setTotalTokens((Integer) totalTokensPath.eval(...));
} else if (promptTokens != null && completionTokens != null) {
    aiMessage.setTotalTokens(promptTokens + completionTokens); // 自动计算
}
```


## OpenAI 兼容解析器

Agents-Flex 提供开箱即用的 OpenAI 解析器：

```java
AiMessageParser<JSONObject> openaiParser = DefaultAiMessageParser.getOpenAIMessageParser();
```

该方法预配置了所有标准 OpenAI 字段路径，包括：

- 内容路径（`content` / `delta.content`）
- 工具调用路径（`tool_calls` / `delta.tool_calls`）
- Token 统计路径（`usage` 下所有字段）
- 结束原因（`finish_reason`）

> ✅ **兼容所有 OpenAI-compatible 服务**：Azure OpenAI、Ollama、LocalAI、DeepSeek、Qwen 等。


## 与整体架构的集成

### 1 在 `OpenAIChatClient` 中的使用

```java
protected AiMessageResponse parseResponse(String response) {
    JSONObject json = JSON.parseObject(response);
    AiMessage aiMessage = getAiMessageParser().parse(json, context); // 👈 调用解析器
    LocalTokenCounter.computeAndSetLocalTokens(..., aiMessage);
    return new AiMessageResponse(context, response, aiMessage);
}
```

一次解析完整响应

### 2 在流式监听器中的使用

```java
public void onMessage(StreamClient client, String response) {
    JSONObject json = JSON.parseObject(response);
    AiMessage delta = messageParser.parse(json, chatContext); // 👈 解析增量
    fullMessage.merge(delta); // 合并到完整消息
}
```
多次解析增量，最终合并完整消息。


## 自定义解析器

### 场景 1：支持新模型（如 Claude）

Claude 的响应结构与 OpenAI 不同：

```json
{
  "content": [{"type": "text", "text": "Hello"}],
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 10, "output_tokens": 5 }
}
```

**自定义步骤**：

```java
DefaultAiMessageParser claudeParser = new DefaultAiMessageParser();
claudeParser.setContentPath(JSONPath.of("$.content[0].text"));
claudeParser.setFinishReasonPath(JSONPath.of("$.stop_reason"));
claudeParser.setPromptTokensPath(JSONPath.of("$.usage.input_tokens"));
claudeParser.setCompletionTokensPath(JSONPath.of("$.usage.output_tokens"));
// 禁用 tool_calls（若 Claude 使用不同格式）
claudeParser.setToolCallsJsonPath(null);
```

### 场景 2：实现全新解析器

若模型返回非 JSON 格式（如 XML、Protobuf），需实现 `AiMessageParser` 接口：

```java
public class MyXmlAiMessageParser implements AiMessageParser<String> {
    @Override
    public AiMessage parse(String xmlStr, ChatContext context) {
        // 自定义 XML 解析逻辑
        AiMessage msg = new AiMessage();
        msg.setContent(extractContent(xmlStr));
        return msg;
    }
}
```

> ⚠️ 注意：需配套修改 `ChatClient` 的响应处理逻辑。


## 配置项详解

| 配置项 | 用途 | 默认值（OpenAI） |
|--------|------|------------------|
| `contentPath` | 同步内容路径 | `$.choices[0].message.content` |
| `deltaContentPath` | 流式内容路径 | `$.choices[0].delta.content` |
| `reasoningContentPath` | 推理内容（如 o1） | `$.choices[0].message.reasoning_content` |
| `toolCallsJsonPath` | 同步工具调用 | `$.choices[0].message.tool_calls` |
| `deltaToolCallsJsonPath` | 流式工具调用 | `$.choices[0].delta.tool_calls` |
| `finishReasonPath` | 结束原因 | `$.choices[0].finish_reason` |
| `promptTokensPath` | 输入 Token | `$.usage.prompt_tokens` |
| `completionTokensPath` | 输出 Token | `$.usage.completion_tokens` |
| `callsParser` | 工具调用解析器 | OpenAI 格式解析器 |

---



## 总结

`AiMessageParser` 是 Agents-Flex **响应标准化层**的核心，它：

- **解耦模型差异**：统一返回 `AiMessage`，屏蔽底层协议
- **支持流式/同步**：一套逻辑适配两种调用模式
- **高度可配置**：通过路径和解析器插件适配任意 JSON 格式
- **容错设计**：自动处理缺失字段（如 Token 统计）

> 📘 **建议**：除非对接非 JSON 或非 OpenAI 协议模型，否则直接使用 `DefaultAiMessageParser` + 路径配置即可。



</div>
