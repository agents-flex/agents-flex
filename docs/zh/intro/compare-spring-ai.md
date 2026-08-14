---
title: 对比 Spring AI
description: 从开发体验、Agent 运行时、OCR、异步任务、多模态、RAG、模型路由和可观测性等维度，对比 Agents-Flex 与 Spring AI。
---

# Agents-Flex 对比 Spring AI

## 概述

Spring AI 是 Java AI 生态中重要的框架。它把模型、RAG、
Tool Calling 和 MCP 带入 Spring Boot，并提供 Fluent `ChatClient`、Advisor、结构化输出、Micrometer
Observation 以及大量模型和向量库适配。

Agents-Flex 与 Spring AI 的目标并不完全相同。Spring AI 重点解决“如何在 Spring 应用中使用 AI”；
Agents-Flex 除了提供模型接入，还继续解决“如何构建能够长期运行、恢复和治理的 Java AI 应用与 Agent”。

因此，Agents-Flex 不仅有 ChatModel、Tool 和 RAG，还把模型高可用、可恢复 Agent、审批表单、OCR、
视频生成、通用异步任务、Worker Lease、Skills Runtime、Sandbox 和执行级遥测路由纳入同一套框架。

## 先看结论

两者不是简单的替代关系。选择时应先看应用的运行环境和最难解决的问题，而不是只比较 Provider 数量。

| 你的主要需求 | 更适合的选择 | 原因 |
| --- | --- | --- |
| 已有 Spring Boot 系统，需要快速接入模型、Tool Calling 或 RAG | **Spring AI** | 自动配置、Fluent `ChatClient`、结构化输出、Advisor、Actuator 和 Spring 生态集成更成熟 |
| 希望核心能力不依赖 Spring，运行在普通 Java 或其他 JVM 框架中 | **Agents-Flex** | 核心保持独立，同时提供可选的 Spring Boot Starter |
| 需要更多国际模型与向量数据库的开箱适配 | **Spring AI** | Provider 和 Vector Store 的覆盖范围更广 |
| 需要 Agent 跨请求、跨进程暂停和恢复 | **Agents-Flex** | Agent Turn、快照、Lease、Worker、审批和恢复是一等运行时能力 |
| 需要模型节点负载均衡、故障转移和熔断恢复 | **Agents-Flex** | ChatModel 与 EmbeddingModel 均有对业务透明的高可用路由 |
| 需要 OCR、视频生成等国内多模态服务 | **Agents-Flex** | 提供统一模型抽象和百度智能云、Gitee AI、MinerU、阿里云、火山引擎等适配 |
| 需要治理供应商长任务的 QPS、账号并发、租户配额和调度顺序 | **Agents-Flex** | 通用异步任务模块内置持久化、Worker 领取、重试以及调度与准入控制 |

如果系统的主要边界就是 Spring Boot，Spring AI 通常能以更少的配置完成基础 AI 集成；如果应用的复杂度
集中在长任务、分布式执行、多租户治理、模型高可用或可恢复 Agent，Agents-Flex 提供了更完整的运行时。

::: info 对比基线
本文基于 2026-08-09 的本地代码核对。Spring AI 的范围同时包含核心代码 `spring-ai`
（`2.0.1-SNAPSHOT`）与 `spring-ai-agent-utils`（`0.11.0-SNAPSHOT`，依赖 Spring AI 2.0.0）；
Agents-Flex 的版本为 2.2.7。
:::

::: tip 如何理解表格中的“不支持”
本文的“不支持”是指在上述版本和核对范围内，没有发现框架直接提供的对等抽象或开箱实现；不代表应用
无法通过 Spring Bean、自定义 Tool、定时任务或第三方组件自行实现。随着双方版本演进，具体能力应以
对应版本的官方文档和代码为准。
:::

## 基础对话 ChatModel 对比

Agents-Flex 和 Spring AI 都提供统一 `ChatModel`，都支持 Prompt、Message、同步调用、流式响应、Tool
Calling 和多模型 Provider。差异主要在运行依赖、高层 API、协议扩展方式和结构化输出。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的对话模型接口 | **整体支持**。`agents-flex-core` 提供 `ChatModel`，`chat()` 与 `chatStream()` 均可接收 String 或 Prompt | **整体支持**。`ChatModel` 提供 `call()` 与 `stream()`，流式结果使用 `Flux<ChatResponse>` |
| 多模型厂商接入 | **整体支持**。`agents-flex-chat-*` 已适配 OpenAI、DeepSeek、Qwen、Ollama、LiteLLM 及 OpenAI 兼容服务 | **整体支持且 Provider 更多**。包括 OpenAI、Anthropic、DeepSeek、Google、Bedrock、Ollama、Mistral 等 |
| 不依赖 Spring Framework 独立运行 | **整体支持**。核心不依赖 Spring，大部分主要模块支持 Java 8，可用于普通 Java 及其他 JVM 框架 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 对话消息与记忆管理 | **整体支持**。提供统一 Prompt/Message、`MemoryPrompt`、`ChatMemory` 以及可替换的 Store | **整体支持**。提供 Prompt、Message、ChatMemory，以及 JDBC、Redis、MongoDB、Neo4j、Cassandra Repository |
| 同步与流式对话统一拦截 | **整体支持**。`ChatInterceptor` 与 `ChatContext` 共享请求上下文，可修改 Prompt、Options 和请求规格 | **整体支持**。Call/Stream Advisor 与 `ChatClientRequest.context` 提供相近的请求增强能力 |
| 分别替换请求构建、传输与响应解析 | **整体支持**。`ChatRequestSpecBuilder`、`ChatClient`、`AiMessageParser` 形成清晰的三层协议扩展边界 | ⚠️ **部分支持**。模型实现和 HTTP Client 可以扩展，但没有完全相同的三层协议适配边界 |
| 模型结构化输出 | **部分支持**。`responseFormat` 支持 `json_object` 与 `json_schema`，结果通常由业务反序列化 | **整体支持且更完整**。`ChatClient.entity(...)`、Converter、原生 Schema 与校验重试可直接映射 POJO |
| Spring Boot 开箱即用集成 | **整体支持**。`agents-flex-spring-boot-starter` 提供可选集成，同时保持核心框架独立 | **整体支持且更深入**。自动配置、Starter、Actuator、Testcontainers 与 Spring DSL 是其强项 |

Agents-Flex 的最小调用不需要容器：

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .endpoint("https://api.openai.com")
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model("gpt-4o")
    .buildModel();

