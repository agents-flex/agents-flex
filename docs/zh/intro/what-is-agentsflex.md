# Agents-Flex 是什么

> **Agents-Flex 是一个面向 Java 开发者的 AI 智能体开发框架。**
> 它不只负责调用大模型，更负责把模型、知识、工具、技能与多个智能体组织成真正能够完成任务的系统。

大模型让软件第一次拥有了理解自然语言、推理和生成内容的能力。但从一次模型调用，到一个可以进入真实业务的智能体，中间还有很长的路：

- 它要连接不同厂商的模型，也要能够随时切换模型；
- 它要记住上下文，检索企业知识，知道什么时候调用工具；
- 它要访问网页、数据库和外部服务，还要安全地执行脚本、读写文件；
- 它要把复杂任务拆给不同的子 Agent，并追踪后台任务的执行结果；
- 它还要经得住生产环境中的超时、故障、并发、审计与观测。

**Agents-Flex 要解决的，正是模型能力与 Java 业务系统之间的这段距离。**

它保留 Java 开发者熟悉的类型系统、接口、注解、拦截器与构建器，不要求你换一套语言，也不强迫你接受一个封闭的运行时。你可以从一行 `chat()` 开始，逐步接入 Tool、MCP、Skills、RAG、Text2SQL 和 Subagent，直到构建出能够感知、思考、行动与协作的智能体。

## 从模型调用，到智能体系统

Agents-Flex 的能力不是一组彼此孤立的组件，而是一条完整的智能体技术链路：

| 层次 | Agents-Flex 提供的能力 | 解决的问题 |
| --- | --- | --- |
| 模型 | Chat、Embedding、Rerank、Image、Video、TTS、STT | 统一接入文本、向量、图像、视频与语音模型 |
| 对话 | Message、Prompt、Memory、同步与流式响应 | 管理一次对话以及持续的多轮上下文 |
| 行动 | Tool Calling、ReAct Agent、工具拦截器 | 让模型调用 Java 方法、API、脚本和业务服务 |
| 连接 | MCP、WebSearch、WebFetch、LLM Wiki | 连接外部工具、互联网和动态知识源 |
| 知识 | 文件解析、文档切分、Embedding、Vector Store、Rerank | 构建从数据导入到召回排序的完整 RAG 链路 |
| 技能 | AI Skills、Local/OpenSandbox/AIO Sandbox Runtime | 把提示词、脚本和资源封装成可复用、可执行的能力包 |
| 协作 | Routing Agent、Subagent、同步与后台任务 | 让不同专长的 Agent 分工协作 |
| 工程 | Model Router、熔断、重试、拦截器、OpenTelemetry | 让智能体在生产环境中可用、可控、可观测 |

这意味着，你可以用同一套抽象构建一个对话机器人，也可以继续向前，构建会查资料、读文档、分析数据库、生成文件、调用内部系统，并把复杂任务分派给多个专业 Agent 的业务助手。

## 先让模型开口，只需要几行 Java

Agents-Flex 不要求 Spring，也不要求专用容器。普通 Java 项目加入依赖后即可调用模型：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>2.2.3</version>
</dependency>
```

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .endpoint("https://api.openai.com")
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model("gpt-4o")
    .buildModel();

String answer = chatModel.chat("用一句话解释什么是 Agent");
System.out.println(answer);
```

同一套 `ChatModel` API 同时支持同步和流式响应。OpenAI、Qwen、DeepSeek、Ollama、LiteLLM，以及兼容 OpenAI 协议的服务，都可以被收敛到一致的编程模型中。业务代码面向的是 Agents-Flex 抽象，而不是某一家厂商的请求格式。

这很重要：**模型应该是可替换的基础设施，而不应该成为业务系统里无法拆除的依赖。**

## 给模型工具，它才真正拥有行动力

只有文本生成能力的模型，知道很多，却无法改变任何真实状态。Tool 是大模型走进业务系统的第一座桥。

在 Agents-Flex 中，一个普通 Java 方法加上注解，就可以成为模型能够理解和调用的工具：

```java
public class OrderTools {

    @ToolDef(description = "根据订单号查询订单状态")
    public String queryOrder(
        @ToolParam(name = "orderNo", description = "订单号", required = true)
        String orderNo
    ) {
        return orderService.queryStatus(orderNo);
    }
}

List<Tool> tools = ToolScanner.scan(new OrderTools());
```

