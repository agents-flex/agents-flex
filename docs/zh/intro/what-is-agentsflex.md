# Agents-Flex 是什么？

Agents-Flex 是一个 Java 开发的 AI 应用开发框架，是为了简化 AI 应用开发而生。 其灵感来源 LangChain、 LlamaIndex 以及作者作为一线 AI 应用开发工程师的最佳实践，提供了跨
AI 服务商的、可移植的、不限 Java 开发框架的 API 支持。

Agents-Flex 适用于聊天、图像生成、Embedding、Function Calling 以及 RAG 应用等场景，支持同步以及流式（Stream）的 API 选择。

作者还开源了其他 AI 框架或产品：

- Tinyflow ：https://tinyflow.cn (一个 AI 工作流编排解决方案、 AI 工作流编排组件)
- AIFlowy：https://aiflowy.tech (一个基于 Java 开发的企业级的开源 AI 应用开发平台，对标 Dify、Coze、腾讯元器等产品)
- AiEditor：https://aieditor.com.cn （一个面向 AI 的下一代富文本编辑器，用于 AI 创作、AI 公文写作等场景）


## Agents-Flex 的特点

### 1、简单易用

几行代码即可实现聊天功能：

```java
@Test()
public void testChat() {

    String output = OpenAIChatConfig.builder()
        .endpoint("https://ai.gitee.com")
        .provider("GiteeAI")
        .model("Qwen3-32B")
        .apiKey("PXW1****D12")
        .buildModel()
        .chat("你叫什么名字");

    System.out.println(output);
}
```

### 2、完善的可观测能力

Agents-Flex 内置了基于 OpenTelemetry 的端到端可观测性支持，覆盖 LLM 调用 与 工具（Tool）执行 的全链路追踪与指标监控，适用于生产环境的可观测性治理需求。

**核心能力包括：**

- **自动埋点**：无需手动编码即可自动记录 LLM 请求次数、延迟、错误率等关键指标。
- **链路追踪**：为每次 LLM 调用和工具调用创建 Span，支持与现有 OpenTelemetry 后端（如 Jaeger、Tempo、Datadog、Prometheus + Grafana 等）无缝对接。
- **流式与同步统一支持**：无论是普通 `chat()` 调用还是流式响应（Stream），都能完整追踪，保证监控一致性。
- **指标分类维度丰富**：所有指标均按 `llm.provider`、`llm.model`、`llm.operation`、`tool.name` 等维度打标，便于聚合分析。
- **动态开关与排除机制**：
    - 通过系统属性 `agentsflex.otel.enabled=false` 全局关闭可观测性。
    - 通过 `agentsflex.otel.tool.excluded=heartbeat,debug` 排除特定工具的监控，避免噪声。
- **敏感信息自动脱敏**：在工具调用监控中，自动识别并脱敏如 `password`、`token`、`secret` 等敏感字段，保障日志安全。
- **灵活导出方式**：
    - 默认使用 `logging` 导出器，便于本地调试。
    - 支持切换为 `otlp` 导出器，对接生产级可观测后端（通过 `agentsflex.otel.exporter.type=otlp`）。
- **资源自动注册**：自动设置 `service.name=agents-flex` 和 `service.version`，便于在 APM 平台识别服务实例。


### 3. 高度可扩展的责任链架构

Agents-Flex 采用 **责任链模式（Chain of Responsibility）** 统一管理 LLM 调用与工具执行的横切逻辑，使核心业务逻辑与非功能性需求（如监控、日志、鉴权、重试等）完全解耦。

- **LLM 调用链**：
  通过 `ChatInterceptor` 接口，支持在同步（`chat()`）和流式（`chatStream()`）调用中插入自定义逻辑。
  默认链路为：**可观测性拦截器 → 全局拦截器 → 实例级拦截器 → 实际 HTTP/gRPC 调用**。

- **工具调用链**：
  通过 `ToolInterceptor` 接口，对 Function Calling（工具调用）进行统一拦截，支持监控、参数校验、权限控制等。

- **动态扩展**：
  开发者可通过 `addInterceptor()` 在运行时动态插入拦截器，无需修改原有模型或工具实现，极大提升灵活性。

### 4. 协议与实现解耦，轻松支持多模型厂商

通过 `ChatClient` 和 `ChatRequestSpecBuilder` 抽象，Agents-Flex 实现了 **协议层与模型层的完全解耦**：

- **`ChatClient`**：负责实际网络通信（HTTP/gRPC/WebSocket），不同厂商（OpenAI、Qwen、Moonshot、Ollama 等）只需实现该接口。
- **`ChatRequestSpecBuilder`**：负责根据 Prompt 和配置生成符合各厂商格式的请求体（如 OpenAI 的 messages 结构、Qwen 的 input 字段等）。

这意味着：
- 新增一个 LLM 厂商，**只需实现两个组件**，无需改动核心框架。
- 同一应用中可**混合使用多个模型**（如用 GPT-4 生成内容，用 Qwen 做摘要），切换成本极低。

### 5. 流式与同步 API 统一体验

无论是同步调用 `chat()` 还是流式调用 `chatStream()`，Agents-Flex 都提供**一致的拦截器接口、上下文管理和可观测性支持**：

- 同一个 `ChatObservabilityInterceptor` 同时处理两种模式；
- `ChatContext` 在两种模式下结构一致；
- 日志、监控、错误处理逻辑无需重复编写。

>  这对构建现代 AI 应用（如 Web 聊天界面 + 后台批处理）至关重要，避免“两套代码、两套监控”的维护困境。