String answer = chatModel.chat("什么是 Tool Calling？");
```

Spring AI 的 `ChatClient` 在 Fluent API 和 POJO 结构化输出方面更完整；Agents-Flex 的优势是核心更轻、
运行环境更自由，并且 ChatModel 可以原样进入后续的模型路由和可恢复 Agent 运行时。

## Tool 扩展对比

两边都能把 Java 方法转换为模型工具，也都能自动推进模型与工具的调用循环。Agents-Flex 进一步把 Tool
执行放入独立的 `ToolExecutor`、`ToolInterceptor` 与 Agent 状态机，使鉴权、审批、观测、错误策略和恢复
不会散落在业务方法中。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 完整的工具调用数据模型与执行闭环 | **整体支持**。`agents-flex-core` 以 Tool、ToolCall、ToolMessage 统一描述工具定义、模型请求、执行结果与消息回传 | **整体支持**。ToolCallback、ToolCallingManager 与 ToolResponseMessage 提供完整闭环 |
| 注解声明和运行时动态创建工具 | **整体支持**。`ToolScanner` 支持注解扫描，`Tool.builder()` 支持动态构建 | **整体支持**。支持 `@Tool`、方法回调、Function 以及动态 ToolCallback |
| 独立的工具执行拦截链 | **整体支持**。`ToolExecutor` 与 `ToolInterceptor` 提供独立执行上下文和责任链，便于统一鉴权、审计与错误处理 | ⚠️ **部分支持**。ToolCallingAdvisor 和 Observation 可以扩展执行，但没有完全相同的独立 ToolInterceptor 分层 |
| Tool 调用次数限制 | **整体支持**。`AgentBudget.maxToolCalls` 限制一次 Agent Turn 的业务 Tool 调用总数，计数随快照持久化，恢复后继续生效 | **整体支持且粒度更细**。`ToolCallLimits` 可分别限制单个 Tool 和当前 Turn 的总调用次数，并配置超限后抛出异常或返回错误响应 |
| Tool 异常处理策略 | **整体支持**。`ToolErrorStrategy` 可选择终止 Agent Turn，或把结构化错误作为原 ToolCall 的结果交回模型；错误消息还可通过 `ToolErrorMessageFactory` 定制 | **整体支持**。`ToolExecutionExceptionProcessor` 可将错误消息交回模型，也可整体抛出或按异常类型选择性重新抛出 |
| Tool 返回值转换为模型消息 | **整体支持**。Agent 自动把标量转换为文本、把结构化 Java 对象序列化为 JSON，并生成与原 ToolCall 关联的 `ToolMessage` | **整体支持且可替换**。默认转换常见 Java 返回值，并可为方法 Tool 指定自定义 `ToolCallResultConverter` |
| Tool 结果直接返回调用方，不再请求模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`returnDirect` 可跳过后续模型调用，直接把 Tool 执行结果作为本次调用结果返回 |
| 运行时按名称动态解析 Tool | **整体支持**。`AgentToolResolver` 可依据当前 Turn 中已持久化的租户、权限或 ToolSearch 激活状态解析可执行 Tool | **整体支持**。`ToolCallbackResolver` 支持静态、委托及应用自定义解析实现，由 `ToolCallingManager` 按名称查找 ToolCallback |
| 不暴露给模型的 Tool 执行上下文 | **整体支持**。`ToolContext` 可传递调用属性；Agent 进一步提供稳定 Turn/ToolCall 身份、幂等键、恢复表单数据、进度发布器和取消检查 | **整体支持**。默认和请求级 `ToolContext` 可合并上下文数据，并注入声明了该参数的 Tool 方法 |
| 扩展模型可见的 Tool 参数 Schema | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`AugmentedToolCallback` 可用 Record 为现有 Tool Schema 增加结构化参数，并在委托执行前消费或移除扩展参数 |
| Tool 执行进度事件 | **整体支持**。业务 Tool 可通过 `AgentToolProgressEmitter` 发布文本和结构化进度，统一转换为带 Turn 与 ToolCall 身份的 Agent 事件 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 常用 Agent 工具集 | **整体支持**。提供 TodoWrite、WebFetch，以及基于 `SkillRuntime` 的 Shell、文件、Glob 和 Grep 等工具 | **整体支持**。提供 Shell、文件、Glob、Grep、TodoWrite 和 WebFetch 等工具 |
| Subagent Tool | **整体支持**。规划工具可把任务委派给子 Agent；父子 Turn、任务依赖、执行状态和结果都进入快照，可跨请求和进程恢复 | **整体支持**。`TaskTool` 可同步或后台启动 Subagent，并通过 TaskOutput 查询结果和恢复已有 Agent；默认任务状态保存在进程内 |
| AutoMemory Tool | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`AutoMemoryTools` 与 `AutoMemoryToolsAdvisor` 提供基于文件的长期记忆创建、读取、更新、删除和上下文注入 |
| 后台 Shell 任务及输出管理 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`ShellTools` 可在后台启动命令，通过 `BashOutput` 增量读取输出，并使用 `KillShell` 终止进程 |
| 可持久化的工具审批与表单 | **整体支持**。审批、拒绝和表单等待都能挂起并持久化到 `AgentTurn`，可跨请求、跨进程恢复 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 的 ToolCallback 层在结果转换、`returnDirect`、参数增强和现成工具集方面更加丰富。Agents-Flex
的重点则是让 Tool 不只完成一次函数调用：调用身份、预算计数、审批决定、恢复输入、执行进度、错误结果以及
Subagent 状态都进入统一的 Agent Turn，并能随快照跨请求、跨进程继续执行。

### Web Search 对比

`agents-flex-websearch` 不是绑定某一个搜索引擎的单一 Tool，而是一套可扩展的 Web Search 抽象。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| Tavily Web Search | **整体支持**。`agents-flex-websearch` 提供 `TavilySearchProvider` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Brave Web Search | **整体支持**。`agents-flex-websearch` 提供 `BraveSearchProvider` | **整体支持**。提供 Brave Web Search Tool |
| Anthropic 原生 Web Search | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`AnthropicWebSearchTool` 可为 Claude 启用原生联网搜索，并配置允许或禁止的域名、最大调用次数和用户位置 |
| 博查 Web Search | **整体支持**。`agents-flex-websearch` 提供 `BochaSearchProvider` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 百度千帆 Web Search | **整体支持**。`agents-flex-websearch` 提供 `BaiduQianfanSearchProvider` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Firecrawl Web Search | **整体支持**。`agents-flex-websearch` 提供 `FirecrawlSearchProvider` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 与搜索厂商无关的统一结果模型 | **整体支持**。不同 Provider 的响应统一转换为 `SearchResult`，上层代码无需绑定厂商协议 | ⚠️ **部分支持**。Brave Tool 使用自身结果模型，尚未形成多 Provider 抽象 |
| 直接进入 Agent 工具执行链 | **整体支持**。Web Search 作为标准 Tool，可进入 `ToolInterceptor`、Agent 运行时和可观测链 | **整体支持**。可作为 Spring AI ToolCallback 进入工具调用流程 |

### Web Fetch 对比

Agents-Flex 的 Web Fetch 不是单一 HTTP 请求实现。`WebFetchTool` 先尝试直接抓取，在内容为空或过短时，
再交给可插拔的 `WebReaderProvider` 链路处理。当前框架内置 HTTP 和 Jina Reader 两个 Provider，也允许
业务接入浏览器渲染、Firecrawl、Browserless 或内部网页解析服务。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 可直接交给模型使用的网页获取工具 | **整体支持**。`agents-flex-tool-webfetch` 提供 `WebFetchTool` | **整体支持**。提供 `SmartWebFetchTool` |
| 可插拔的网页读取 Provider 抽象 | **整体支持**。`WebReaderProvider` 统一定义支持判断、静态评分与正文读取接口 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 直接 HTTP 网页读取 | **整体支持**。`HttpReaderProvider` 适合静态网页、JSON API 和开放文档 | **整体支持**。`SmartWebFetchTool` 内部使用 Java `HttpClient` 直接抓取 |
| Jina Reader 网页提取 | **整体支持**。`JinaReaderProvider` 可通过 `r.jina.ai` 提取正文并去除页面噪音 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 自定义网页读取实现 | **整体支持**。实现 `WebReaderProvider` 即可接入浏览器渲染、Firecrawl、Browserless 或内部解析服务 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 多 Reader Provider 自适应路由 | **整体支持**。`AdaptiveWebReaderRouter` 根据 URL 支持情况和 Provider 评分排列候选 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 根据历史成功率动态调整 Provider 优先级 | **整体支持**。`AdaptiveScoreEngine` 记录成功与失败次数，并将历史表现加入实时评分 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Reader 失败后的跨 Provider 自动降级 | **整体支持**。当前 Provider 失败或返回空内容时自动尝试下一个候选 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| HTML 清洗并转换为 Markdown | **整体支持**。移除 Script、Style 后通过 Flexmark 转换，并执行长度限制 | **整体支持**。通过 Flexmark 转换并执行长度限制 |
| 返回可复用的 Markdown 网页正文 | **整体支持**。返回经过清洗并受 `maxContentLength` 限制的 Markdown 正文，后续可以由 Agent、RAG 或 Skill 重复使用；默认最多保留 100,000 个字符 | ⚠️ **部分支持**。抓取后必须调用 `ChatClient` 按 Prompt 生成摘要，返回结果绑定本次问题并产生额外模型调用 |
| 缓存与瞬时故障重试 | **整体支持**。提供 15 分钟 LRU 缓存，并对 429、5xx 等错误重试 | **整体支持**。提供 15 分钟缓存，并对网络错误和 5xx 执行指数退避重试 |
| 抓取前的域名安全检查 | ⚠️ **部分支持**。校验 URL、Host 以及 HTTP/HTTPS 协议，没有内置远程域名信誉检查 | **整体支持**。可选调用 Claude Domain Info API，并配置检查失败时放行或拒绝 |
| 抓取任务的审批、恢复与追踪 | **整体支持**。执行可经过 `ToolInterceptor`、Agent 审批、错误策略与 OpenTelemetry Trace | ⚠️ **部分支持**。支持缓存、重试和 Tool Observation；跨请求审批及恢复仍需业务实现 |
| 网页内容进入隔离的 Skill 工作空间 | **整体支持**。抓取结果可进入 `SkillRuntime` Workspace，并继续参与文件处理与产物发布 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

### ToolSearch 对比

ToolSearch 解决工具数量过多时的上下文膨胀问题：模型先搜索工具，再把命中的 Tool Schema 加入当前请求。
Agents-Flex 的默认实现强调轻量、丰富元数据和可恢复 Agent 集成；Spring AI 则提供了更多开箱即用的索引实现。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 开箱即用的工具搜索机制 | **整体支持**。`agents-flex-toolsearch` 提供 `ToolSearchTool`、Provider 与请求级拦截器 | **整体支持**。提供 Tool Search Tool、ToolSearchToolCallingAdvisor、自动配置和 Starter |
| 按需披露 Tool Schema | **整体支持**。只有搜索命中的 Tool 才进入当前模型请求，减少上下文和无关工具干扰 | **整体支持**。Tool Search Advisor 实现相同目标 |
| 无需 Embedding 的默认词法搜索 | **整体支持**。`InMemoryToolSearchProvider` 对 Tool 元数据执行进程内加权搜索，无需部署外部索引 | **整体支持**。`RegexToolIndex` 对名称和摘要执行不区分大小写的词法匹配 |
| 名称、描述、分类、标签与参数联合检索 | **整体支持**。默认 Provider 会对名称、描述、分类、标签及嵌套参数名称和描述分别加权 | ⚠️ **部分支持**。内置索引主要检索 Tool 名称与摘要，`ToolReference` 不包含对等的标签、参数树和业务元数据模型 |
| Lucene 全文 Tool 索引 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`LuceneToolIndex` 使用进程内 Lucene 索引和 `StandardAnalyzer` 执行全文检索 |
| 基于 VectorStore 的 Tool 语义搜索 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`VectorToolIndex` 将 Tool 描述写入 Spring AI `VectorStore`，按语义相似度召回工具 |
| 替换工具搜索后端 | **整体支持**。实现 `ToolSearchProvider` 可接入数据库、Lucene、Elasticsearch 或向量检索服务 | **整体支持**。可实现 `ToolIndex` 接入其他索引或检索策略 |
| Tool 元数据与本地执行函数分离 | **整体支持**。`ToolSearchManager` 只把 `ToolInfo` 交给 Provider，搜索命中后再按名称解析进程内 Tool，无需序列化执行回调 | **整体支持**。`ToolIndex` 保存 `ToolReference`，Advisor 负责将命中引用解析为可执行 ToolCallback |
| 搜索目录的会话隔离 | **整体支持**。普通对话使用当前 Prompt 快照，Agent 按 Turn 保存已激活工具，不修改共享 Prompt | **整体支持**。`ToolIndex` 按 `sessionId` 建立和查询隔离索引 |
| 会话 Tool 索引自动淘汰 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 TTL、LRU、Always、Never 和组合淘汰策略，可清理过期会话索引 |
| 普通对话接入 ToolSearch | **整体支持**。`ToolSearchChatInterceptor` 同时覆盖同步与流式 `ChatModel` 请求 | **整体支持**。`ToolSearchToolCallingAdvisor` 可接入 `ChatClient` Advisor 链 |
| ToolSearch 状态随 Agent 持久化和恢复 | **整体支持**。`ToolSearchAgentMiddleware` 将已激活 Tool 名称写入 `AgentTurn` Metadata，并随 Snapshot 保存和恢复 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 在内置索引种类上更丰富，尤其适合直接使用 Lucene 或 VectorStore 进行 Tool 召回的应用。
Agents-Flex 的默认搜索更轻量，可检索的 Tool 元数据更丰富，并且搜索结果能够成为可持久化 Agent 执行状态的一部分。

### ToolGroup 对比

`ToolGroup` 把一组 Tool、配套 System Prompt 和 Matcher 组合成可按请求启用的能力单元。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 工具、使用说明和启用规则封装为能力组 | **整体支持**。`ToolGroup` 将 Tool、System Prompt 与 Matcher 一起管理，避免工具和提示词脱节 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 按请求上下文自动启用工具组 | **整体支持**。Matcher 可根据最新用户消息或自定义 Context 决定本次请求启用哪些能力 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 动态工具组的请求隔离 | **整体支持**。匹配结果只作用于当前 Prompt，不修改 `ChatModel` 的共享配置 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

### Text2SQL 对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 可直接使用的 Text2SQL 模块 | **整体支持**。`agents-flex-text2sql` 提供从 Schema 探索、SQL 生成到查询执行的完整工具链 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 数据库 Schema 渐进披露 | **整体支持**。先发现相关表，再按需读取字段，避免将全库结构一次性塞入 Prompt | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 面向大模型生成 SQL 的安全治理 | **整体支持**。提供参数化 SQL、只读校验、LIMIT、租户隔离与审计拦截器 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

## MCP 能力对比

MCP 让 Agent 可以通过标准协议发现和调用外部工具。Agents-Flex 当前聚焦 MCP Client，
将多个外部 MCP Server 的工具统一转换为框架 `Tool`；Spring AI 除了客户端，还提供了
完整的 MCP Server 与 Spring Boot 集成。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| MCP Client | **整体支持**。`agents-flex-mcp` 提供 `McpClientManager`，可统一管理多个本地或远程 MCP Server | **整体支持**。提供标准与 WebFlux MCP Client Starter，支持管理多个命名连接 |
| Stdio Client 传输 | **整体支持**。可启动并连接本地 MCP Server 进程 | **整体支持**。提供 Stdio Client Transport 与 Starter 配置 |
| HTTP/SSE Client 传输 | **整体支持**。提供 `HttpSseTransportFactory` | **整体支持**。提供 JDK HttpClient 与 WebFlux SSE Client Transport |
| Streamable HTTP Client 传输 | **整体支持**。提供 `HttpStreamTransportFactory` | **整体支持**。提供 HttpClient、WebFlux 以及 Stateless Streamable HTTP Client |
| MCP Tool 转换为框架统一工具 | **整体支持**。远程 Tool Schema 会转换为 Agents-Flex `Tool` | **整体支持**。MCP Tool 可转换为 Spring AI `ToolCallback` |
| MCP Tool 进入统一工具执行链 | **整体支持**。MCP Tool 与 Java Tool 使用相同的 `ToolExecutor`、`ToolInterceptor` 和 Agent Middleware | **整体支持**。MCP ToolCallback 可进入 ChatClient、ToolCallingManager 与 Advisor 执行链 |
| MCP Tool 持久化审批 | **整体支持**。MCP Tool 可使用 Agent 的统一审批策略，审批或拒绝状态保存在 `AgentTurn` 中 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| MCP Tool 挂起与跨请求恢复 | **整体支持**。等待审批时可挂起当前 Turn，并在后续请求或其他进程中恢复 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| MCP Tool 执行可观测 | **整体支持**。`ToolObservabilityInterceptor` 为 MCP Tool 执行生成 OpenTelemetry Trace 和 Metrics | **整体支持**。Tool Calling Observation 可观测 MCP ToolCallback 的执行与结果 |
| MCP Server | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 MCP Server Starter，可向客户端暴露 Tool、Resource、Prompt 与 Completion |
| MCP Server 传输 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。支持 Stdio、SSE、Streamable HTTP 与 Stateless Streamable HTTP，并提供 WebMVC/WebFlux 实现 |
| 注解式 MCP Client/Server 开发 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 Client 和 Server 注解，可声明式实现 Handler 与 Server 能力 |

Agents-Flex 已经覆盖 Agent 消费外部 MCP 能力的主要场景，而且 MCP Tool 不是一条独立的旁路，
它与本地 Tool 共用同一套 Agent 运行时。如果需要使用 Java 构建并发布 MCP Server，
Spring AI 当前提供的服务端范围更完整。

## Agent 运行时对比

Tool Calling 能让模型调用方法，但它本身不等于生产级 Agent。真正的 Agent 运行时需要知道任务执行到哪一步、
为什么暂停、如何恢复、是否超预算，以及多个 Worker 如何避免重复执行。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 有状态的 Agent 执行模型 | **整体支持**。`agents-flex-agent` 以 `AgentTurn` 和 `AgentRunner` 运行任务，每次执行都有明确 Phase 与状态迁移 | ⚠️ **部分支持**。ToolCallingAdvisor 能推进工具循环，但没有对等的持久化 Turn 状态机 |
| 完整快照并恢复 Agent 执行现场 | **整体支持**。`AgentTurnSnapshot` 保存消息、ToolCall、Phase、预算、挂起原因及父子关系，可从中断点继续 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 可跨进程共享的 Agent Store | **整体支持**。`agents-flex-agent-store-jdbc/redis` 持久化 Snapshot、CAS、取消/恢复命令与 Lease | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 多 Worker 安全领取和执行任务 | **整体支持**。`AgentWorker` 使用 Lease 与 fencing token 防止过期 Worker 重复提交结果 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 跨请求的审批、表单、暂停与恢复 | **整体支持**。等待是可持久化的正常状态，用户或外部系统可通过另一个请求恢复执行 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Agent 执行预算 | **整体支持**。`AgentBudget` 统一限制迭代、Step、Tool、Token 与执行耗时，达到上限后由 Agent 状态机终止 Turn | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 可持久化的 Agent 重试调度 | **整体支持**。`AgentRetryPolicy` 定义重试次数、退避间隔和最大等待时间，下一次重试时间随 Turn 快照持久化，可由 Worker 在到期后继续执行 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 持久化任务计划、依赖和子 Agent 状态 | **整体支持**。计划、依赖、进度、父子 Turn 及子 Agent 状态统一保存在运行时模型中 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 完整 Agent 生命周期事件与中间件 | **整体支持**。Turn、Step、Model、Tool 均有生命周期事件，并提供三层 Middleware 扩展点 | ⚠️ **部分支持**。Advisor 和 Observation 覆盖调用链，但没有同层级的 Turn 生命周期模型 |

这部分是两者最根本的差异。Spring AI 可以构建会调用工具和 Subagent 的应用；Agents-Flex 进一步保证
这些任务在等待审批、模型限流、进程重启和多实例调度之后仍能可靠继续。

## Skills 能力对比

两边都能读取 Markdown/YAML Skill，并按需把 Skill 内容提供给模型。Agents-Flex 的重点不只在“加载
Skill”，还在于 Skill 如何安装、在哪里执行、文件如何进出以及产物如何交付。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| Skill 发现与渐进式披露 | **整体支持**。`agents-flex-skills` 兼容开放 Skill 格式，只在需要时向模型加载 Skill 内容 | **整体支持**。SkillsTool 可以发现并加载 Markdown/YAML Skill |
| 统一且可替换的 Skill 执行运行时 | **整体支持**。`SkillRuntime` 与 Workspace 统一命令执行、文件系统、环境变量、超时和文件传输 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 受控本地工作空间执行可信 Skill | **整体支持**。`LocalSkillRuntime` 管理本地 Workspace 和完整执行生命周期 | ⚠️ **部分支持**。Shell/FileSystem 可以在本机执行，但不属于独立 Skill Runtime 生命周期 |
| 按需创建远程隔离 Sandbox | **整体支持**。`agents-flex-skills-open-sandbox` 可为任务按需创建 OpenSandbox 隔离环境 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 接入独立的 AIO Sandbox 服务 | **整体支持**。`agents-flex-skills-aio-sandbox` 将 AIO Sandbox 接入统一 `SkillRuntime` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Skill 包的完整安装与缓存生命周期 | **整体支持**。`agents-flex-skills-artifact-core` 负责安装、摘要校验、安全解压、物化、删除与节点缓存 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 通过主流对象存储分发 Skill 包 | **整体支持**。Artifact 子模块覆盖 OSS、COS、OBS、TOS、S3，可在多节点环境统一分发 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Skill 二进制产物统一交付 | **整体支持**。产物可从 Runtime 下载，再由 `FilePublisher` 发布到业务可访问的位置 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 已经具备 Skill 的发现和宿主机执行工具；Agents-Flex 提供的是从 Skill 包到隔离 Runtime、对象
存储、节点缓存和最终产物的完整执行链。对于第三方 Skill、多租户或用户文件场景，这是明确的安全边界。

## 图片能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的图像生成模型抽象 | **整体支持**。`agents-flex-image-*` Provider 统一实现 `ImageModel` | **整体支持**。提供统一 ImageModel |
| OpenAI 图像生成 | **整体支持**。`agents-flex-image-openai` 提供 `OpenAIImageModel` | **整体支持**。提供 `OpenAiImageModel` |
| Google Gemini 图像生成 | **整体支持**。`agents-flex-image-gemini` 提供 `GeminiImageModel` | **整体支持**。提供 Google GenAI ImageModel |
| Stability AI 图像生成 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 `StabilityAiImageModel` |
| 阿里云图像生成 | **整体支持**。`agents-flex-image-aliyun` 提供 `AliyunImageModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 火山引擎图像生成 | **整体支持**。`agents-flex-image-volcengine` 提供 `VolcengineImageModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Gitee AI 图像生成 | **整体支持**。`agents-flex-image-gitee` 提供 `GiteeImageModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Agents-Flex 用统一 `ImageModel` 承载不同厂商的图像生成能力，业务可以在不改变上层调用方式的情况下
选择国际模型或国内云服务。

