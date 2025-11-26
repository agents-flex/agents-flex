
# Agents-Flex

**轻量 · 高性能 · 跨厂商的 Java AI 应用开发框架。**


## Agents-Flex 简介

**Agents-Flex 是一个由 Java 编写的、轻量且高性能的 AI 应用开发框架**，用于统一封装各大 LLM 厂商（OpenAI、Qwen、DeepSeek、Moonshot、Ollama 等）的 API，极大简化构建 AI 应用的开发成本。

框架的灵感来源于 LangChain、LlamaIndex，并结合作者在实际企业级 AI 应用中的工程经验，提供：

* 统一的模型调用 API
* 标准化的工具（Function Calling）能力
* 跨服务商的可移植性
* 高扩展性与可观测性
* 与任何 Java 技术栈无耦合

适用于 **聊天对话、图像生成、Embedding、Function Calling、RAG 等多种 AI 场景**。


##  Agents-Flex 的核心特性

### 1. 简单易用（开箱即用）

无需复杂配置，几行代码即可完成一次完整的 AI 对话：

```java
@Test
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

**零框架绑定**：可在 Spring、Quarkus、Micronaut、Servlet 甚至裸 Java 环境中直接使用。


### 2. 高性能（轻量架构 + 高效实现）

Agents-Flex 的核心结构极其轻量，无多余依赖，不强制引入 ORM、Web 框架或大型中间件。

**性能优势包括：**

* **最小依赖设计**：核心仅依赖少量基础组件，JAR 小、无臃肿
* **高效 I/O 调用链**：支持高性能 HTTP Client（如 OkHttp、Apache HttpClient、Vert.x）
* **流式（SSE）性能优化**：流式响应开销极低
* **按需加载机制**：仅加载使用到的模型厂商，不浪费资源
* **零 GC 压力的拦截器链**：责任链设计极简，减少中间对象创建

> 适合高并发场景，例如 Chat 服务、智能客服、AgentHub、高流量 API 服务等。


### 3. 生产级可观测性（零侵入）

内置 **OpenTelemetry**，对 LLM 调用与工具（Tool）提供端到端监控：

* 自动埋点：请求次数、延迟、错误率
* 链路追踪：每次调用自动生成 Span
* 同步 / 流式 统一监控
* 按 provider、model、operation、tool 维度打标
* 动态关闭和排除特定工具监控
* 自动脱敏密码/token 字段
* 默认 logging 导出，可切换 OTLP 对接 Jaeger、Tempo、Grafana、Datadog 等

**无需写一行监控代码即可直接上生产。**



### 4. 责任链架构（超强扩展性）

通过责任链模式（Chain of Responsibility）管理所有拦截逻辑：

* LLM 调用链
* 工具（Function Calling）调用链

你可以在任意阶段插入：

* 重试
* 鉴权
* 日志
* 限流
* A/B 测试
* 自定义路由

无需修改核心逻辑，开发体验极其灵活。


### 5. 协议与实现彻底解耦（多厂商接入超容易）

两个抽象层：

* **ChatClient**：负责 HTTP/gRPC/WebSocket 通信
* **ChatRequestSpecBuilder**：负责不同厂商的请求格式构建

要接入一个新的 LLM 厂商，只需：

- ✔ 实现 ChatClient
- ✔ 实现 ChatRequestSpecBuilder

不需改动 Agent-Flex 核心框架。



### 6. 流式与同步统一 API

无论是：

* `chat()` （同步）
* `chatStream()`（流式）

两者共享：

* 拦截器链
* 上下文结构
* 可观测逻辑
* 错误处理方式

让实时聊天、后台批处理、长连接推流等场景都能保持 **一致的代码结构与可维护性**。



## 示例代码



```java
ChatModel model = OpenAIChatConfig.builder()
        .endpoint("https://ai.gitee.com")
        .provider("GiteeAI")
        .model("Qwen3-32B")
        .apiKey("PXW1****D12")
        .buildModel();
```
### 同步接口
```java
String output = model.chat("介绍一下你自己");
```

### 流式接口

```java
model.chatStream("解释一下 Java Stream 机制", delta -> {
    System.out.print(delta);
});
```



## 生态项目

作者还开源了多项 AI 相关项目，与 Agents-Flex 可以完美搭配：

* **Tinyflow** – [https://tinyflow.cn](https://tinyflow.cn)

  轻量、高性能的 AI 工作流编排引擎（可与 Agents-Flex 深度集成，也支持 SpringAI、LangChain4j 等产品）

* **AIFlowy** – [https://aiflowy.tech](https://aiflowy.tech)

  基于 Java 的企业级开源 AI 应用平台，对标 Dify、Coze、腾讯元器等产品

* **AiEditor** – [https://aieditor.com.cn](https://aieditor.com.cn)

  面向 AI 创作与公文写作的下一代富文本编辑器



## 安装依赖

Maven：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>最新版本</version>
</dependency>
```

Gradle：

```gradle
implementation("com.agentsflex:agents-flex-bom:最新版本")
```


## 参与贡献

欢迎一起完善 Agents-Flex！
无论是 Issue、PR 还是新模型厂商的接入都非常欢迎。

https://gitee.com/agents-flex/agents-flex

## License

Agents-Flex 使用 **Apache-2.0** 协议开源。

