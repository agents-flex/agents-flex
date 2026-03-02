# LocalTokenCounter
<div v-pre>

## 1. 核心概念

`LocalTokenCounter` 是一个用于 **本地统计对话消息 Token 数量的工具类**。在 AI 应用中，Token 数量直接影响模型的请求成本和响应长度，因此准确的本地统计对于**对话管理、预算控制、Prompt 设计和流式生成**非常重要。

### 1.1 Token 统计对象

* **Prompt Tokens**：对话历史消息（用户/系统/AI）的 Token 数。
* **Completion Tokens**：AI 生成内容的 Token 数，包括回答文本、推理内容和函数调用。
* **Total Tokens**：Prompt Tokens 与 Completion Tokens 的总和。

### 1.2 核心思想

`LocalTokenCounter` 模拟 OpenAI ChatCompletion 消息格式：

* 每条消息包含 `role`、`content`。
* 每条消息的 role 结尾都有固定 token。
* Completion 部分包含文本和可选函数调用，并在末尾加固定 token。

通过这种方式，本地统计可以 **更接近 OpenAI 实际计费逻辑**。



## 2. 快速入门


### 2.1 核心静态方法

```java
List<Message> messages = ...; // 完整对话历史
AiMessage aiMessage = ...;    // 最新生成的 AI 消息

// 计算并设置本地 token
LocalTokenCounter.computeAndSetLocalTokens(messages, aiMessage);

// 获取各类 token
int promptTokens = aiMessage.getLocalPromptTokens();
int completionTokens = aiMessage.getLocalCompletionTokens();
int totalTokens = aiMessage.getLocalTotalTokens();
```

### 2.2 单独计算

如果只想计算而不修改 `AiMessage` 对象：

```java
int promptTokens = LocalTokenCounter.countPromptTokens(messages);
int completionTokens = LocalTokenCounter.countCompletionTokens(aiMessage);
```



## 3. 配置与高级应用

### 3.1 模型编码类型

默认使用 `CL100K_BASE`，适用于 GPT-3.5 / GPT-4。
如果需要支持其他模型，可通过静态方法重载或扩展：

```java
Encoding encoding = Encodings.newDefaultEncodingRegistry()
                             .getEncoding(EncodingType.R50K_BASE);
```

### 3.2 支持函数调用

AI 消息可能包含函数调用：

```java
aiMessage.getToolCalls(); // 返回 ToolCall 对象
```

`LocalTokenCounter` 会自动序列化函数调用内容并统计 token：

```java
int completionTokens = LocalTokenCounter.countCompletionTokens(aiMessage);
```

### 3.3 大历史优化

* 对长对话历史，静态方法 **不拼接大字符串**，逐条累加 token。
* 避免了内存开销和 StringBuilder 过大导致的性能问题。
* 适合流式生成和高频 token 统计场景。

### 3.4 自定义消息格式

如果你的消息对象包含额外字段（如 metadata），可通过 **重载 `countMessageTokens` 方法**：

```java
private static int countMessageTokens(CustomMessage msg) {
    int count = 1; // role
    if (msg.getName() != null) count += 1;
    count += ENCODING.countTokens(msg.getTextContent().toString());
    if (msg.getExtraContent() != null) {
        count += ENCODING.countTokens(msg.getExtraContent());
    }
    return count;
}
```


## 4. 使用场景示例

### 4.1 AI 对话计费估算

```java
int totalTokens = LocalTokenCounter.countPromptTokens(messages) +
                  LocalTokenCounter.countCompletionTokens(aiMessage);
double estimatedCost = totalTokens * 0.000002; // 每 token 成本示例
```

### 4.2 限制最大 Prompt

```java
int promptTokens = LocalTokenCounter.countPromptTokens(messages);
if (promptTokens > 4000) {
    // 截断对话历史或删除老消息
}
```

### 4.3 流式生成

```java
AiMessage aiMessage = new AiMessage();
while (stream.hasNext()) {
    aiMessage.appendContent(stream.next());
    int completionTokens = LocalTokenCounter.countCompletionTokens(aiMessage);
    if (completionTokens > maxLimit) break;
}
```



## 5. 注意事项

1. **统计精度**

    * 本地统计尽量模拟 OpenAI 计费，但不同版本或新模型可能略有差异。
    * 函数调用、嵌套 JSON 等内容应按序列化后统计。

2. **线程安全**

    * 静态方法使用 **线程安全的 Encoder**，适合多线程场景。

3. **大历史性能**

    * 对长对话，建议使用批量或缓存优化，避免重复统计相同消息。





</div>
