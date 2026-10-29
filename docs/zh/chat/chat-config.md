<div v-pre>

# ChatConfig

## 概述

“ChatConfig”表示 Agents-Flex 的模型配置体系。当前代码中通用聊天配置类实际名为 `BaseChatConfig`，具体厂商
提供 `OpenAIChatConfig` 等子类；仓库中没有独立的 `ChatConfig` 类。

配置分为两层：

```text
BaseModelConfig
  endpoint / requestPath / apiKey / model / provider / headers
        ↓
BaseChatConfig
  能力声明 / thinking / 日志 / 可观测性 / 重试
        ↓
OpenAIChatConfig 等厂商配置
  默认 Endpoint、模型和 Builder
```

模型配置适合放连接级、实例级默认值；单次请求的温度、最大 Token、模型覆盖和业务上下文应放在
`ChatOptions`。

## 适用场景

- 连接 OpenAI 官方或兼容 Chat Completions 服务；
- 为一个长生命周期 `ChatModel` 设置默认模型和 Endpoint；
- 声明某个模型是否支持图片、Tool Message 或思考模式；
- 在模型级启用或关闭日志、可观测性和重试；
- 通过请求头配置动态认证、租户或网关参数。

## 快速开始

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .provider("openai-compatible")
    .endpoint("https://api.example.com")
    .requestPath("/v1/chat/completions")
    .apiKey(System.getenv("AI_API_KEY"))
    .model("gpt-4o-mini")
    .logEnabled(false)
    .observabilityEnabled(true)
    .buildModel();
```

`buildModel()` 等价于先 `build()` 再创建 `OpenAIChatModel`，并会校验 API Key 非空。

## 连接配置

`BaseModelConfig` 提供通用连接字段：

| 字段 | 作用 | 处理规则 |
| --- | --- | --- |
| `provider` | 日志、观测和实例区分 | 业务标识，不决定协议 |
| `endpoint` | 服务根地址 | Setter 会去掉末尾 `/` |
| `requestPath` | API 路径 | Setter 会补前导 `/` |
| `apiKey` | 认证凭证 | 必填规则由具体 Builder 决定 |
| `model` | 默认模型 | 可被 `ChatOptions.model` 覆盖 |
| `customProperties` | 厂商扩展配置 | 不等同于请求体 `extraBody` |

最终 URL 通常是 `endpoint + requestPath`。例如：

```text
https://api.example.com + /v1/chat/completions
```

`BaseModelConfig` 不提供通用的自定义 Header Map。需要按请求加入租户 Header 或动态 Token 时，应在
`ChatInterceptor.before()` 中修改 `ChatRequestSpec`，详见 [对话拦截器](./chat-interceptor)。

## 能力声明

`BaseChatConfig` 通过能力字段告诉序列化层如何处理消息：

| 字段 | `isSupport...()` 的默认语义 | 用途 |
| --- | --- | --- |
| `supportImage` | `null` 为不支持 | 图片输入 |
| `supportImageBase64Only` | `null` 为 false | URL/文件图片需转 Base64 |
| `supportAudio` | `null` 为不支持 | 音频输入 |
| `supportVideo` | `null` 为不支持 | 视频输入 |
| `supportFile` | `null` 为不支持 | 文件输入 |
| `supportTool` | `null` 为支持 | Tool 定义和 Tool Call |
| `supportToolMessage` | `null` 为支持 | Tool 结果消息 |
| `supportThinking` | `null` 为支持 | 推理内容 |

不同字段的 `null` 语义并不完全相同，不能统一理解为“默认支持”。应优先使用具体厂商配置给出的默认值，并让
声明与服务端实际能力一致。

例如禁用工具能力：

```java
OpenAIChatConfig config = OpenAIChatConfig.builder()
    .apiKey(apiKey)
    .model(model)
    .supportTool(false)
    .build();
```

能力声明不会让服务端获得原本不存在的能力。把 `supportImage(true)` 配给纯文本模型，只会增加请求失败风险。

## 运行时行为

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `thinkingEnabled` | `null` | 模型级默认开关；空值不发送参数，交给远端决定 |
| `thinkingProtocol` | `none` | 推理字段协议，如 `deepseek`、`qwen`、`ollama` |
| `preserveThinkingEnable` | `null` | Tool 调用时是否回传推理内容；空值由协议自动决定 |
| `observabilityEnabled` | `true` | 是否启用框架可观测拦截器 |
| `logEnabled` | `true` | 是否记录 Chat 请求与响应日志 |
| `retryEnabled` | `true` | 是否启用模型级错误重试 |
| `retryCount` | `3` | 最大重试次数配置 |
| `retryInitialDelayMs` | `1000` | 初始重试延迟 |

### 模型上下文容量

`BaseChatConfig` 可以声明模型稳定的上下文容量，供 Agent 的服务端 Usage 跟踪和压缩策略使用：

```java
OpenAIChatConfig config = OpenAIChatConfig.builder()
    .apiKey(apiKey)
    .model("your-model")
    .contextWindowTokens(128_000)
    .maxOutputTokens(8_192)
    .build();