框架负责把方法描述转换为模型可以识别的参数结构，并提供工具执行器、上下文和拦截器链。你可以在调用前后加入鉴权、审批、限流、审计、脱敏或结果转换，而无需把这些逻辑侵入业务方法。

在此之上，[ReAct Agent](../agent/react-agent.md) 能持续执行“判断 - 行动 - 观察 - 再判断”的循环；缺少关键信息时可以向用户发起澄清，执行状态还可以序列化并在中断后恢复。模型不再只是回答问题，而是开始完成任务。

## MCP、Skills 与 Subagent：打开能力边界

真正强大的 Agent，不可能把所有能力都硬编码进一个 Prompt。Agents-Flex 提供了三种相互补充的扩展机制。

### MCP：连接正在增长的工具生态

Agents-Flex 原生支持 [Model Context Protocol](../chat/mcp.md)，可以管理多个 MCP 服务，并把远端 MCP Tool 自动适配为框架统一的 `Tool`。它支持 Stdio、HTTP SSE 与 Streamable HTTP 传输。

这使数据库、浏览器、代码仓库、SaaS 服务或企业内部能力能够通过标准协议接入，而不必为每个服务重复设计一套 Agent 集成层。

> MCP 模块依赖官方 Java SDK，使用时需要 JDK 17 或更高版本；其他主要模块仍支持 Java 8 或更高版本。

### Skills：把经验变成可复用、可执行的能力包

[Agents-Flex Skills](../skills/overview.md) 不只是另一段 System Prompt。一个 Skill 可以同时包含说明文档、脚本、模板、参考资料和其他资源，并采用渐进式披露方式，只在需要时把详细内容交给模型，减少上下文占用。

同一套 Skill 可以运行在本机 `LocalSkillRuntime`，也可以运行在 OpenSandbox 或 AIO Sandbox 中。框架为模型提供 Shell、文件读写、搜索与产物发布工具，并让 Runtime 明确划定执行和文件系统边界。

于是，“生成并验证一份 PPTX”“分析一批文件并输出报告”“按照团队规范审查代码”不再是一段临时提示词，而是可以版本化、复用、迁移和隔离执行的工程资产。

### Subagent：一个 Agent 不必包办一切

面对复杂任务，最合理的结构通常不是一个无所不能的超级 Prompt，而是一组边界清晰的专业 Agent。

[Subagent](../chat/subagent.md) 允许主 Agent 把任务委托给代码审查、数据分析、资料研究等子 Agent。任务可以同步执行，也可以进入后台；主 Agent 能够通过任务 ID 查询状态和获取结果。配合 [Routing Agent](../agent/routing-agent.md)，还可以先用关键词或 LLM 判断意图，再把请求交给最合适的 Agent。

从单 Agent 到多 Agent，不需要推翻已有代码，只是继续给系统增加新的角色和能力。

## 不止问答：把企业数据变成 Agent 的上下文

Agents-Flex 覆盖了完整的 RAG 链路：

1. 从本地文件、字节流或 HTTP 资源加载内容；
2. 解析 PDF、Word、Excel、PowerPoint、HTML 和纯文本；
3. 按长度、Token、正则或 Markdown 标题切分文档；
4. 通过 Embedding 模型生成向量；
5. 写入 Redis、Milvus、Elasticsearch、OpenSearch、PgVector、Qdrant、Chroma 等存储；
6. 执行向量检索、条件过滤与 Rerank，再把结果交给模型。

这些组件都由接口连接。你可以只替换其中一环，也可以把已有的模型、数据库或检索服务接进来，而不是被固定流水线绑住。详见 [RAG 文档](../rag/document.md) 与 [向量数据库](../rag/vector-store.md)。

对于结构化数据，[Text2SQL](../chat/text2sql.md) 采用渐进式披露：先获取表列表，再读取必要的字段结构，最后生成参数化 SQL 并执行查询。模块内置只读校验，并提供 LIMIT、租户隔离和 SQL 审计拦截器，让“用自然语言查询业务数据”不必以放弃工程控制为代价。

