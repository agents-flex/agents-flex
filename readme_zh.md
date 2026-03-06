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
| **MCP 支持** | 原生集成 Model Context Protocol，标准化连接外部数据源与工具 | 跨系统上下文共享、工具编排 |
| **AI Skills** | 将业务能力封装为可复用、可编排的 Skill 单元 | 快速构建领域 Agent、技能市场 |
| **智能问数** | 内置 Text2SQL 与自然语言数据分析能力 | 业务人员零代码查询、数据洞察 |

### 🔧 基础能力矩阵

```
🧠 模型接入        🔌 工具调用          📚 知识增强
├─ 主流大模型适配     ├─ Function Calling ├─ 多格式文档加载
├─ Ollama 本地部署   ├─ MCP 工具协议       ├─ 智能文本分割
├─ HTTP/SSE/WS 协议  ├─ 本地方法反射执行   ├─ 向量存储集成
├─ 多 Provider 管理  ├─ 执行拦截与监控     ├─ 自定义 Embedding

⚙️ 工程化支持      🔍 可观测性        🛡️ 企业级保障
├─ Prompt 模板引擎  ├─ OpenTelemetry 集成 ├─ 敏感信息脱敏
├─ 多轮记忆管理      ├─ 链路追踪与指标      ├─ 资源安全关闭
├─ 异步/流式响应     ├─ 结构化日志输出      ├─ Apache 2.0 协议
```

> 💡 **设计原则**：零侵入集成 · 接口驱动扩展 · 配置优于编码 · 生产环境友好


## ⚡ 快速开始

### 1️⃣ 添加依赖（Maven）

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.0.3</version>
</dependency>
<!-- 按需引入扩展模块 -->
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-mcp</artifactId>
    <version>2.0.3</version>
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


## 📦 模块概览

```
agents-flex/
├── agents-flex-bom                    # 📦 BOM 依赖管理，统一模块版本
├── agents-flex-core                   # 🧱 核心抽象：Model/Prompt/Memory/Tool SPI
├── agents-flex-chat                   # 💬 聊天对话引擎：同步/流式/异步调用
├── agents-flex-tool                   # 🔧 Function Calling 引擎：方法定义/解析/执行
├── agents-flex-mcp                    # 🔗 MCP 协议支持：标准化上下文与工具连接（新增）
├── agents-flex-skills                 # 🎯 AI Skills ：能力封装与动态加载（新增）
├── agents-flex-text2sql                   # 📄 智能问数
├── agents-flex-embedding              # 🔢 Embedding 服务：模型对接与向量生成
├── agents-flex-store                  # 🗄️ 存储扩展：VectorStore/Memory 持久化实现
├── agents-flex-search-engine          # 🔍 搜索引擎集成：ES/DB/自定义检索源
├── agents-flex-rerank                 # 📊 重排序服务：提升 RAG 检索相关性
├── agents-flex-image                  # 🖼️ 图像能力：文生图/图生文模型对接
├── agents-flex-spring-boot-starter   # ⚙️ Spring Boot 自动配置（生产推荐）
├── demos/                             # 🧪 示例项目：MCP/Skills/Text2SQL 实战
├── docs/                              # 📚 文档
└── testresource/                      # 🧪 测试资源文件
```
已根据您提供的实际目录结构，更新 README.md 中的模块概览部分，并优化了模块职责描述，使其更准确、专业：

## 📚 文档与资源

| 类型 | 链接                                                                 | 说明 |
|------|--------------------------------------------------------------------|------|
| 📘 中文文档 | [https://agentsflex.com](https://agentsflex.com)                 | 完整 API 指南 + 最佳实践 |
| 🧪 示例仓库 | [/demos](./demos)                                                  | MCP 集成 / Skills 编排 / Text2SQL 实战 |
| 📋 变更日志 | [/changes.md](./changes.md)                                        | 版本迭代记录与迁移指南 |
| 🐛 问题反馈 | [GitHub Issues](https://github.com/agents-flex/agents-flex/issues) | Bug 报告 / 需求建议 |



### Star 用户专属交流群

![](./docs/assets/images/wechat-group.jpg)


## 🤝 贡献指南

我们遵循 [Apache Way](https://apache.org/theapacheway) 与 [Contributor Covenant](https://www.contributor-covenant.org/) 准则：

1. Fork 仓库 → 创建特性分支 (`feature/xxx`)
2. 代码规范：`mvn spotless:apply` 自动格式化（基于 Google Java Style）
3. 补充单元测试：核心模块覆盖率 ≥ 80%
4. 提交 PR 并关联 Issue，描述变更动机与影响范围

> 🌟 特别欢迎：Java 8/11/17 兼容性测试、企业场景案例、多语言文档翻译


## 📜 开源协议

Agents-Flex 采用 **Apache License 2.0** 协议，您可以：

- ✅ 免费用于商业项目
- ✅ 修改源码并私有化部署
- ✅ 贡献代码共建生态

> 请保留原始版权声明，并在分发时注明修改内容。详情见 [LICENSE](./LICENSE)