## 音频能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的语音模型抽象 | **整体支持**。统一提供 TTS、流式 TTS 与 STT 接口 | **整体支持**。提供 TTS 与 STT 抽象 |
| OpenAI 语音 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 OpenAI TTS 与 STT 模型 |
| ElevenLabs 语音 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 ElevenLabs TTS 模型 |
| 阿里云语音 | **整体支持**。`agents-flex-audio-aliyun` 提供 TTS、流式 TTS 与 STT 模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 腾讯云语音 | **整体支持**。`agents-flex-audio-tencent` 提供 TTS、流式 TTS 与 STT 模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 火山引擎语音 | **整体支持**。`agents-flex-audio-volcengine` 提供 TTS、流式 TTS 与 STT 模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Agents-Flex 的三个国内云语音模块都同时覆盖语音识别、语音合成和流式语音合成，不需要为同一厂商
分别拼接多套框架接口。

## 视频能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的视频生成模型与异步任务管理 | **整体支持**。统一 `VideoModel` 负责视频生成请求、异步任务与结果轮询 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 阿里云视频生成 | **整体支持**。`agents-flex-video-aliyun` 提供万相与 HappyHorse 视频模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 火山引擎视频生成 | **整体支持**。`agents-flex-video-volcengine` 提供 `VolcengineVideoModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Gitee AI 视频生成 | **整体支持**。`agents-flex-video-gitee` 提供 `GiteeVideoModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 的独立媒体模型抽象主要覆盖图像生成和语音；Agents-Flex 进一步提供统一的视频生成接口、
国内主流 Provider 和异步任务轮询，并能与 ChatModel、Tool 和 Agent 组合成同一业务系统。

