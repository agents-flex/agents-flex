# ChatModel

## 概述
ChatModel 是 Agents-Flex 框架中用于与大语言模型（LLM）交互的核心接口。它提供统一的同步与流式调用方式，支持 OpenAI、DeepSeek 等 OpenAI 兼容协议的模型，并通过责任链模式集成日志、可观测性、认证等横切关注点。

感谢指出！以下是**修正后的用户使用手册**，已按您的要求：

- **准确描述 `StreamResponseListener` 接口行为**
- **移除所有拦截器相关内容**（留待独立章节）
- **保持其他结构清晰、实用**


本手册面向**开发者**，涵盖：
- 快速上手
- 核心接口说明
- 配置与使用示例
- 最佳实践建议

> ⚠️ **注意**：拦截器、责任链、可观测性等高级扩展机制将在《高级扩展指南》中单独说明，本手册聚焦基础使用。

---

## 快速上手

### 1. 引入依赖（以 Maven 为例）
```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.x.x</version>
</dependency>
```
具体版本号请查看：https://search.maven.org/artifact/com.agentsflex/parent

### 2. 创建模型配置（以 OpenAI 为例）
```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey("your-api-key");
config.setModel("gpt-4o");
```

### 3. 实例化 ChatModel
```java
ChatModel chatModel = new OpenAIChatModel(config);
```

### 4. 同步调用（简单文本）
```java
String response = chatModel.chat("你好，今天过得怎么样？");
System.out.println(response); // 输出完整回复
```

### 5. 流式调用（实时逐片段接收）
```java
chatModel.chatStream("请用 Java 写一个单例模式", new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        // 使用 fullContent 获取当前已接收的完整内容
        String fullText = response.getMessage().getFullContent();
        String delta = response.getMessage().getContent(); // 仅本次增量

        System.out.print(delta); // 实时输出增量（更流畅）
        // 或 System.out.println(fullText); // 每次输出完整内容（覆盖式）
    }

    @Override
    public void onStart(StreamContext context) {
        System.out.println("[流式开始]");
    }

    @Override
    public void onStop(StreamContext context) {
        System.out.println("\n[流式正常结束]");
    }

    @Override
    public void onFailure(StreamContext context, Throwable throwable) {
        System.err.println("流式调用失败: " + throwable.getMessage());
    }
});
```

##  ChatModel核心接口说明

### `ChatModel` 主要方法

| 方法 | 说明 |
|------|------|
| `String chat(String prompt)` | 最简同步调用 |
| `String chat(String prompt, ChatOptions options)` | 带选项的同步调用 |
| `AiMessageResponse chat(Prompt prompt, ChatOptions options)` | 返回完整响应对象 |
| `void chatStream(String prompt, StreamResponseListener listener)` | 流式调用（默认选项） |
| `void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options)` | 带选项的流式调用 |

> 💡 推荐使用 `Prompt` 对象构建多轮对话，而非纯字符串。

---

## `StreamResponseListener` 详解

流式响应通过回调方式处理，接口定义如下：

```java
public interface StreamResponseListener {
    // 流式开始时调用（可选）
    default void onStart(StreamContext context) {}

    // 每收到一个响应片段时调用（必须实现）
    void onMessage(StreamContext context, AiMessageResponse response);

    // 流式正常结束时调用（可选）
    default void onStop(StreamContext context) {}

    // 发生错误时调用（可选，默认记录日志）
    default void onFailure(StreamContext context, Throwable throwable) {}
}
```

### 各回调方法说明

| 方法 | 触发时机 | 用途 |
|------|--------|------|
| `onStart` | 建立连接后、首个消息前 | 初始化状态、打印提示等 |
| `onMessage` | 每收到一个 LLM 返回的增量片段 | 拼接内容、实时渲染、流式输出 |
| `onStop` | LLM 正常结束流式响应 | 清理资源、标记完成 |
| `onFailure` | 网络错误、超时、服务异常等 | 错误处理、告警、重试 |

