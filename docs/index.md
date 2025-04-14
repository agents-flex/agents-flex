---
layout: home

hero:
  name: "Agents-Flex"
  text: "一个优雅的 LLM（大语言模型）应用开发框架"
  tagline: 对标 LangChain、使用 Java 开发、简单、轻量。
  image:
    src: /assets/images/logo.png
    alt: Agents-Flex
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/intro/getting-started
    - theme: alt
      text: 帮助文档
      link: /zh/intro/what-is-agentsflex

features:
  - title: LLMs (大语言模型)
    details: Agents-Flex 支持市场上了常见的大语言模型，支持 Ollama 部署模型，也内置了丰富的对接大模型的网络协议，比如 HTTP、SSE、WS 等，使得开发者可以使用其轻易的对接各种自有大模型。
  - title: Prompt（提示词）
    details: Agents-Flex 提供了丰富的大语言模型开发模板以及 Prompt Framework 的支持，比如 FEW-SHOT、CRISPE、BROKE、ICIO 等。另外，Prompt Template 我们也可以自定义自己独特的内容。
  - title: Function Calling
    details: Agents-Flex 内置了非常灵活的 Function Calling 组件，包括本地方法的定义、解析、通过 LLMs 回调、并执行本地方法到结果，开发者几行代码就可以完成 Function Calling。
  - title: Document
    details: Agents-Flex 在文本处理方面、内置了 Loader、Parser、Splitter 三大组件，而每种组件又有多重不同的实现类，因此，我们可以轻易的加载网络数据、本地数据、数据库数据，以及多种数据类型。
  - title: Memory
    details: Agents-Flex 的 Memory 模块分为 MessageMemory 和 ContextMemory，他们分别用于历史对话和 Chain 执行上下文记录，我们可以通过继承 Memory 去实现更加丰富的扩展。
  - title: Embedding
    details: Agents-Flex 内置了非常丰富的 Embedding 能力和扩展，我们可以通过去实现 Embedding 接口，来扩充自己的私有 Embedding 算法和支持 。
  - title: Vector Store
    details: Agents-Flex 内置了多种向量数据库支持、当然，我们可以自己去实现 VectorStore 接口，来扩充自己的私有 VectorStore 服务 。
  - title: Chain
    details: Agents-Flex 的 Chain 包含了同步顺序执行 Chain，异步执行 Chain 以及循环执行 Chain，帮助开发面对多种不同场景 。
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