此外，[WebSearch](../chat/websearch.md) 可以接入 Tavily、Brave、博查、百度千帆与 Firecrawl，[LLM Wiki](../chat/llm-wiki.md) 可以把领域知识以工具形式按需提供给模型。Agent 的答案可以来自实时世界，也可以扎根于企业自己的知识体系。

## 为生产环境设计，而不是停在 Demo

一个智能体能跑起来，只是起点。Agents-Flex 把生产系统需要的机制放进了核心调用链。

### 模型路由与故障恢复

[Model Router](./model-router.md) 可以把多个 Chat 或 Embedding 模型组织成一个逻辑模型，并提供：

- 最少活跃与加权随机负载均衡；
- 按 `vision`、`reasoning`、`cheap` 等标签选择节点；
- 节点级运行指标、自动重试与故障切换；
- 熔断、半开探测与自动恢复。

上层仍然调用普通的 `ChatModel`。今天可以按成本分流，明天可以在某个供应商故障时自动切换，业务代码无需感知背后的节点变化。

### 统一的拦截器与可观测性

模型调用和工具执行都有独立的责任链与上下文。日志、重试、鉴权、缓存、限流、合规检查和自定义路由都可以作为拦截器加入，而不需要修改模型或工具实现。

框架通过 OpenTelemetry 为同步调用、流式调用和 Tool 执行提供追踪与指标，并按 provider、model、operation、tool 等维度记录信息。你可以把遥测数据接入现有的 OTLP 后端，让一次用户请求如何经过模型、工具和外部系统变得可定位、可度量。

### 模块化，而不是全家桶

Agents-Flex 的核心保持对 Web 框架和 ORM 的独立。你可以在裸 Java、Spring Boot、Quarkus、Micronaut 或现有 Servlet 应用中使用它；需要 Spring Boot 自动配置时，再引入对应 Starter。

模型、向量库、工具与高级能力按模块组织。项目可以使用聚合依赖快速开始，也可以只选择实际需要的模块。框架提供默认实现，但关键接口始终允许替换。

**这就是名字中 Flex 的含义：不是让你适应框架，而是让框架进入你的架构。**

## 你可以用它构建什么

- 能查订单、调接口、执行审批的智能客服；
- 能搜索资料、交叉验证并生成报告的研究助手；
- 能理解数据库结构、查询指标并生成图表的数据分析 Agent；
- 能读取企业文档、引用知识来源并持续更新的知识助手；
- 能运行脚本、处理文件并交付 PPTX、PDF 等产物的办公 Agent；
- 能把任务分给不同专业角色，并在后台并行推进的多 Agent 系统；
- 同时支持文本、语音、图像与视频生成的多模态应用。

这些场景看似不同，底层其实共享同一组问题：模型如何接入，知识如何进入上下文，工具如何安全执行，任务如何持续推进，系统如何稳定运行。Agents-Flex 把这些问题拆成清晰、可组合、可替换的 Java 模块。

## 从这里出发

如果你只想验证一次模型调用，从 [快速开始](../chat/getting-started.md) 出发。

如果你准备构建一个真正会行动的 Agent，继续阅读 [Tool](../chat/tool.md)、[ReAct Agent](../agent/react-agent.md)、[MCP](../chat/mcp.md)、[Skills](../skills/overview.md) 和 [Subagent](../chat/subagent.md)。

如果你正在建设企业知识与数据能力，请进入 [RAG](../rag/document.md)、[LLM Wiki](../chat/llm-wiki.md) 和 [Text2SQL](../chat/text2sql.md)。

如果你的系统即将面对真实流量，请重点阅读 [模型路由](./model-router.md) 与 [可观测性](../observability/observability.md)。

Agents-Flex 使用 Apache License 2.0 开源：

- GitHub：[https://github.com/agents-flex/agents-flex](https://github.com/agents-flex/agents-flex)
- Gitee：[https://gitee.com/agents-flex/agents-flex](https://gitee.com/agents-flex/agents-flex)

Java 已经承载了无数关键业务系统。现在，不必离开这些系统，也不必放弃成熟的工程方法，就可以为它们接入能够理解、推理、行动与协作的智能体。

**让模型进入业务，让工具成为双手，让知识成为记忆，让多个 Agent 成为团队。下一代 Java AI 应用，可以从这里开始。**