只要调用 start() 后，onMessage() 可能会被多次调用，直到流式结束。 onStart() 和 onStop() 可用于初始化、清理工作，他们是 100% 触发的，onFailure() 仅用于错误处理。

##  ChatOptions 配置


`ChatOptions` 用于精细控制 LLM 的生成行为（如温度、长度、格式等）。它支持 **Java Bean 风格设值** 和 **链式 Builder 模式**，推荐使用后者以提升可读性。

> ⚠️ **重要提示**：不同模型厂商（OpenAI、DeepSeek、Qwen 等）对参数的支持和默认值可能不同。未被支持的参数会被忽略或导致请求失败，请参考具体模型文档。

---

### 参数详解

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| **`model`** | `String` | 无（使用客户端默认） | 指定模型名称，如 `"gpt-4o"`、`"qwen-max"`。若未设置，使用 `ChatConfig` 中配置的默认模型。 |
| **`temperature`** | `Float` | `0.5f` | 控制输出随机性：<br>• `0.1~0.3`：确定性强，适合事实性任务（RAG、工具调用）<br>• `0.7~1.0`：创意性强，适合写作<br>• **必须 ≥ 0** |
| **`topP`** | `Float` | `null` | Nucleus 采样阈值（0.0~1.0）。仅保留累积概率不超过 `topP` 的最小词集进行采样。 |
| **`topK`** | `Integer` | `null` | 仅从概率最高的 `topK` 个词中采样。 |
| **`maxTokens`** | `Integer` | `null` | 限制生成内容的最大 token 数（不含 prompt）。**必须 ≥ 0** |
| **`seed`** | `String` | `null` | 随机种子，用于可复现输出（需模型支持）。 |
| **`stop`** | `List<String>` | `null` | 停止序列。当生成内容包含列表中的任意字符串时立即停止（如 `["\n", "。"]`）。 |
| **`thinkingEnabled`** | `Boolean` | `null` | 启用“思考模式”（如 Qwen3 的 reasoning output）。若为 `null`，由模型默认行为决定。 |
| **`extra`** | `Map<String, Object>` | `null` | 透传模型特有参数（如 `{"response_format": "json_object"}`）。 |
| **`streaming`** | `boolean` | **自动设置** | **禁止用户手动设置**！框架在调用 `chat()` 或 `chatStream()` 时自动赋值。 |

> 📌 **使用建议**：
> - `temperature`、`topP`、`topK` 通常**只启用其中一个**，避免行为冲突。
> - 通过 `addExtra()` 添加未显式暴露的参数，例如强制 JSON 输出：
>   ```java
>   options.addExtra("response_format", Map.of("type", "json_object"));
>   ```

---

### 创建 `ChatOptions` 的两种方式

#### 1. Builder 模式（推荐）
```java
ChatOptions options = ChatOptions.builder()
    .model("gpt-4o-mini")
    .temperature(0.2f)
    .maxTokens(512)
    .stop(List.of("\n\n", "。"))
    .addExtra("response_format", Map.of("type", "json_object"))
    .build();
```

#### 2. Java Bean 风格
```java
ChatOptions options = new ChatOptions();
options.setModel("qwen-max");
options.setTemperature(0.3f);
options.setMaxTokens(1024);
options.addExtra("enable_search", true);
```


### 在调用中使用

```java
// 同步调用
String response = chatModel.chat(prompt, options);

// 流式调用
chatModel.chatStream(prompt, listener, options);
```

> 框架会自动根据调用方法设置 `streaming` 字段，**请勿手动调用 `setStreaming()`**。






### 模型配置（示例：OpenAI）
```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey("sk-xxxx");
config.setModel("gpt-4o-mini");
config.setEndpoint("https://api.openai.com");
config.setRequestPath("/v1/chat/completions");
```

> 其他模型（如 DeepSeek）使用对应的配置类（`DeepseekConfig`），参数类似。


