# ChatMessageSerializer 消息序列化

<div v-pre>


## 概述

`ChatMessageSerializer` 是 Agents-Flex 中用于**将内部消息模型转换为 LLM（大语言模型）可识别的请求格式**的核心组件。其核心职责是：

- 将 `Message` 对象列表（如 `UserMessage`、`AiMessage`）序列化为模型所需的聊天消息数组（例如 OpenAI 的 `[{"role":"user", "content":"..."}]`）
- 将工具（`Tool`）定义序列化为模型支持的函数调用格式（如 OpenAI 的 `tools` 字段）

尽管接口名为“消息序列化器”，但它**同时承担了工具/函数定义的序列化职责**，因为这些通常作为聊天请求的一部分（如 `tools` 或 `functions` 字段）一起发送。


## 接口定义

```java
public interface ChatMessageSerializer {
    List<Map<String, Object>> serializeMessages(List<Message> messages, ChatConfig config);
    List<Map<String, Object>> serializeTools(List<Tool> tools, ChatConfig config);

    // 默认方法：从 UserMessage 提取工具列表
    default List<Map<String, Object>> serializeTools(UserMessage userMessage, ChatConfig config) {
        return serializeTools(userMessage == null ? null : userMessage.getTools(), config);
    }
}
```

### 方法说明

| 方法 | 用途 |
|------|------|
| `serializeMessages` | 将对话历史消息转换为 LLM 可解析的结构化数组 |
| `serializeTools` | 将函数定义（工具）转换为 LLM 的函数调用描述格式 |
| `serializeTools(UserMessage, ...)` | 便捷方法，自动从用户消息中提取工具列表 |


## 默认实现：`OpenAIChatMessageSerializer`

`OpenAIChatMessageSerializer` 实现完整支持 **OpenAI 兼容协议**，包括：

- 标准角色消息（user/assistant/system/tool）
- 多模态内容（文本 + 图片/音频/视频 URL）
- 函数调用（Function Calling）与工具调用（Tool Calling）
- 复杂参数结构（嵌套对象、数组、枚举、必填字段）

### 1 消息序列化（`serializeMessages`）

#### 支持的消息类型

| 内部类型 | 转换结果（OpenAI 格式） |
|--------|------------------------|
| `UserMessage` | `{ "role": "user", "content": "..." }` |
| `AiMessage` | `{ "role": "assistant", "content": "...", "tool_calls": [...] }` |
| `SystemMessage` | `{ "role": "system", "content": "..." }` |
| `ToolMessage` | `{ "role": "tool", "content": "...", "tool_call_id": "..." }` |

#### 多模态支持（仅限 `UserMessage`）

当 `UserMessage` 包含图像、音频或视频 URL 时，自动转换为 OpenAI 的多模态格式：

```json
[
  { "type": "text", "text": "Describe this image" },
  { "type": "image_url", "image_url": { "url": "https://..." } },
  { "type": "audio_url", "audio_url": { "url": "https://..." } }
]
```

> 🔒 **安全处理**：若 `config.isSupportImageBase64Only()` 为 `true` 且图片 URL 是 HTTP 链接，会自动下载并转换为 `data:image/jpeg;base64,...` 格式。

#### 函数调用响应（`AiMessage`）

当 AI 响应包含 `toolCalls` 时：
- 自动清空 `content` 字段（避免模型将推理过程误认为输出）
- 添加 `tool_calls` 数组，格式如下：

```json
{
  "role": "assistant",
  "content": "",
  "tool_calls": [
    {
      "id": "call_123",
      "type": "function",
      "function": {
        "name": "get_weather",
        "arguments": "{\"location\": \"Beijing\"}"
      }
    }
  ]
}
```


### 2 工具序列化（`serializeTools`）

将 `Tool` 对象转换为 OpenAI 的 `tools` 结构：

```json
[
  {
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "Get weather for a location",
      "parameters": {
        "type": "object",
        "properties": {
          "location": {
            "type": "string",
            "description": "City name",
            "enum": ["Beijing", "Shanghai"]
          }
        },
        "required": ["location"]
      }
    }
  }
]
```

#### 高级参数支持

- **嵌套对象**：通过 `Parameter.getChildren()` 支持层级结构
- **数组类型**：自动识别单值数组（`string[]`）与对象数组（`{...}[]`）
- **必填字段**：通过 `parameter.isRequired()` 控制 `required` 列表
- **枚举值**：通过 `parameter.getEnums()` 设置合法取值范围

> ⚠️ **开关控制**：模型配置里的  `chatConfig.getSupportTool()` 为 `false`，则返回 `null`，**完全跳过工具发送**，适用于不支持 Function Calling 的模型。


## 扩展与定制

### 1 自定义序列化器

若需支持非 OpenAI 协议（如 Claude、Gemini、自研 LLM），可实现 `ChatMessageSerializer` 接口：

```java
public class ClaudeMessageSerializer implements ChatMessageSerializer {
    @Override
    public List<Map<String, Object>> serializeMessages(List<Message> messages, ChatConfig config) {
        // 转换为 Anthropic 格式，例如合并 system message 到 system 参数
    }

    @Override
    public List<Map<String, Object>> serializeTools(List<Tool> tools, ChatConfig config) {
        // Claude 使用不同的 tools 格式
    }
}
```

### 2 注入到请求构建器

在 `OpenAIChatRequestSpecBuilder` 中设置自定义序列化器：

```java
OpenAIChatRequestSpecBuilder builder = new OpenAIChatRequestSpecBuilder(
    new MyCustomMessageSerializer()
);
```

---

##  与整体架构的集成

`ChatMessageSerializer` 被 `ChatRequestSpecBuilder` 调用，用于构建请求体：

```java
// 在 OpenAIChatRequestSpecBuilder.buildRequestBody() 中
map.set("messages", chatMessageSerializer.serializeMessages(messages, config))
   .setIfNotEmpty("tools", chatMessageSerializer.serializeTools(userMessage, config));
```

- **完全解耦**：`ChatClient` 仅消费最终 JSON，不关心序列化细节
- **上下文感知**：`ChatConfig` 提供模型能力信息（如是否支持图片、工具等），用于条件渲染



## 总结

`ChatMessageSerializer` 是 Agents-Flex **消息协议适配层**的关键组件，它：

- **屏蔽模型差异**：统一内部 `Message` 模型，适配不同 LLM 的输入格式
- **支持高级特性**：多模态、函数调用、复杂参数结构
- **安全可控**：通过 `ChatConfig` 动态启用/禁用特性
- **易于扩展**：通过接口实现支持任意 LLM 协议

> 📘 **建议**：除非对接非 OpenAI 协议模型，否则直接使用 `OpenAIChatMessageSerializer` 即可满足绝大多数场景。




</div>