## OCR 文档识别对比

OCR 与普通文档读取解决的问题不同。文档 Reader 适合从带文本层的文件中抽取内容；扫描件、拍照文件、
复杂版面和公式表格通常需要专业 OCR 服务。Agents-Flex 将这类能力抽象为统一的 `OcrModel`，避免业务
分别处理不同供应商的提交参数、任务状态和结果格式。

| 对比项描述 | Agents-Flex | <span class="vp-nowrap">Spring AI     </span>                                                          |
| --- | --- |-------------------------------------------------------------------------|
| 独立的 OCR 模型抽象 | **整体支持**。`OcrModel` 统一任务提交、状态查询和等待完成等操作 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 本地文件与远程 URL 输入 | **整体支持**。统一 `OcrRequest` 表达输入，具体上传方式由 Provider 适配器处理 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>               |
| 百度智能云 OCR | **整体支持**。`agents-flex-ocr-baidu` 适配 PaddleOCR-VL 文档解析异步 API | <span class="vp-nowrap">❌ <strong>不支持</strong></span>       |
| Gitee AI OCR | **整体支持**。`agents-flex-ocr-gitee` 适配模力方舟异步文档解析接口 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>       |
| MinerU 文档解析 | **整体支持**。`agents-flex-ocr-mineru` 支持远程 URL，以及本地文件的预签名上传流程 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>       |
| 统一任务状态与结果 | **整体支持**。不同供应商的处理中、成功、失败状态统一映射，并返回 Markdown、资源文件或原始结果 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>               |
| 接入持久化异步任务运行时 | **整体支持**。`OcrAsyncTaskHandler` 可将 OCR 任务交给通用异步任务模块持续跟踪 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>               |

