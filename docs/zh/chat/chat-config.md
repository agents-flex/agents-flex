# ChatConfig

## 概述

`ChatConfig` 是 Agents-Flex 框架中用于**配置大语言模型（LLM）连接与能力**的核心类。它继承自 `BaseModelConfig`，在此基础上增加了对多模态（图像、音频、视频）、工具调用（Tool Calling）、推理模式（Thinking Mode）、可观测性、日志等高级能力的声明与控制。

开发者通过配置 `ChatConfig`（或其子类如 `OpenAIChatConfig`）来：
- 指定模型 API 的连接信息（Endpoint、API Key、Model Name）
- 声明模型支持的能力（如是否支持图片输入）
- 开关框架级功能（日志记录、OpenTelemetry 可观测性）
- 动态注入请求头（如认证、租户 ID）

> ⚠️ **注意**：`ChatConfig` 本身是通用基类，实际使用中应采用其具体实现（如 `OpenAIChatConfig`、`DeepseekConfig`），这些子类提供了合理的默认值和便捷的创建方式。

---

## 继承结构

```
BaseModelConfig
 └── ChatConfig
      ├── OpenAIChatConfig
      ├── DeepseekConfig
      └── ...（其他厂商实现）
```

- **`BaseModelConfig`**：定义通用连接属性（`endpoint`, `apiKey`, `model` 等）
- **`ChatConfig`**：扩展 LLM 特定能力开关与运行时行为配置

---

## 核心配置项说明

### 1. 通用连接信息（来自 `BaseModelConfig`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `provider` | `String` | 模型提供商标识（如 `"openai"`, `"deepseek"`），用于日志、指标区分 |
| `endpoint` | `String` | API 基础地址（如 `"https://api.openai.com"`），**自动去除末尾 `/`** |
| `requestPath` | `String` | 完整请求路径（如 `"/v1/chat/completions"`），**自动添加前导 `/`** |
| `model` | `String` | 默认模型名称（可在 `ChatOptions` 中覆盖） |
| `apiKey` | `String` | 认证密钥（**敏感信息，日志中自动脱敏**） |
| `customProperties` | `Map<String, Object>` | 自定义扩展属性（用于传递厂商特有配置） |

> **完整请求 URL** = `endpoint + requestPath`
> 例如：`https://api.openai.com/v1/chat/completions`

---

### 2. 模型能力声明（`ChatConfig` 特有）

这些字段用于**声明模型是否支持某项能力**，框架据此启用或禁用相关功能（如多模态消息、工具调用）。

| 能力 | 配置字段 | 默认行为 | 说明 |
|------|--------|--------|------|
| **图像输入** | `supportImage` | `true`（若未设置） | 设置为 `false` 可强制禁用图片处理 |
| **仅支持 Base64 图片** | `supportImageBase64Only` | `false` | 某些本地模型（如 Ollama）不支持 URL 图片 |
| **音频输入** | `supportAudio` | `true`（若未设置） | 声明支持音频模态 |
| **视频输入** | `supportVideo` | `true`（若未设置） | 声明支持视频模态 |
| **工具调用（Function Calling）** | `supportTool` | `true`（若未设置） | 启用后，Prompt 中的 `ToolMessage` 才会被发送 |
| **推理模式（Thinking）** | `supportThinking` | `true`（若未设置） | 模型是否支持显式输出推理过程（如 Qwen3） |

> 📌 **布尔语义说明**：
> 所有 `supportXxx` 字段采用 **“null 表示支持”** 逻辑：
> ```java
> public boolean isSupportImage() {
>     return supportImage == null || supportImage; // null 或 true → 支持
> }
> ```
> 因此，**仅当需要显式禁用某能力时才设为 `false`**。

---

### 3. 运行时行为控制

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `thinkingEnabled` | `false` | 是否**主动启用**推理模式（需模型支持） |
| `observabilityEnabled` | `true` | 是否启用 OpenTelemetry 追踪与指标 |
| `logEnabled` | `true` | 是否记录请求/响应日志（通过 `ChatMessageLogger`） |

> 💡 **`thinkingEnabled` vs `supportThinking`**：
> - `supportThinking`：模型**是否具备**该能力（声明）
> - `thinkingEnabled`：本次调用**是否启用**该能力（开关）

---


##  使用方式（以 OpenAI 为例）

### 方式一：使用 Builder（推荐）

```java
OpenAIChatConfig config = OpenAIChatConfig.builder()
    .apiKey("sk-xxxx")
    .model("gpt-4o")
    .supportImage(true)
    .supportTool(true)
    .thinkingEnabled(false)
    .observabilityEnabled(true)
    .logEnabled(true)
    .build();

ChatModel chatModel = new OpenAIChatModel(config);

// 或直接构建模型
ChatModel chatModel = OpenAIChatConfig.builder()
    .apiKey("sk-xxxx")
    .buildModel();
```

### 方式二：手动创建（适用于配置注入）

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey("sk-xxxx");
config.setModel("gpt-4o-mini");
config.setSupportImage(false); // 禁用图片
config.setLogEnabled(false);  // 关闭日志

ChatModel chatModel = new OpenAIChatModel(config);
```


## 子类默认值（OpenAIChatConfig）

| 属性 | 默认值 |
|------|--------|
| `provider` | `"openai"` |
| `model` | `"gpt-3.5-turbo"` |
| `endpoint` | `"https://api.openai.com"` |
| `requestPath` | `"/v1/chat/completions"` |

> 其他 OpenAI 兼容服务（如 Azure、Ollama）只需覆盖 `endpoint` 和 `requestPath`。


## 最佳实践

1. **优先使用 Builder 模式**
   提升可读性，避免遗漏必要字段（如 `apiKey`），Builder 会在 `build()` 时校验。

2. **谨慎关闭日志**
   生产环境建议保留日志（可通过日志级别控制），便于排查问题。敏感内容由框架自动脱敏。

3. **能力声明与实际一致**
   若模型不支持工具调用，务必设置 `supportTool(false)`，否则框架可能发送无效消息导致错误。

4. **动态 Header 优于静态配置**
   对于 Token、租户 ID 等动态值，使用 `headersConfig` 而非硬编码。

5. **多模态支持按需开启**
   若应用无需图片/音频，可显式关闭以减少请求体积和兼容性风险。


## 常见问题

**Q：`customProperties` 有什么用？**
A：用于传递模型特有参数（如 Azure 的 `api-version`）：
```java
builder.customProperty("api-version", "2024-05-01-preview");
```

**Q：能否在运行时修改配置？**
A：可以，但需确保线程安全。建议在初始化阶段完成配置。

**Q：日志中会泄露 API Key 吗？**
A：不会。`toString()` 方法已对 `apiKey` 脱敏显示为 `[REDACTED]`。


## 注意事项
1. 模型能力声明（如 `supportImage`）用于**声明模型是否支持某项能力**，框架据此启用或禁用相关功能。

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey("sk-5gqOcl*****");
config.setModel("gpt-4-turbo");
config.setSupportImage(false); // 主动声明不支持图片


ChatModel chatModel = new OpenAIChatModel(config);
SimplePrompt prompt = new SimplePrompt("What's in this image?");

// 就算在 prompt 中添加了图片，模型也会忽略它
prompt.addImageUrl("https://your-image.jpg");
```

其他功能比如工具调用（Function Calling）和推理模式（Thinking）也遵循相同的逻辑。设置 `supportImageBase64Only` 为 `true` 后，
用户在 prompt 中添加的图片，无论是 url 或者是 file，都会被转换成 base64 格式发送给模型。
