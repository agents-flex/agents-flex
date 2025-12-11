# Chat Observability 对话可观测
<div v-pre>

## 1. 核心概念

在 Agents-Flex 框架中，对话可观测性（Chat Observability）提供对 LLM 请求的 **端到端追踪和指标监控能力**。
主要概念包括：

1. **Tracing（追踪）**

    * 通过 OpenTelemetry `Span` 记录请求链路信息：

        * LLM 提供商（provider）、模型（model）、操作类型（operation）
        * 消耗 token 数量
        * 响应文本（截断）
        * 异常信息
    * 支持同步请求和流式请求

2. **Metrics（指标）**

    * 请求总数 (`llm.request.count`)
    * 请求延迟 (`llm.request.latency`)
    * 错误请求数 (`llm.request.error.count`)
    * 支持按 provider、model、操作类型及成功/失败维度统计

3. **责任链拦截器**

    * `ChatObservabilityInterceptor` 位于责任链最外层
    * 拦截器负责：

        1. 在请求开始时创建 Span
        2. 执行请求并记录成功或失败
        3. 上报 Metrics
    * 对同步和流式请求分别处理，确保 Span 和 Metrics 安全记录

4. **流式请求处理**

    * Span 在 `onStop` 或 `onFailure` 安全关闭
    * 通过 `AtomicBoolean` 确保 Metrics 只记录一次
    * 异常自动标记 `StatusCode.ERROR` 并记录异常信息



## 2. 快速入门

### 2.1 启用可观测性

```java
OpenAiChatConfig config = new OpenAiChatConfig();
config.setObservabilityEnabled(true);  // 默认开启，可省略
config.setApikey("sk-xxxx");

OpenAIChatModel chatModel = new OpenAIChatModel(config);
```

### 2.2 同步请求

```java
AiMessageResponse response = chatModel.chat(prompt, new ChatOptions());
// ChatObservabilityInterceptor 自动记录 Span 和 Metrics
```

### 2.3 流式请求

```java
chatModel.chatStream(prompt, new StreamResponseListener() {
    @Override
    public void onStart(StreamContext ctx) {}
    @Override
    public void onMessage(StreamContext ctx, AiMessageResponse resp) {}
    @Override
    public void onFailure(StreamContext ctx, Throwable t) {}
    @Override
    public void onStop(StreamContext ctx) {}
}, new ChatOptions());
// Span 在 onStop/onFailure 安全结束，Metrics 自动上报
```



## 3. 配置说明

| 配置项                            | 默认值  | 说明                          |
| - | - |-----------------------------|
| `observabilityEnabled`         | true | 是否启用可观测性（Tracing + Metrics） |
| `MAX_RESPONSE_LENGTH_FOR_SPAN` | 500  | Span 中存储响应内容的最大长度（字符数）      |

* Span 属性：

    * `llm.provider`：LLM 提供商
    * `llm.model`：模型名称
    * `llm.operation`：操作类型（chat/chatStream）
    * `llm.total_tokens`：消耗 token
    * `llm.response`：响应文本（截断）
* Metrics 标签：

    * `llm.provider`、`llm.model`、`llm.operation`、`llm.success`



## 4. 高级应用

### 4.1 集成 Observability 系统

```java
Observability.init(tracerProvider, meterProvider);
```

* 支持 OTLP、Prometheus、Jaeger 等
* 所有 LLM 请求自动上报 Trace 和 Metrics，无需手动改动业务代码

### 4.2 异常和失败处理

* `ChatObservabilityInterceptor` 会在异常发生时：

    * 标记 Span 状态为 `StatusCode.ERROR`
    * 记录异常信息
    * 上报失败 Metrics
* 流式请求也通过 `AtomicBoolean` 确保 Metrics 只记录一次

### 4.3 性能监控

* 请求延迟通过 `LLM_LATENCY_HISTOGRAM` 记录（秒）
* 可按 provider、model、操作类型维度分析性能
* 错误率通过 `LLM_ERROR_COUNT` 统计

### 4.4 响应截断

* Span 中的响应文本限制为 500 字符，避免追踪信息过大
* 可根据需求调整 `MAX_RESPONSE_LENGTH_FOR_SPAN`



## 5. 总结

`ChatObservabilityInterceptor` 提供了完整的 **对话可观测方案**：

* 自动创建 Span、记录请求链路
* 自动上报请求数、延迟、错误等指标
* 支持同步和流式请求
* 自动处理异常与失败
* 可集成到 OTLP、Prometheus、Jaeger 等监控平台


</div>
