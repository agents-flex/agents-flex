# Prompt 提示词


## 概述

在 Agents-Flex 框架中，`Prompt` 是表示**大语言模型（LLM）输入上下文**的核心抽象。它不仅包含用户输入，还可整合**系统指令、多轮对话历史、工具定义、多模态内容（图片/音频/视频）** 等丰富信息。

框架提供两种主要实现：
- **`SimplePrompt`**：适用于**单轮对话**或**无状态交互**场景
- **`MemoryPrompt`**：适用于**多轮对话**，自动管理对话历史与上下文记忆

所有 `Prompt` 实例均可作为参数传递给 `ChatModel.chat(prompt, options)`，实现标准化、结构化调用。

---

## 核心接口：`Prompt`

```java
public abstract class Prompt extends Metadata {
    public abstract List<Message> getMessages();
}
```

- 继承 `Metadata`，支持添加自定义元数据（如调试信息、业务 ID）
- **`getMessages()`** 是唯一抽象方法，返回按时间顺序排列的 `Message` 列表（系统消息 → 用户消息 → AI 消息 → 工具消息）


##  SimplePrompt：简单提示词构建器

适用于**单次请求**场景，无需维护对话历史。

### 构造方式
```java
// 空提示（需后续填充）
SimplePrompt prompt = new SimplePrompt();

// 直接设置用户消息
SimplePrompt prompt = new SimplePrompt("你好，请介绍一下你自己");
```

### 核心功能

#### (1) 系统消息（System Message）
```java
prompt.setSystemMessage(new SystemMessage("你是一名专业的 Java 开发工程师"));
// 或简写
prompt.setSystemMessage("你是一名专业的 Java 开发工程师");
```

#### (2) 多模态输入
```java
// 图片
prompt.addImageUrl("https://example.com/image.jpg");
prompt.addImageFile(new File("/path/to/image.png"));
prompt.addImageBytes(imageBytes, "image/jpeg");

// 音频
prompt.addAudioUrl("https://example.com/audio.mp3");

// 视频
prompt.addVideoUrl("https://example.com/video.mp4");
```

> ✅ 框架会根据 `ChatConfig` 中的 `supportImage` 等能力声明，自动决定是否发送多模态内容。

#### (3) 工具调用（Function Calling）
```java
// 添加单个工具
prompt.addTool(new CalculatorTool());

// 添加多个工具
prompt.addTools(List.of(tool1, tool2));

// 从类或对象反射注册工具
prompt.addToolsFromClass(MathUtils.class, "add", "multiply");
prompt.addToolsFromObject(myService, "processOrder");
```

#### (4) 强制工具选择（tool_choice）
```java
prompt.setToolChoice("auto");      // 默认，模型自主决定
prompt.setToolChoice("required");  // 必须调用工具
prompt.setToolChoice("my_tool");   // 强制调用指定工具
```

### 消息结构
调用 `getMessages()` 后返回顺序为：
```
[ SystemMessage (可选), UserMessage, ToolMessage... (可选) ]
```

> ⚠️ **注意**：`SimplePrompt` **不包含 AI 历史消息**，仅用于发起新对话。


## MemoryPrompt：带记忆的提示词

适用于**多轮对话**场景，自动管理对话上下文。

### 构造方式
```java
// 使用默认内存（内存中存储，无持久化）
MemoryPrompt prompt = new MemoryPrompt();

// 使用自定义 ChatMemory（如 Redis、数据库）
MemoryPrompt prompt = new MemoryPrompt(myRedisChatMemory);
```

### 核心机制

#### (1) 对话历史管理
- 通过 `addMessage(Message msg)` 添加用户/AI 消息，自动存入 `ChatMemory`
- 调用 `getMessages()` 时，自动按配置截取最近 N 条消息

#### (2) 系统消息
```java
prompt.setSystemMessage("你正在协助用户完成订单查询");
```
> 系统消息会**始终置于消息列表开头**（若存在）

#### (3) 临时消息（Temporary Messages）
用于**过程性消息**（如工具调用结果），**不存入历史**：
```java
// 添加工具调用结果（本次调用后自动清除）
prompt.addMessageTemporary(new ToolMessage("订单已创建", "create_order"));
```

#### (4) 历史消息控制
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `maxAttachedMessageCount` | 10 | 最多携带多少条历史消息（不含 system） |
| `historyMessageTruncateEnable` | false | 是否启用历史消息截断 |
| `historyMessageTruncateLength` | 1000 | 截断长度（字符数） |
| `historyMessageTruncateProcessor` | null | 自定义截断处理器（如摘要、关键词提取） |

**示例：限制上下文长度**
```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.setMaxAttachedMessageCount(6); // 最多6轮对话
prompt.setHistoryMessageTruncateEnable(true);
prompt.setHistoryMessageTruncateLength(500); // 每条消息最多500字符
```

### 消息结构
调用 `getMessages()` 后返回顺序为：
```
[ SystemMessage (可选), Historic Messages..., Temporary Messages... ]
```

> ✅ **临时消息在使用后自动清空**，符合“一次性”语义。



## 最佳实践

### 1. 选择合适的 Prompt 类型
- **单次问答、工具调用** → `SimplePrompt`
- **聊天机器人、多轮任务** → `MemoryPrompt`

### 2. 控制上下文长度
- 使用 `MemoryPrompt` 的截断功能避免 token 超限
- 对长文档，建议先摘要再放入上下文

### 3. 多模态内容按需添加
- 仅在模型支持时添加图片/音频（参考 `ChatConfig.supportXxx`）
- 本地文件会自动转为 Base64（受 `supportImageBase64Only` 控制）


## 常见问题

**Q：能否混合使用 `SimplePrompt` 和 `MemoryPrompt`？**

A：可以，但不推荐。应根据场景统一选择一种。

**Q：`MemoryPrompt` 的历史消息会无限增长吗？**

A：不会。默认最多携带 10 条，可通过 `setMaxAttachedMessageCount()` 控制。

**Q：如何实现“清空对话历史”？**

A：调用 `memory.clear()`（若使用自定义 `ChatMemory`）或创建新的 `MemoryPrompt` 实例。

**Q：系统消息会被计入历史吗？**

A：不会。系统消息每次调用都显式传递，不存入 `ChatMemory`。
