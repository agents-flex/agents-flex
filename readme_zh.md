<p align="center">
    <img src="./docs/assets/images/banner-zh.png"/>
</p>



<p align="center">
    <strong>一个优雅的 Java LLM 应用开发框架 | 真开源 · 易集成 · 生产就绪</strong>
</p>

---

## 🚀 核心特性

Agents-Flex 专为 Java 工程师与架构师设计，提供**轻量、模块化、可扩展**的 AI 智能体开发体验，助力企业快速构建生产级 LLM 应用。

### ✨ 新增核心能力（v2.0+）

| 特性 | 说明 | 应用场景 |
|------|------|----------|
| **WebSearch 网络搜索** | 内置多搜索引擎支持（Bocha、Brave），域名过滤与 Markdown 格式化输出 | Agent 实时网络检索、信息获取 |
| **MCP 支持** | 原生集成 Model Context Protocol，标准化连接外部数据源与工具 | 跨系统上下文共享、工具编排 |
| **AI Skills** | 将业务能力封装为可复用、可编排的 Skill 单元 | 快速构建领域 Agent、技能市场 |
| **智能问数** | 内置 Text2SQL 与自然语言数据分析能力 | 业务人员零代码查询、数据洞察 |

### 🔧 基础能力矩阵

```

🌐 网络搜索        🧠 模型接入        🔌 工具调用          📚 知识增强
├─ 多搜索引擎适配   ├─ 主流大模型适配     ├─ Function Calling ├─ 多格式文档加载
├─ 域名白/黑名单    ├─ Ollama 本地部署   ├─ MCP 工具协议       ├─ 智能文本分割
├─ Markdown 输出    ├─ HTTP/SSE/WS 协议  ├─ 本地方法反射执行   ├─ 向量存储集成
└─ LLM Tool 集成   └─ 多 Provider 管理  └─ JS/Python 脚本     └─ 自定义 Embedding

⚙️ 工程化支持      🔍 可观测性        🛡️ 企业级保障
├─ Prompt 模板引擎  ├─ OpenTelemetry 集成 ├─ 敏感信息脱敏
├─ 多轮记忆管理      ├─ 链路追踪与指标      ├─ 负载均衡与熔断
├─ 异步/流式响应     ├─ 结构化日志输出      ├─ 资源安全关闭
└─ Spring Boot 集成 └─ Token 消耗统计      └─ Apache 2.0 协议
```
> 💡 **设计原则**：零侵入集成 · 接口驱动扩展 · 配置优于编码 · 生产环境友好


## ⚡ 快速开始

### 1️⃣ 添加依赖（Maven）

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.1.9</version>
</dependency>
<!-- 按需引入扩展模块 -->
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-websearch</artifactId>
    <version>2.1.9</version>
</dependency>
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-mcp</artifactId>
    <version>2.1.9</version>
</dependency>
```
### 2️⃣ Hello World

```java
public class QuickStart {
    public static void main(String[] args) {
        // 1. 配置模型（支持 GiteeAI / OpenAI / Ollama 等）
        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_AI_KEY")) // ✅ 建议从环境变量读取
            .model("Qwen3-32B")
            .buildModel();

        // 2. 发起对话（同步/流式/异步均支持）
        String response = chatModel.chat("用 Java 开发者能理解的方式，解释什么是幽默？");

        // 3. 输出结果
        System.out.println("🤖 Agents-Flex: " + response);
    }
}
```
**控制台输出示例**：
```text
[Agents-Flex] >>> [GiteeAI/Qwen3-32B] Request: {"model":"Qwen3-32B","messages":[...]}
[Agents-Flex] <<< [GiteeAI/Qwen3-32B] Response: 200 OK (1.2s)
🤖 Agents-Flex: 幽默就像代码中的优雅异常处理——看似意外，实则精心设计...
```
> 📝 日志前缀 `[Agents-Flex]` 可通过 `application.properties` 自定义或关闭，生产环境建议配合 SLF4J 使用。

### 3️⃣ WebSearch 网络搜索示例

```java
public class WebSearchDemo {
    public static void main(String[] args) {
        // 1. 创建 WebSearchTool（支持 Bocha、Brave 等搜索引擎）
        WebSearchTool searchTool = WebSearchTool.builder()
            .provider(new BochaSearchProvider(System.getenv("BOCHA_APIKEY")))
            .maxResults(10)
            .build();

        // 2. 注册到 Agent 的工具集
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addTools(ToolScanner.scan(searchTool));

        // 3. Agent 自动调用搜索工具
        UserMessage message = new UserMessage("帮我搜索 Agents-Flex 框架的最新特性");
        prompt.addMessage(message);

        // 4. 执行对话（Agent 会自动调用 web_search 工具）
        chatModel.chatStream(prompt, listener);
    }
}
```
**搜索结果示例**：

```markdown
# Agents-Flex v2.1.5 发布 - 新增 WebSearch 支持

URL: https://github.com/agents-flex/agents-flex/releases

Agents-Flex v2.1.5 版本新增了 WebSearch 网络搜索模块，
支持 Bocha 和 Brave 搜索引擎，提供域名过滤功能...

-----

# Agents-Flex 官方文档

