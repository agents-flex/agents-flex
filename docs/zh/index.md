---
layout: home

hero:
    name: "Agents-Flex"
    text: "一个轻量的 AI 智能体开发框架"
    tagline: Java 生态首选 | 支持 MCP、AI Skills 与智能问数
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

features:
    - title: MCP (Model Context Protocol)
      details: 原生支持 Model Context Protocol，标准化连接外部数据与工具，打破模型孤岛，实现更广泛的上下文交互。
    - title: AI Skills
      details: 将复杂能力封装为可复用的 AI Skills，简化 Agent 构建流程，支持技能的快速编排与动态加载。
    - title: 智能问数 (Text2SQL)
      details: 内置自然语言查询数据库能力，支持 Text2SQL 与数据分析，让业务人员通过对话即可获取数据洞察。
    - title: LLMs (大语言模型)
      details: 支持市场主流大模型及 Ollama 本地部署，内置 HTTP、SSE、WS 等多种网络协议，轻松对接私有化模型。
    - title: Tool
      details: 内置灵活的 Tool Calls 组件，支持本地方法定义、解析与回调执行，几行代码即可实现工具调用。
    - title: RAG 知识库
      details: 提供 Loader、Parser、Splitter 完整链路，支持多种数据源加载与切片，轻松构建企业级知识库应用。
    - title: Vector & Embedding
      details: 内置多种向量数据库与 Embedding 实现，支持自定义扩展接口，为语义检索提供高效、灵活的底层支持。
    - title: Prompt 工程
      details: 提供丰富的 Prompt Framework 支持（FEW-SHOT、CRISPE 等），支持模板自定义，优化模型输出质量。
    - title: Memory 管理
      details: 提供 MessageMemory 和 ContextMemory，灵活管理多轮对话历史与会话状态，支持自定义扩展以满足复杂场景。
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
