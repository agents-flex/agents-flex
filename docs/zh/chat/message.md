<div v-pre>

# Message 消息

## 概述

`Message` 是模型对话中的单条角色消息。Agents-Flex 使用不同子类表达系统指令、用户输入、AI 回复和 Tool
结果，并由 `Prompt` 按协议顺序组合后发送给模型。

```text
SystemMessage   设定规则
UserMessage     提出问题或提供多模态输入
AiMessage       模型回复，或请求调用 Tool
ToolMessage     返回某次 Tool Call 的结果
```

所有 Message 都继承 `Metadata`，可以附加追踪或业务信息；`getTextContent()` 用于提取文本语义，不包含图片、
音频等非文本内容。

## 适用场景

- 为模型设置稳定系统规则；
- 构建多轮 User/Ai 历史；
- 发送图片、音频、视频或文件 URL；
- 处理流式增量、推理内容和 Token Usage；
- 完成 Tool Call 与 ToolMessage 的闭环。

## 快速开始

```java
SimplePrompt prompt = new SimplePrompt();
prompt.setSystemMessage(SystemMessage.of("你是严谨的技术助手"));
prompt.setUserMessage(new UserMessage("解释 Java 虚拟线程"));

AiMessageResponse response = chatModel.chat(prompt);
AiMessage answer = response.getMessage();
System.out.println(answer.getContent());
```

单轮文本可以使用 `new SimplePrompt(text)`，但显式 Message 更适合多模态、Tool Calling 和历史管理。

## SystemMessage

用于角色、规则、输出格式和安全边界：

```java
SystemMessage system = SystemMessage.of(
    "你是订单客服。不得编造订单状态，信息不足时要求用户提供订单号。"
);
```

SystemMessage 只有文本内容。不要把不可信用户输入直接拼进高优先级系统规则；动态数据应放 UserMessage，并在
业务层做边界标记和长度限制。

## UserMessage

### 文本

```java
UserMessage user = new UserMessage("请分析这张销售图");
```

### 图片

```java
user.addImageUrl("https://example.com/chart.png");
user.addImageFile(new File("chart.png"));
user.addImageBytes(imageBytes, "image/png");
```

文件和字节会转换为 Data URI。若模型配置 `supportImageBase64Only=true`，HTTP 图片 URL 在序列化时也会下载并
转换。生产环境需要限制远程 URL 的协议、域名、响应大小和超时，避免 SSRF 与内存风险。

### 音频、视频和文件

```java
user.addAudioUrl(audioUrl);
user.addVideoUrl(videoUrl);
user.addFileUrl(fileUrl);
```

添加 URL 不代表具体模型一定支持。需要让 `BaseChatConfig` 能力声明和服务端协议保持一致。

Tool 不存放在 UserMessage 中，而是通过 `Prompt.addTool(s)` 注册。

## AiMessage

`AiMessage` 同时承载正文、推理、Tool Call、Usage 和模型响应元数据：

| 字段 | 说明 |
| --- | --- |
| `content` | 同步响应正文；流式回调中通常是当前增量 |
| `fullContent` | 流式处理过程中累计的完整正文 |
| `reasoningContent` / `fullReasoningContent` | 当前推理片段与累计推理内容 |
| `toolCalls` | 模型请求执行的 Tool 列表 |
| `finishReason` / `stopReason` | 停止原因 |
| `promptTokens` / `completionTokens` / `totalTokens` | 服务端 Usage |
| `local...Tokens` | 本地 Token 计数器估算值 |
| `refusal`、`annotations`、`logprobs` | 厂商返回的附加信息 |

```java
AiMessage message = response.getMessage();
String text = message.getContent();
int effectiveTokens = message.getEffectiveTotalTokens();
List<ToolCall> calls = message.getToolCalls();
```

`getEffectiveTotalTokens()` 优先使用服务端总数，缺失时使用本地估算。Usage 字段仍可能为空或为 0，取决于服务商。

### 流式消息

```java
public void onMessage(StreamContext context, AiMessageResponse response) {
    AiMessage chunk = response.getMessage();
    System.out.print(chunk.getContent());       // 当前增量
    ui.replace(chunk.getFullContent());         // 截至当前的全文
}
```

不要把每个 `content` 当成完整回答重复保存。框架解析器会累积 `fullContent`，但业务仍需在正常结束时决定保存哪个
版本。

## ToolMessage

模型返回 Tool Call 后，应用执行 Tool，并把结果用相同 ID 回传：

```java
ToolMessage result = new ToolMessage();
result.setToolCallId("call_abc123");
result.setContent("{\"status\":\"success\",\"orderId\":\"1001\"}");
```

通常可以直接使用响应辅助方法：

```java
List<ToolMessage> toolMessages =
    response.executeToolCallsAndGetToolMessages();
```

ToolMessage 必须出现在请求 Tool 的 AiMessage 之后，并保持每个 `toolCallId` 对应。不要把异常堆栈、凭证或无界
大对象直接作为结果返回模型。

## Metadata 与复制

```java
user.putMetadata("source", "web");
Object source = user.getMetadata("source");
```

Metadata 主要供应用、日志和拦截器使用，是否发送给模型取决于具体序列化器。

文本消息实现提供 `copy()`，MemoryPrompt 截断时会复制后再修改正文。不要假设所有嵌套字段都具有任意深度的
深拷贝语义；跨线程或长期缓存前应按业务需要创建不可变快照。

## 生产建议

- 严格保持 System → User/Ai → Tool 的协议顺序；
- 保存历史前移除不需要的推理、超大 Tool 结果和敏感 Metadata；
- 多模态输入先验证类型、大小、URL 与模型能力；
- Tool Message 返回结构化、简短、可供模型判断的结果；
- 流式场景区分增量和累计正文，避免重复拼接；
- Message 是可变对象，不在并发请求间共享修改。

## 常见问题

### Tool 定义为什么不在 UserMessage 上？

当前代码把 Tool 归属于 `Prompt`，通过 `addTool()`、`addTools()` 或扫描方法注册。

### getTextContent() 会包含图片描述吗？

不会。当前文本消息返回 `content`，非文本 URL 需要通过各自 Getter 访问。

### AiMessage 如何判断流式结束？

处理 `StreamResponseListener.onStop()` 和 `onFailure()`，并结合 `finishReason`；当前 AiMessage 没有
`isLastMessage()` 方法。

## 下一步

- [构建 Prompt](./prompt)
- [使用 Memory](./memory)
- [处理 Tool Calling](./tool)
- [序列化 Message](./chat-message-serializer)
- [解析 AI 响应](./ai-message-parser)

</div>
