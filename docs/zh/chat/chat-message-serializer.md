---
title: ChatMessageSerializer 消息序列化
description: 将 Message 与 Tool 转换为模型协议所需的结构化请求字段。
---

# ChatMessageSerializer 消息序列化

<div v-pre>

## 概述

`ChatMessageSerializer` 负责把统一的 `Message` 和 `Tool` 对象转换为具体模型协议的数据结构。它不负责网络请求，也不负责拼装整个 Body；`ChatRequestSpecBuilder` 会把序列化结果放入 `messages` 和 `tools` 等字段。

普通 OpenAI 兼容模型使用内置实现即可。只有模型的 role、多模态内容或 Tool Schema 格式不兼容时才需要扩展。

## 适用场景

- 新协议使用不同的 system/user/assistant/tool 表示方式。
- 厂商要求把 SystemMessage 放在顶层字段而不是 messages 数组。
- 多模态 content part 的字段名或编码方式不同。
- Tool 参数 Schema 与 OpenAI function calling 不兼容。

如果只是响应字段不同，应扩展 [AiMessageParser](./ai-message-parser.md)，不是 Serializer。

## 快速开始

实现两个方向的请求序列化：

```java
public class MyMessageSerializer implements ChatMessageSerializer {
    @Override
    public List<Map<String, Object>> serializeMessages(
        List<Message> messages, BaseChatConfig config) {
        return convertMessages(messages);
    }

    @Override
    public List<Map<String, Object>> serializeTools(
        List<Tool> tools, BaseChatConfig config) {
        return convertTools(tools);
    }
}
```

注入 OpenAI Request Builder：

```java
OpenAIChatRequestSpecBuilder builder =
    new OpenAIChatRequestSpecBuilder(new MyMessageSerializer());
model.setChatRequestSpecBuilder(builder);
```

## 核心接口

```java
List<Map<String, Object>> serializeMessages(
    List<Message> messages, BaseChatConfig config);

List<Map<String, Object>> serializeTools(
    List<Tool> tools, BaseChatConfig config);
```

便捷方法 `serializeTools(Prompt, config)` 读取 `prompt.getTools()`。Tool 属于 Prompt，不属于 UserMessage。

## OpenAI 默认映射

| 内部对象 | 协议结果 |
| --- | --- |
| `SystemMessage` | `role=system` |
| `UserMessage` | `role=user`，可含多模态 content parts |
| `AiMessage` | `role=assistant`，可含 reasoning 与 tool_calls |
| `ToolMessage` | `role=tool` 与 `tool_call_id` |
| `Tool` | `type=function`、名称、描述和参数 JSON Schema |

`Parameter` 的 `children` 用于对象属性，`itemsParameter` 用于数组元素，`required` 和 `enums` 会进入 Schema。配置声明不支持 Tool 时，默认实现不发送工具定义。

多模态是否序列化取决于 `BaseChatConfig` 的能力声明。对于只接受 Base64 图片的服务，默认实现可能下载远程图片并转换；这会引入网络访问、内容大小和超时风险，应在生产环境限制来源。

## 与请求构建器的关系

```text
Prompt.getMessages() -> serializeMessages -> body.messages
Prompt.getTools()    -> serializeTools    -> body.tools
```

ToolGroup 和 ToolSearch 会在请求准备阶段把本轮可见工具解析到请求级 Prompt，Serializer 只处理最终列表，不负责匹配与搜索。

## 生产建议

1. Serializer 保持纯函数式，不修改输入 Message、Tool 或 Config。
2. 未支持的消息类型应明确拒绝或记录，避免静默丢失历史。
3. 测试 System、ToolCall/ToolMessage、多模态、空内容和嵌套参数。
4. Schema 尽量小而明确，过度嵌套会增加 Token 并降低模型填参稳定性。

## 常见问题

### 为什么工具没有出现在请求里？

确认工具在 Prompt 上，配置支持 Tool，并检查本轮 ToolGroup/ToolSearch 解析结果。

### Serializer 能读取 ChatContext 吗？

接口只接收输入对象与 Config。请求级策略应在进入 Serializer 前由 Builder 或 Interceptor 完成。

## 下一步

- [ChatRequestSpecBuilder](./chat-request-spec-builder.md)
- [Message 消息](./message.md)
- [Function Call](./function-call.md)

</div>
