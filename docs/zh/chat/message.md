# Message 消息
<div v-pre>


## 概述

在 Agents-Flex 框架中，`Message` 是表示**与大语言模型（LLM）交互内容**的基础抽象。它不仅支持纯文本，还原生支持**多模态输入（图片/音频/视频）**、**工具调用（Function Calling）** 和**结构化元数据**。

消息体系采用**面向角色的设计**，定义了四种核心消息类型：
- `SystemMessage`：系统指令
- `UserMessage`：用户输入（支持多模态与工具定义）
- `AiMessage`：AI 回复（含生成文本、工具调用请求、推理过程等）
- `ToolMessage`：工具执行结果

所有消息均继承自 `Message`，具备**统一文本提取接口**和**深拷贝能力**，便于日志、缓存与上下文管理。


## 核心基类

### 1. `Message`（抽象基类）
```java
public abstract class Message extends Metadata {
    public abstract String getTextContent();
}
```
- 继承 `Metadata`，支持附加任意元数据（如 traceId、source）
- **`getTextContent()`**：提取纯文本内容（用于日志、监控等）

### 2. `AbstractTextMessage<T>`（文本消息基类）
```java
public abstract class AbstractTextMessage<T extends AbstractTextMessage<?>>
    extends Message implements Copyable<T> {
    protected String content;
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    @Override public String getTextContent() { return content; }
    @Override public abstract T copy();
}
```
- 提供 `content` 字段存储文本
- 实现 `Copyable` 接口，支持深拷贝


## 消息类型详解

### 1. `SystemMessage` 系统指令
**用途**：设置 AI 行为准则、角色设定
**特点**：
- 通常置于对话开头
- 仅含纯文本，无多模态或工具能力

```java
SystemMessage sys = new SystemMessage("你是一名专业的客服助手");
// 或
SystemMessage sys = SystemMessage.of("你是一名专业的客服助手");
```

---

### 2. `UserMessage` 用户输入

**用途**：表示用户发起的请求，支持**多模态**与**工具定义**

#### 核心能力
| 能力 | 方法 | 说明 |
|------|------|------|
| **文本内容** | `setContent(content)` | 基础文本输入 |
| **图片** | `addImageUrl(url)`<br>`addImageFile(file)`<br>`addImageBytes(bytes, mimeType)` | 支持 URL、本地文件、字节数组；自动转 Base64（若模型仅支持 Base64） |
| **音频/视频** | `addAudioUrl(url)`<br>`addVideoUrl(url)` | 声明多模态输入（需模型支持） |
| **工具定义** | `addTool(tool)`<br>`addToolsFromClass(clazz, ...)` | 注册可供 LLM 调用的函数 |
| **工具选择** | `setToolChoice("auto"/"required"/"tool_name")` | 控制工具调用行为 |

#### 多模态处理示例
```java
UserMessage userMsg = new UserMessage("请分析这张图");
userMsg.addImageUrl("https://example.com/chart.png");
userMsg.addImageFile(new File("/local/photo.jpg"));

// 框架会根据 ChatConfig 自动处理 Base64 转换
SimplePrompt prompt = new SimplePrompt();
prompt.setUserMessage(userMsg);

ChatModel chatModel = new OpenAIChatModel(...config);
AiMessageResponse  response = chatModel.chat(prompt);
```

#### 工具注册示例
```java
UserMessage userMsg = new UserMessage("请分析这张图");

// 手动添加工具
userMsg.addTool(new CalculatorTool());

// 从类反射注册
userMsg.addToolsFromClass(MathUtils.class, "add", "multiply");
```


### 3. `AiMessage` AI 回复

**用途**：封装 LLM 的完整响应，包括**生成文本**、**工具调用请求**、**Token 统计**等

#### 核心字段
| 字段 | 说明 |
|------|------|
| `content` | **本次增量内容**（流式调用中为 delta） |
| `fullContent` | **截至当前的完整文本**（由框架自动累积） |
| `toolCalls` | 工具调用请求列表（`ToolCall` 对象） |
| `finishReason` | 结束原因（如 `"stop"`, `"tool_calls"`） |
| `promptTokens` / `completionTokens` | Token 消耗统计 |
| `reasoningContent` | 推理模式输出（如 Qwen 的 thinking content） |

#### 关键方法
```java
// 判断是否为最后一条消息（流式结束）
boolean isLast = aiMessage.isLastMessage();

// 获取有效总 Token 数（优先使用服务端返回值）
int tokens = aiMessage.getEffectiveTotalTokens();
```

> ✅ **流式使用提示**：
> - 实时输出用 `content`（增量）
> - 全文处理用 `fullContent`（完整）


### 4. `ToolMessage` 工具执行结果

**用途**：将工具执行结果反馈给 LLM，完成工具调用闭环

```java
ToolMessage result = new ToolMessage();
result.setToolCallId("call_abc123"); // 对应 AiMessage 中的 ToolCall.id
result.setContent("{\"status\": \"success\", \"orderId\": \"1001\"}");
```

> **典型流程**：
> `UserMessage`（定义工具） → `AiMessage`（请求调用） → `ToolMessage`（返回结果）

一般情况下，ToolMessage 是通过调用 `AiMessageResponse.executeToolCallsAndGetToolMessages()` 方法获得的。




</div>