对于单次、短时任务，可以直接使用 `OcrModel` 等待结果；对于 Web 服务、批量文档或耗时不可控的任务，
应将任务交给异步任务模块持久化跟踪，避免占用请求线程，并在进程重启后继续查询。完整用法参见
[OCR 概览](../ocr/overview.md)。

## 通用异步任务与长任务治理对比

视频生成、OCR 和部分云端解析 API 通常先返回外部任务编号，几秒到数分钟后才能取得结果。简单的定时
轮询可以处理少量任务，但进入生产环境后还要面对进程重启、重复查询、多 Worker 竞争、供应商限流、
租户公平性和失败重试。Agents-Flex 将这些共性问题提取为独立的 `agents-flex-async-task` 模块，而不是
让每个模型 Provider 重复实现一套轮询逻辑。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的异步任务生命周期 | **整体支持**。Handler 负责 `submit()` 和 `query()`，Manager、Worker 与 Store 负责持久化、领取、查询、重试和终态更新 | <span class="vp-nowrap">❌ <strong>未发现 AI 任务级对等模块</strong></span> |
| 提交后持久化与跨重启恢复 | **整体支持**。`submit()` 统一持久化提交参数和状态，再由 Worker 创建并持续查询供应商任务；服务重启后可继续处理 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 多 Worker 安全领取 | **整体支持**。Store 通过 Lease、版本控制与条件更新避免多个 Worker 同时推进同一任务 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| JDBC 与 Redis Store | **整体支持**。分别提供 `agents-flex-async-task-store-jdbc` 和 `agents-flex-async-task-store-redis`，另有进程内 Store 用于测试和单机场景 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 每供应商 QPS | **整体支持**。按 `providerKey` 限制排队任务的供应商提交速率，保护供应商接口并降低限流错误 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 每账号并发上限 | **整体支持**。按供应商账号限制同时执行数，避免单账号占满外部服务并发额度 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 租户配额 | **整体支持**。按租户限制运行中任务数量，防止单一租户挤占共享资源 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 优先级队列与延迟提交 | **整体支持**。任务按优先级和计划时间参与领取，可表达紧急任务、退避重试和预约执行 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |
| 暂停某个供应商 | **整体支持**。运行时可按供应商停止新任务准入，并保留已持久化任务等待恢复 | <span class="vp-nowrap">❌ <strong>不支持</strong></span>  |

