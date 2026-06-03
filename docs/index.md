---
layout: home

hero:
    name: "Agents-Flex"
    text: "一个轻量的 AI 智能体开发框架"
    tagline: Java 生态首选 | 支持 RAG、MCP、AI Skills、WebSearch、Subagent、LLM Wiki 与企业级可观测
    image:
        src: /assets/images/logo.png
        alt: Agents-Flex
    actions:
        - theme: brand
          text: 快速开始
          link: /zh/chat/getting-started
        - theme: alt
          text: 帮助文档
          link: /zh/intro/what-is-agentsflex
        - theme: alt
          text: GitHub
          link: https://github.com/agents-flex/agents-flex

features:
    - title: 🌐 WebSearch 网络搜索
      details: 内置多搜索引擎支持（Bocha、Brave），提供域名过滤、Markdown 格式化输出，无缝集成 LLM Tool 系统，让 Agent 具备实时网络检索能力。
    - title: 🔗 MCP (Model Context Protocol)
      details: 原生支持 Model Context Protocol，标准化连接外部数据与工具，打破模型孤岛，实现更广泛的上下文交互与工具编排。
    - title: 🎯 AI Skills
      details: 将复杂业务能力封装为可复用的 AI Skills，简化 Agent 构建流程，支持技能的快速编排、动态加载与技能市场共享。
    - title: 📊 智能问数 (Text2SQL)
      details: 内置自然语言查询数据库能力，支持 Text2SQL 生成、数据分析与可视化，让业务人员通过对话即可获取数据洞察。
    - title: ⚖️ LLM 负载均衡与高可用
      details: 提供企业级 LLM 路由策略，支持多模型负载均衡、故障自动转移与熔断重试，保障高并发下的服务稳定性。
    - title: 🔍 全链路可观测
      details: 内置强大的监控体系，支持 OpenTelemetry 集成、Token 消耗统计、分布式链路追踪、耗时分析及敏感信息日志脱敏。
    - title: 📚 LLM Wiki 知识树
      details: 超越传统 RAG，支持 LLM Wiki 知识树与图谱集成，通过结构化数据关联提升模型对复杂领域知识的理解与推理能力。
    - title: 🧠 多模型集成 (LLMs)
      details: 支持 OpenAI、Qwen、DeepSeek、Ollama 等主流大模型及本地部署，内置 HTTP、SSE、WS 等多种协议，轻松对接私有化模型。
    - title: 🔧 灵活的工具调用 (Tool)
      details: 内置 Function Calling 引擎，支持本地方法反射、MCP 协议工具、JavaScript/Python 脚本执行，几行代码即可实现工具调用。
    - title: 📖 RAG 知识库
      details: 提供 Loader、Parser、Splitter 完整链路，支持 PDF/Word/HTML 等多格式文档加载与智能切片，轻松构建企业级知识库应用。
    - title: 🗄️ Vector & Embedding
      details: 内置 PGVector、Milvus、Chroma、Redis 等多种向量数据库与 Embedding 实现，支持自定义扩展接口，为语义检索提供高效支持。
    - title: 🎨 图像生成与理解
      details: 支持 DALL·E、通义万相、文心一格等图像模型，提供文本到图像、图像到文本的双向能力，拓展多模态应用场景。
    - title: 📝 Prompt 工程
      details: 提供丰富的 Prompt Framework 支持（FEW-SHOT、CRISPE、ROLE-PLAY 等），支持模板自定义与版本管理，优化模型输出质量。
    - title: 💭 Memory 管理
      details: 提供 MessageMemory 和 ContextMemory，灵活管理多轮对话历史与会话状态，支持持久化存储与自定义扩展以满足复杂场景。
    - title: 🔄 SubAgent 子智能体
      details: 支持 hierarchical agent 架构，主 Agent 可委派任务给专业子 Agent，实现复杂任务的分解与协作执行。
    - title: 🔎 搜索引擎集成
      details: 支持 Elasticsearch、Lucene 等搜索引擎集成，提供统一的检索接口，为企业级全文检索与混合检索提供支持。
    - title: 📈 ReRank 重排序
      details: 内置智能重排序服务，优化 RAG 检索结果的相关性评分，提升最终答案的准确性与质量。
    - title: ⚙️ Spring Boot 集成
      details: 提供 agents-flex-spring-boot-starter，支持自动配置、属性注入与生产级部署，无缝融入现有 Java/Spring 生态系统。

---

<style>
:root {
  --vp-home-hero-name-color: transparent;
  --vp-home-hero-name-background: -webkit-linear-gradient(120deg, #bd34fe 30%, #41d1ff);

  --vp-home-hero-image-background-image: linear-gradient(-45deg, #bd34fe 50%, #47caff 50%);
  --vp-home-hero-image-filter: blur(44px);
}

@media (min-width: 640px) {
  :root {
    --vp-home-hero-image-filter: blur(56px);
  }
}

@media (min-width: 960px) {
  :root {
    --vp-home-hero-image-filter: blur(68px);
  }
}
</style>