```

| 字段 | 说明 |
| --- | --- |
| `contextWindowTokens` | 模型输入与输出共享的总上下文容量；`0` 表示未知 |
| `maxOutputTokens` | 模型允许的最大输出容量；`0` 表示未知 |

这两个值是模型能力声明，不是本次请求的实际用量，也不会让远端模型获得更大的窗口。它们必须与实际请求
使用的模型一致；如果通过 `ChatOptions.model` 切换到另一模型，应使用具有对应能力声明的 `ChatModel` 实例。
单次请求的输出上限仍通过 `ChatOptions.maxTokens` 配置。

单次调用可以通过 `ChatOptions` 覆盖 thinking 和 retry 等部分行为。长期默认值放模型配置，请求差异放
`ChatOptions`，避免并发修改共享对象。

### 思考开关与协议

开关按字段解析：`ChatOptions.thinkingEnabled` 非空时覆盖 Config；否则继承
`BaseChatConfig.thinkingEnabled`。两者都为空时不发送开关参数。传入只包含温度等字段的 Options
不会改变模型的默认思考开关。

```java
OpenAIChatConfig config = OpenAIChatConfig.builder()
    .apiKey(apiKey)
    .thinkingProtocol("deepseek")
    .thinkingEnabled(true)
    .build();
ChatModel model = config.toChatModel();
ChatOptions options = ChatOptions.builder().thinkingEnabled(false).build();
model.chat(new SimplePrompt("你好"), options);
```

协议由接口决定，不根据模型名称猜测。DeepseekConfig、QwenChatConfig、OllamaChatConfig 已设置各自协议；
通用 OpenAIChatConfig 连接兼容接口时，需要显式选择协议。

| 协议 | 请求字段 | 注意事项 |
| --- | --- | --- |
| `none` | 不发送 | 显式覆盖开关会报配置错误 |
| `qwen` | `enable_thinking` | 布尔值 |
| `deepseek` | `thinking.type` | `enabled` / `disabled` |
| `openai` | `reasoning_effort` | `medium` / `none`，仅限支持这两个值的 Chat Completions 模型 |
| `ollama` | `reasoning_effort` | Ollama 的 OpenAI 兼容接口 |
| `ollama_native` | `think` | 仅供自行实现原生接口的请求构建器使用 |

`supportThinking=false` 的连接拒绝显式开关，不静默忽略。`extraBody` 与生成的同名思考参数冲突时也会报错。
自定义网关可调用 `config.setThinkingProtocolHandler(ThinkingProtocol)` 实现参数映射，无需替换整个请求构建器。
Handler 必须无状态且线程安全。

DeepSeek / Qwen 在思考未关闭时自动为带工具调用的 assistant 消息回传 `reasoning_content`。
`preserveThinkingEnable` 仅覆盖这一消息回传策略，不会发送 `preserve_thinking` 请求参数。
界面的隐藏、简化和展开状态属于应用展示策略，不应控制此回传行为。

流式结束后使用 `AiMessage.resolveReasoningText()` 读取完整推理内容；它优先读取 `fullReasoningContent`，
否则读取当前 `reasoningContent`。该只读便捷方法不会增加持久化 JSON 字段。

## OpenAIChatConfig 默认值

| 属性 | 默认值 |
| --- | --- |
| `provider` | `openai` |
| `model` | `gpt-3.5-turbo` |
| `endpoint` | `https://api.openai.com` |
| `requestPath` | `/v1/chat/completions` |

连接兼容服务时通常覆盖 Endpoint 和模型：

```java
OpenAIChatConfig config = OpenAIChatConfig.builder()
    .provider("internal-gateway")
    .endpoint("https://llm.example.com")
    .requestPath("/v1/chat/completions")
    .apiKey(apiKey)
    .model("qwen-plus")
    .build();

ChatModel model = config.toChatModel();
```

## Builder 与配置注入

应用启动时创建固定配置，推荐 Builder：

```java
ChatModel model = OpenAIChatConfig.builder()
    .apiKey(apiKey)
    .model(modelName)
    .buildModel();
```

Spring 等框架按属性绑定时可以使用 Bean Setter：

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey(apiKey);
config.setEndpoint(endpoint);
config.setModel(modelName);

ChatModel model = config.toChatModel();
```

直接调用无参构造不会执行 Builder 的 `apiKey` 校验，应用需要自行在启动阶段校验必填配置。

## 生产建议

- 配置对象初始化后只读使用，不要在并发请求中修改模型、Endpoint 或 Header Map；
- API Key 来自环境变量或密钥系统；动态 Header 通过拦截器加入，不写入源码、异常或业务日志；
- 只有服务端真实支持时才启用多模态、Tool 和 Thinking；
- OpenAI 兼容只代表协议接近，上线前验证 Tool Call、流式 Usage、错误码和推理字段；
- 日志与可观测性应按数据分类配置，框架不会替业务识别 Prompt 中的所有敏感信息；
- 重试只用于短暂网络错误、限流和服务端故障，参数错误应直接修复。

## 常见问题

### 为什么文档标题叫 ChatConfig，代码却找不到这个类？

这是配置体系的文档名称。代码应使用 `BaseChatConfig` 或具体厂商类，例如 `OpenAIChatConfig`。

### customProperties 和 ChatOptions.extraBody 有什么区别？

`customProperties` 属于模型配置，由具体实现决定如何使用；`extraBody` 明确用于向单次模型请求体透传额外字段。

### 修改共享 Config 是否线程安全？

配置类是可变 Bean，没有提供并发修改保证。应在初始化阶段完成配置，请求级变化使用 `ChatOptions`。

### 日志会自动隐藏所有敏感内容吗？

不能这样假设。`toString()` 会隐藏 API Key，但 Prompt、Header 或模型输出中的业务敏感数据仍需应用设置日志策略
和脱敏规则。

## 下一步

- [调用 ChatModel](./chat-model)
- [配置单次 ChatOptions](./chat-model#chatoptions)
- [设置对话拦截器](./chat-interceptor)
- [配置错误重试](./retry)
- [配置 Chat 日志](./logger)

</div>