这里的差异不是“能否写出轮询代码”，而是谁负责保证长任务在故障和并发条件下仍然正确。只处理少量
任务时，应用内定时器通常足够；当任务必须跨重启恢复，或者需要多租户、多个供应商和多个 Worker 时，
统一 Store、状态机和准入策略会显著减少重复基础设施。详细设计参见
[异步任务概览](../async-task/overview.md)与[调度与准入控制](../async-task/scheduling.md)。

## 多模态 Agent 协作对比

ChatModel 接收多模态输入与使用独立模型生成图片、音频或视频是两类能力。双方都允许在对话消息中携带
多模态内容；Agents-Flex 进一步让 Agent 根据完整上下文自动选择普通或多模态 ChatModel。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| ChatModel 消息携带多模态内容 | **整体支持**。统一消息模型可以携带图片、音频、视频和文件，并交给支持相应能力的 ChatModel 处理 | **整体支持**。UserMessage 可通过 Media 携带图片、音频或视频等内容，具体可用类型取决于模型 Provider |
| Agent 根据上下文中的多模态内容切换 ChatModel | **整体支持**。Agent 会扫描完整 `MemoryPrompt`；上下文中仍有图片、音频、视频或文件时，选择配置的 `multimodalChatModel`，否则使用普通 `chatModel` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

## LLM Wiki 对比

LLM Wiki 将企业知识组织为模型可以主动导航的知识树。模型先看到顶层主题的标题和摘要，只有确定某个主题
与当前问题相关时，才通过 Tool 按路径读取正文，并继续发现下一层子主题。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 开箱即用的 LLM Wiki 模块 | **整体支持**。`agents-flex-wiki` 提供 Wiki 数据模型、Provider 与 Tool | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 层级知识树 | **整体支持**。每个 `Wiki` 节点包含 Path、Title、Summary、Content 与 Children | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 递归渐进式知识披露 | **整体支持**。模型先查看节点摘要，再通过 `get_wiki_content` 按需进入子节点，不把整棵知识树放入上下文 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 可替换的 Wiki 内容来源 | **整体支持**。框架定义按路径获取 Wiki 的 `WikiProvider` 接口；应用可以实现该接口，从文件、数据库、Git 仓库或业务知识服务加载内容 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Wiki Front Matter 与扩展元数据 | **整体支持**。节点可以携带版本、标签、作者、更新时间等 Front Matter，并随摘要提供给模型 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 无需向量化的精确路径读取 | **整体支持**。知识通过目录导航和路径读取，不强制依赖 Embedding 或 Vector Store | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 作为标准 Tool 接入 ChatModel 与 Agent | **整体支持**。`WikiTool` 构建为标准 Tool，可直接加入 Prompt、ToolGroup 或 Agent | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

## RAG 能力对比

### 文档解析能力对比

RAG 的质量首先取决于能否把原始文档稳定地转换为可检索内容。Agents-Flex 通过统一的
`DocumentExtractionService` 自动选择专用 Extractor，再使用 Tika 覆盖长尾格式；Spring AI 提供多种
`DocumentReader`，并可通过 `TikaDocumentReader` 解析 Apache Tika 支持的格式。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的文档解析入口与解析器路由 | **整体支持**。`DocumentExtractionService` 通过 `ExtractorRegistry` 按文件类型和优先级自动选择 Extractor，失败时可尝试后续候选 | ⚠️ **部分支持**。提供统一 `DocumentReader` 接口，但 PDF、Markdown、HTML 和 Tika Reader 需由应用按格式选择 |
| PDF | **整体支持**。`PdfTextExtractor` 按页提取文本和页内图片，并组织为 Markdown 风格内容；当前不识别或重建 PDF 表格 | **整体支持**。提供 `PagePdfDocumentReader`、`ParagraphPdfDocumentReader`，也可使用 Tika Reader |
| Word（DOC、DOT） | **整体支持**。`DocExtractor` 专门解析段落、表格与图片，文档内表格自动转换为 Markdown 表格 | **整体支持**。通过 `TikaDocumentReader` 提取文本，不支持将 Word 表格自动输出为 Markdown 表格 |
| Word（DOCX、DOTX、DOCM、DOTM） | **整体支持**。`DocxExtractor` 专门处理段落、列表、表格和嵌入图片，表格自动转换为 Markdown | **整体支持**。通过 `TikaDocumentReader` 提取文本，不支持将 Word 表格自动输出为 Markdown 表格 |
| Excel（XLS、XLSX、XLSM、XLT、XLTX、XLTM） | **整体支持**。`ExcelExtractor` 支持多 Sheet 和公式计算，每个 Sheet 的单元格数据自动输出为 Markdown 表格 | **整体支持**。通过 `TikaDocumentReader` 提取文本，不支持将 Sheet 自动输出为 Markdown 表格 |
| PowerPoint（PPT、PPS、POT） | **整体支持**。`PptExtractor` 专门解析 PowerPoint 97-2003 的文本、表格和图片，幻灯片表格自动转换为 Markdown | **整体支持**。通过 `TikaDocumentReader` 提取文本，不支持将幻灯片表格自动输出为 Markdown 表格 |
| PowerPoint（PPTX、PPSX、POTX、PPTM、PPSM、POTM） | **整体支持**。`PptxExtractor` 提取文本、表格与嵌入图片，幻灯片表格自动转换为 Markdown | **整体支持**。通过 `TikaDocumentReader` 提取文本，不支持将幻灯片表格自动输出为 Markdown 表格 |
| HTML、HTM、XHTML 与 MHTML | **整体支持**。`HtmlExtractor` 清理页面并转换为 Markdown 风格文本 | **整体支持**。`JsoupDocumentReader` 支持元素选择、链接和元数据提取，Tika Reader 可补充通用解析 |
| Markdown | **整体支持**。`PlainTextExtractor` 直接读取 MD 与 Markdown 文件并保留原文 | **整体支持**。`MarkdownDocumentReader` 可按标题、段落和水平分隔线组织 `Document` |
| 纯文本、CSV、TSV、JSON、XML、YAML、配置与代码文件 | **整体支持**。`PlainTextExtractor` 覆盖常见文本、数据、配置和编程语言扩展名，并自动检测常见中文编码 | **整体支持**。`TikaDocumentReader` 可读取文本及 Tika 能识别的结构化文本格式 |
| RTF 与 OpenDocument（ODT、ODS、ODP、ODG 等） | **整体支持**。`TikaDocumentExtractor` 内置对应扩展名与 MIME 类型 | **整体支持**。通过 `TikaDocumentReader` 解析 |
| 邮件（EML、MSG） | **整体支持**。`TikaDocumentExtractor` 支持 RFC 822 邮件与 Outlook MSG | **整体支持**。通过 `TikaDocumentReader` 解析 |
| EPUB、XLSB 与 Apple iWork（Pages、Numbers、Keynote） | **整体支持**。`TikaDocumentExtractor` 内置这些长尾文档的扩展名和 MIME 识别 | **整体支持**。通过 `TikaDocumentReader` 解析 Tika 已安装 Parser 支持的格式 |
| 压缩格式识别与包内文档解析（ZIP、TAR、TGZ、GZ、BZ2、XZ、7Z、RAR） | **整体支持**。`TikaDocumentExtractor` 可识别压缩格式，并通过嵌入文档提取器递归解析包内文档 | ⚠️ **部分支持**。`TikaDocumentReader` 引入的 Tika Standard Parsers 可以识别归档与压缩格式，但当前实现未显式配置嵌入文档提取器，不将递归提取包内文档作为框架保证 |
| 压缩包递归解析的安全治理 | **整体支持**。内置最大递归层级、条目数、单条大小和总解压大小限制 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 文件、URL、InputStream 与字节数组输入 | **整体支持**。提供 File、HTTP、ByteArray、ByteStream 和临时文件 `DocumentSource` | **整体支持**。DocumentReader 基于 Spring `Resource`，可使用文件、URL、InputStreamResource 和 ByteArrayResource |
| PDF、Word、PowerPoint 与长尾文档的嵌入图片提取 | **整体支持**。PDF、DOC/DOCX、PPT/PPTX 专用 Extractor 以及 Tika 长尾解析器可提取图片并在内容中生成 Markdown 图片引用 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 可替换的文档图片处理机制 | **整体支持**。`ExtractedImageHandler` 统一接收图片字节、MIME 类型和文件名，应用可将图片上传对象存储、写入本地或转换为自定义 URL | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 嵌入图片默认转换为 Base64 Data URI | **整体支持**。`Base64ExtractedImageHandler` 可在不依赖外部存储的情况下直接生成可用的 Markdown 图片地址 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Agents-Flex 不只是把所有格式交给一个通用 Parser，而是为 PDF、Word、Excel、PowerPoint 和 HTML 提供专用
Extractor。Word、Excel、PPT 和 PPTX 中的表格会转换为 Markdown 表格，比打平后的纯文本更容易被模型正确理解；
该表格转换能力不包含 PDF。PDF、Word 和 PowerPoint 中的图片统一交给 `ExtractedImageHandler` 处理，最后
再由 Tika 补齐邮件、OpenDocument、iWork 和压缩包等长尾格式。