URL: https://agentsflex.com/zh/intro/what-is-agentsflex

Agents-Flex 是一个优雅的 Java LLM 应用开发框架，
支持 MCP、AI Skills、Text2SQL 等企业级特性...
```
## 📦 模块概览

```
agents-flex/
├── agents-flex-bom                    # 📦 BOM 依赖管理，统一模块版本
├── agents-flex-core                   # 🧱 核心抽象：Model/Prompt/Memory/Tool SPI
├── agents-flex-chat                   # 💬 聊天对话引擎：同步/流式/异步调用
├── agents-flex-tool                   # 🔧 Function Calling 引擎：方法定义/解析/执行
├── agents-flex-mcp                    # 🔗 MCP 协议支持：标准化上下文与工具连接（新增）
├── agents-flex-skills                 # 🎯 AI Skills ：能力封装与动态加载（新增）
├── agents-flex-text2sql               # 📊 智能问数：Text2SQL 与数据分析（新增）
├── agents-flex-websearch              # 🌐 网络搜索：多搜索引擎集成与域名过滤（新增）
├── agents-flex-subagent               # 🔄 子智能体：分层 Agent 架构与任务委派（新增）
├── agents-flex-wiki                   # 📚 Wiki 知识树：结构化知识管理与图谱集成（新增）
├── agents-flex-embedding              # 🔢 Embedding 服务：模型对接与向量生成
├── agents-flex-store                  # 🗄️ 存储扩展：VectorStore/Memory 持久化实现
├── agents-flex-search-engine          # 🔍 搜索引擎集成：ES/Lucene/自定义检索源
├── agents-flex-rerank                 # 📈 重排序服务：提升 RAG 检索相关性
├── agents-flex-image                  # 🖼️ 图像能力：文生图/图生文模型对接
├── agents-flex-spring-boot-starter   # ⚙️ Spring Boot 自动配置（生产推荐）
├── demos/                             # 🧪 示例项目：WebSearch/MCP/Skills/Text2SQL 实战
├── docs/                              # 📚 文档源码（VitePress）
└── testresource/                      # 🧪 测试资源文件
```
✅ **生产环境推荐**：
- 使用 `agents-flex-spring-boot-starter` 配合配置中心管理 API Key
- 通过 `@Value("${xxx}")` + 加密配置注入敏感信息（API Keys / 数据库密码）
- 启用 `management.endpoints.web.exposure.include=metrics,trace` 集成监控
- RAG 场景组合使用：`websearch` + `embedding` + `store` + `rerank` 模块


## 🎯 典型应用场景

### 场景 1：智能客服助手
```java
// 结合 RAG + WebSearch + Memory
prompt.addTools(ToolScanner.scan(WebSearchTool.builder()
    .provider(new BochaSearchProvider(apiKey))
    .build()));
prompt.addMemory(new MessageMemory()); // 多轮对话记忆
```
### 场景 2：数据分析助手
```java
// Text2SQL + 可视化
Text2SQLTool sqlTool = new Text2SQLTool(dataSource);
prompt.addTools(ToolScanner.scan(sqlTool));
```
### 场景 3：跨系统自动化
```java
// MCP + AI Skills
MCPClient mcpClient = new MCPClient("https://mcp-server.example.com");
prompt.addTools(mcpClient.discoverTools());
```
## 📚 文档与资源

| 类型 | 链接                                                                 | 说明 |
|------|--------------------------------------------------------------------|------|
| 📘 中文文档 | [https://agentsflex.com](https://agentsflex.com)                 | 完整 API 指南 + 最佳实践 |
| 🧪 示例仓库 | [/demos](./demos)                                                  | WebSearch/MCP/Skills/Text2SQL 实战 |
| 📋 变更日志 | [/changes.md](./changes.md)                                        | 版本迭代记录与迁移指南 |
| 🐛 问题反馈 | [GitHub Issues](https://github.com/agents-flex/agents-flex/issues) | Bug 报告 / 需求建议 |
| 💬 社区讨论 | [Discussions](https://github.com/agents-flex/agents-flex/discussions) | Q&A、想法交流、社区支持 |


### Star 用户专属交流群

![](./docs/assets/images/wechat-group.jpg)


## 🤝 贡献指南

我们遵循 [Apache Way](https://apache.org/theapacheway) 与 [Contributor Covenant](https://www.contributor-covenant.org/) 准则：

1. Fork 仓库 → 创建特性分支 (`feature/xxx`)
2. 代码规范：`mvn spotless:apply` 自动格式化（基于 Google Java Style）
3. 补充单元测试：核心模块覆盖率 ≥ 80%
4. 提交 PR 并关联 Issue，描述变更动机与影响范围

> 🌟 特别欢迎：Java 8/11/17 兼容性测试、企业场景案例、多语言文档翻译、新搜索引擎适配


## 📜 开源协议

Agents-Flex 采用 **Apache License 2.0** 协议，您可以：

- ✅ 免费用于商业项目
- ✅ 修改源码并私有化部署
- ✅ 贡献代码共建生态

> 请保留原始版权声明，并在分发时注明修改内容。详情见 [LICENSE](./LICENSE)

## 贡献用户

<img src="https://contrib.rocks/image?repo=agents-flex/agents-flex" />
