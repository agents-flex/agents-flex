<div v-pre>

# Prompt 提示词

## 概述

`Prompt` 是一次模型请求的输入容器。它不只是一个字符串，还可以同时携带按角色排列的 `Message`、可调用
`Tool`、按条件生效的 `ToolGroup`、Tool Choice 和框架元数据。

Agents-Flex 提供两个常用实现：

| 实现 | 适用场景 | 消息来源 |
| --- | --- | --- |
| `SimplePrompt` | 单轮问答、多模态、一次 Tool 循环 | 当前对象中的 System/User/Ai/Tool Message |
| `MemoryPrompt` | 多轮对话和持续 Agent | `ChatMemory` 历史 + System + 临时消息 |

## 适用场景

- 单轮摘要、分类和结构化抽取；
- 带系统角色的用户问答；
- 图片、音频、视频或文件输入；
- Tool Calling 的初始请求与 ToolMessage 回传；
- 携带最近历史的客服、助手和 Agent 对话。

## 快速开始

### 单轮 Prompt

```java
SimplePrompt prompt = new SimplePrompt("请总结下面的内容");
prompt.setSystemMessage(SystemMessage.of(
    "你是一名编辑，只返回三条要点。"
));

AiMessageResponse response = chatModel.chat(prompt);
```

`getMessages()` 的顺序是 System、User、可选 Ai、可选 ToolMessage。UserMessage 始终会加入，即使内容为空。

### 多轮 Prompt

```java
ChatMemory memory = new DefaultChatMemory("support:u1001:c2002");
MemoryPrompt prompt = new MemoryPrompt(memory);
prompt.setSystemMessage("你是订单客服助手");

prompt.addUserMessage("我的订单什么时候发货？");
AiMessageResponse response = chatModel.chat(prompt);
prompt.addMessage(response.getMessage());
```

下一轮继续复用同一 Memory，并先加入新的 UserMessage。`ChatModel` 不会自动把响应写入 Memory，业务必须明确
保存需要保留的用户消息、AI 回复和 Tool 结果。

## SimplePrompt

### 消息组成

```java
SimplePrompt prompt = new SimplePrompt();
prompt.setSystemMessage(SystemMessage.of("你是图片分析助手"));
prompt.setUserMessage(new UserMessage("分析图表趋势"));
prompt.addImageUrl("https://example.com/chart.png");
```

它提供图片、音频和视频的便捷方法，实际内容写入内部 `UserMessage`：

```java
prompt.addImageFile(new File("chart.png"));
prompt.addImageBytes(imageBytes, "image/png");
prompt.addAudioUrl(audioUrl);
prompt.addVideoUrl(videoUrl);
```

只有模型配置声明对应能力且服务端实际支持时才能使用。图片文件和字节会转换为 Data URI；当
`supportImageBase64Only` 为 true 时，HTTP 图片 URL 也会下载并转换。

### Tool 调用回传

模型请求 Tool 后，可以构造下一次 Prompt：

```java
SimplePrompt next = new SimplePrompt();
next.setUserMessage(originalUserMessage);
next.setAiMessage(response.getMessage());
next.setToolMessages(toolMessages);
next.setTools(tools);

AiMessageResponse finalResponse = chatModel.chat(next);
```

AiMessage 中的 Tool Call 和对应 ToolMessage 必须保持 `toolCallId` 关联。

## MemoryPrompt

`MemoryPrompt.getMessages()` 按以下顺序组装：

```text
最近 N 条 ChatMemory 消息
        ↓ 可选：复制并截断文本
在开头插入 SystemMessage
        ↓
追加 temporaryMessages
        ↓
返回消息并清空 temporaryMessages
```

常用配置：

| 配置 | 当前默认值 | 说明 |
| --- | --- | --- |
| `maxAttachedMessageCount` | `100` | 从 Memory 取最近多少条消息 |
| `historyMessageTruncateEnable` | `false` | 是否截断历史文本 |
| `historyMessageTruncateLength` | `1000` | 默认截断字符数 |
| `historyMessageTruncateProcessor` | `null` | 自定义摘要或裁剪函数 |

```java
prompt.setMaxAttachedMessageCount(20);
prompt.setHistoryMessageTruncateEnable(true);
prompt.setHistoryMessageTruncateLength(2000);
```

截断在消息副本上完成，不修改 Memory 中原始文本。计数单位是 Message 条数，不是对话轮数，也不是 Token。

临时消息只参与下一次 `getMessages()`：

```java
ToolMessage toolResult = new ToolMessage();
toolResult.setToolCallId("call_123");
toolResult.setContent("{\"status\":\"ok\"}");
prompt.addMessageTemporary(toolResult);
```

## Tool 与 ToolGroup

Tool 属于 Prompt，而不是 UserMessage：

```java
prompt.addTool(weatherTool);
prompt.addTools(toolList);
prompt.addToolsFromObject(toolBean, "queryWeather");
prompt.setToolChoice("auto");
```

`getToolsMap()` 按名称生成 Map；同名 Tool 后出现的值会覆盖前一个，因此应用应在注册阶段检测命名冲突。

ToolGroup 用于按上下文动态追加工具和系统提示：

```java
prompt.addToolGroup(billingTools);
prompt.addToolGroup(adminTools);
```

具体匹配与合并规则见 [ToolGroup 工具组](./tool-group)。

## 如何选择 Prompt

| 需求 | 推荐实现 |
| --- | --- |
| 单个字符串 | `chatModel.chat(String)` 或 `SimplePrompt` |
| System + User | `SimplePrompt` |
| 图片、音频、视频 | `SimplePrompt` |
| 一次 Tool Calling 闭环 | `SimplePrompt` |
| 多轮历史 | `MemoryPrompt` |
| 外部持久化历史 | `MemoryPrompt` + 自定义 `ChatMemory` |

## 生产建议

- SystemMessage 放稳定规则，UserMessage 放本次输入，不要混淆信任等级；
- 不把全部历史无限附加，按 Token 预算裁剪、摘要或分段；
- 多模态 URL 视为外部输入，限制协议、域名、大小和下载超时；
- Tool 只注册当前用户有权使用的集合，Tool 描述不是权限控制；
- Prompt 对象和内部 Message 都是可变对象，不要跨并发请求共享并修改；
- 对 Tool 循环显式保存 AiMessage 和 ToolMessage，保持顺序与 ID。

## 常见问题

### MemoryPrompt 会自动保存模型回复吗？

不会。它只从 Memory 读取和向 Memory 写入业务明确添加的消息。

### 临时消息什么时候清除？

`getMessages()` 把它们加入返回列表后立即清空。需要重复发送时应重新添加。

### 为什么图片没有发送？

检查具体模型的 `supportImage` 配置和服务端能力；仅把 URL 加入 Prompt 不能让纯文本模型支持图片。

### Prompt 可以复用吗？

可以在单线程流程中继续修改，但并发请求应使用独立实例，避免消息和工具互串。

## 下一步

- [理解 Message](./message)
- [使用 Memory](./memory)
- [构建 Tool](./tool-build)
- [使用 ToolGroup](./tool-group)
- [使用 PromptTemplate](./prompt-template)

</div>