### RAG 核心能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 标准文档模型和多种切分策略 | **整体支持**。统一 `Document`、Metadata，并内置多种 Splitter | **整体支持**。提供 DocumentReader、Transformer、TokenTextSplitter 等 ETL 组件 |
| 多个 Embedding Provider | **整体支持**。`agents-flex-embedding-*` 已适配 OpenAI、Qwen 与 Ollama | **整体支持且 Provider 更多**。还包括 Google、Vertex、PostgresML 等 |
| 独立于向量库的全文检索抽象 | **整体支持**。`agents-flex-search-engine-*` 以统一 SearchEngine 接入 Lucene、Elasticsearch 与远程 Search Service | ⚠️ **部分支持**。具备 VectorStore/RAG 检索，但没有对等的通用 SearchEngine 层 |
| 可复用于任意召回结果的统一重排模型 | **整体支持**。`agents-flex-rerank-*` 提供独立 `RerankModel`，不限定召回来源 | ⚠️ **部分支持**。RAG 提供 PostProcessor，Bedrock KB 支持 Rerank，但没有通用对等 RerankModel |
| 向量、数据库、层级知识与互联网检索统一接入 Agent | **整体支持**。RAG 可与 WebSearch、Text2SQL、Wiki 组合，让 Agent 按问题选择知识来源 | ⚠️ **部分支持**。RAG 与 Web Tool 可以组合，但缺少现成的 Text2SQL 和 Wiki 模块 |

Spring AI 的模块化 RAG Advisor 很成熟。Agents-Flex 在基础 RAG 之外继续提供独立 Rerank、SearchEngine、
Wiki、Text2SQL 和 WebSearch，使企业知识不必全部压缩成向量检索。

## Vector Store 能力对比

两边都通过统一抽象屏蔽不同向量数据库的写入、删除、相似度查询和元数据过滤差异。Provider 覆盖各有侧重：
Agents-Flex 对国内基础设施和分析型数据库覆盖更直接；Spring AI 的 Provider 总量更多。

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 统一的向量存储抽象 | **整体支持**。`VectorStore`、`DocumentStore`、`StoreOptions` 与 `SearchWrapper` 统一存储及查询接口 | **整体支持**。`VectorStore`、`SearchRequest` 与 Filter Expression 提供统一接口 |
| Apache Cassandra | **整体支持**。`agents-flex-store-cassandra` | **整体支持**。`spring-ai-cassandra-store` |
| Chroma | **整体支持**。`agents-flex-store-chroma` | **整体支持**。`spring-ai-chroma-store` |
| Elasticsearch | **整体支持**。`agents-flex-store-elasticsearch` | **整体支持**。`spring-ai-elasticsearch-store` |
| MariaDB Vector | **整体支持**。`agents-flex-store-mariadb` | **整体支持**。`spring-ai-mariadb-store` |
| Milvus | **整体支持**。`agents-flex-store-milvus` | **整体支持**。`spring-ai-milvus-store` |
| MongoDB Atlas Vector Search | **整体支持**。`agents-flex-store-mongodb-atlas` | **整体支持**。`spring-ai-mongodb-atlas-store` |
| OpenSearch | **整体支持**。`agents-flex-store-opensearch` | **整体支持**。`spring-ai-opensearch-store` |
| PostgreSQL PGvector | **整体支持**。`agents-flex-store-pgvector` | **整体支持**。`spring-ai-pgvector-store` |
| Qdrant | **整体支持**。`agents-flex-store-qdrant` | **整体支持**。`spring-ai-qdrant-store` |
| Redis | **整体支持**。`agents-flex-store-redis` | **整体支持**。`spring-ai-redis-store` |
| Weaviate | **整体支持**。`agents-flex-store-weaviate` | **整体支持**。`spring-ai-weaviate-store` |
| 阿里云 DashVector | **整体支持**。`agents-flex-store-aliyun` 使用 DashVector 官方 Java SDK | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| ClickHouse | **整体支持**。`agents-flex-store-clickhouse` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Infinity | **整体支持**。`agents-flex-store-infinity` | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Azure AI Search | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-azure-store` |
| Amazon Bedrock Knowledge Bases | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-bedrock-knowledgebase-store` |
| Oracle Coherence | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-coherence-store` |
| Couchbase Search | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-couchbase-store` |
| GemFire | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-gemfire-store` |
| Neo4j | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-neo4j-store` |
| Oracle Database | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-oracle-store` |
| Pinecone | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-pinecone-store` |
| Amazon S3 Vectors | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-s3-vector-store` |
| Typesense | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`spring-ai-typesense-store` |
| Redis Semantic Cache | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。提供 `SemanticCache`、Redis `DefaultSemanticCache` 与 `SemanticCacheAdvisor`，可按语义相似度复用已有对话结果 |
| 进程内简易向量存储 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> | **整体支持**。`SimpleVectorStore` 使用进程内存保存向量与文档，支持相似度检索、元数据过滤与文件持久化，适合测试和小型应用 |

Spring AI 在 Vector Store Provider 数量上更丰富，并额外提供进程内 `SimpleVectorStore` 和基于 Redis 的
Semantic Cache；Agents-Flex 则原生覆盖 DashVector、ClickHouse 和 Infinity，对国内云服务、实时分析数据库及
混合检索场景更友好。两边共同支持的 11 个主流存储，已经覆盖大多数企业现有的 Redis、关系型数据库、
搜索引擎和专用向量数据库部署。

## 路由和高可用能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| 对业务透明的多 ChatModel 路由 | **整体支持**。`RoutedChatModel` 将多个模型节点包装成标准 `ChatModel`，上层无需感知路由过程 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| Embedding 高可用路由 | **整体支持**。`RoutedEmbeddingModel` 将节点选择、故障转移与恢复能力扩展到 Embedding | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 按业务上下文动态选择模型候选 | **整体支持**。`ChatModelSelector` 可按 Prompt、租户、区域、成本与请求 Metadata 选择节点 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 基于模型能力标签进行路由 | **整体支持**。节点可以标记 vision、reasoning、cheap 等标签，请求先按所需能力筛选 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 面向模型节点的负载均衡策略 | **整体支持**。提供最少活跃与加权随机策略，并与节点实时状态协同 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 单个模型调用失败重试 | **整体支持**。模型 Provider 和路由层均可配置重试次数与退避策略，对限流和瞬时故障重新发起调用 | **整体支持**。模型实现普遍通过 `RetryTemplate` 对可重试错误执行退避重试 |
| 跨模型节点故障切换 | **整体支持**。失败节点会被排除并自动选择备用节点，同步和流式调用使用统一策略 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 节点级熔断与半开探测恢复 | **整体支持**。内置失败计数、熔断、半开探测与自动恢复，避免持续请求故障节点 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 模型调用指标采集 | **整体支持**。OpenTelemetry Metrics 记录模型调用次数、耗时、Token 与成功失败结果 | **整体支持**。Micrometer Observation 记录模型调用、耗时、Token 与错误信息 |
| 节点实时运行指标参与路由决策 | **整体支持**。活跃请求数、成功/失败记录与熔断状态都会影响节点选择 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 能管理多个模型实例，但“存在多个模型”和“提供模型高可用”不是同一件事。Agents-Flex 的
Router 已经把节点选择、负载、失败、重试和恢复封装在标准 ChatModel 后面，Agent 与业务代码无需感知。

## 可观测能力对比

| 对比项描述 | Agents-Flex | Spring AI |
| --- | --- | --- |
| Chat、Tool 与框架 HTTP 的链路和指标 | **整体支持**。OpenTelemetry Trace 与 Metrics 覆盖同步/流式 Chat、Tool 及框架 HTTP 调用 | **整体支持**。Micrometer Observation 覆盖 ChatClient、Model、Tool、VectorStore 等 |
| HTTP 子 Span 与上下文传播 | **整体支持**。`AgentsFlexHttpClient` 自动创建 Client Span 并传播 Trace Context | **整体支持**。通过 Micrometer 与 HTTP Client 观测体系传播上下文 |
| 模型内容按需采集与隐私保护 | **整体支持**。内容采集默认关闭，开启后执行递归脱敏与长度限制 | **整体支持**。提供 Prompt、Completion、Tool 内容采集开关，并明确提示隐私风险 |
| 标准遥测后端和自定义 Exporter | **整体支持**。可复用全局 OpenTelemetry，也可由框架管理 OTLP、Logging 或自定义 Exporter | **整体支持**。可通过 Micrometer Tracing 与 OTel Bridge 接入监控后端 |
| Span 和 Metric 直接保存到业务数据库 | **整体支持**。`agents-flex-observability-jdbc` 可直接复用应用 `DataSource` 持久化遥测数据 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |
| 按单次 Agent 或业务执行动态路由遥测数据 | **整体支持**。Telemetry Runtime Routing 可为一次执行选择一个或多个 Destination，支持租户及业务隔离 | <span class="vp-nowrap">❌ <strong>不支持</strong></span> |

Spring AI 的 Micrometer 集成非常适合已经使用 Actuator 的 Spring Boot 应用。Agents-Flex 直接采用
OpenTelemetry，同时提供 JDBC 持久化和执行级路由，因此既能进入标准 APM，也能服务需要私有存储、
多租户隔离或不同业务对象分流的系统。

## 总结

**结论：** Spring AI 更适合在 Spring Boot 应用中快速接入模型、Tool Calling 和 RAG，其 Provider
覆盖、自动配置、`ChatClient`、结构化输出与 Spring 生态集成是明确优势。Agents-Flex 更适合把 AI 能力
作为独立运行时建设：它不仅让应用调用模型和工具，还提供可以持续执行、暂停、恢复、调度和观测的 Agent
与异步任务基础设施。

在 Agents-Flex 中，ChatModel、Tool、MCP、Skills、RAG、Wiki、Text2SQL、OCR 和其他多模态能力并非
彼此孤立。Agent 长流程共享 Turn、执行预算、审批表单、快照恢复、模型路由和遥测链路；OCR、视频等
供应商长任务则可共享 Async Task Store、Worker Lease、失败重试、优先级、延迟调度、QPS、账号并发和
租户配额治理。模型节点故障、进程重启或任务转移到其他 Worker 后，系统仍能从已持久化状态继续处理。

如果系统只需要为现有 Spring 应用增加 AI 调用能力，Spring AI 已经提供了成熟的开发体验；如果目标是
构建需要模型高可用、OCR 与视频长任务治理、分布式执行、隔离 Skills 或跨请求恢复的生产级 AI 应用，
Agents-Flex 提供的能力边界更完整，也能减少业务团队自行拼装状态机、Store 和调度设施的工作。

## 扩展阅读

- [ChatModel 快速开始](../chat/getting-started.md)
- [Agent 概述](../agent/overview.md)
- [Skills 模块概述](../skills/overview.md)
- [OCR 文档识别](../ocr/overview.md)
- [异步任务概览](../async-task/overview.md)
- [异步任务调度与准入控制](../async-task/scheduling.md)
- [异步任务 Store](../async-task/store.md)
- [模型路由与高可用](./model-router.md)
- [RAG 文档](../rag/document.md)
- [可观测性](../observability/observability.md)
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)
