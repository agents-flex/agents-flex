---
layout: home

hero:
  name: Agents-Flex
  text: A Java framework for LLM applications
  tagline: lightweight, simple, and more elegant.
  image:
    src: /assets/images/logo.png
    alt: Agents-Flex
  actions:
    - theme: brand
      text: Quick Start
      link: /intro/getting-started
    - theme: alt
      text: Documentation
      link: /intro/what-is-agentsflex

features:
  - title: LLMs Connector
    details: Agents-Flex includes a variety of network protocols for connecting LLMs, such as HTTP, SSE and WS. Its simple and flexible design allows developers to easily connect to various LLMs, including OpenAI, LLama, and Others AI.
  - title: Prompt
    details: Agents-Flex provides a rich set of development templates and Prompt Frameworks, including FEW-SHOT, CRISPE, BROKE, and ICIO. Developers can also customize their own unique prompt templates.
  - title: Function Calling
    details: Agents-Flex has a very flexible Function Calling component. It supports local method definitions, parsing, callbacks through LLMs, and executing local methods to obtain results.
  - title: Document
    details: Agents-Flex offers Loader, Parser, and Splitter components for the Document. Each component has multiple implementations, making it easy to load data from the web, local files, databases, and various data types.
  - title: Memory
    details: The Memory module of Agents-Flex is divided into MessageMemory and ContextMemory, used for recording chat messages and Chain execution contexts. Developers can extend the Memory module by inheritance to achieve richer functionalities.
  - title: Embedding
    details: Agents-Flex includes extensive embedding capabilities and extensions. Developers can implement the Embedding interface to expand their private embedding algorithms and support.
  - title: Vector Store
    details: Agents-Flex supports multiple vector databases. Developers can also implement the VectorStore interface to expand their private VectorStore services.
  - title: Agent
    details: Agents-Flex defines an abstract implementation of Agents. Developers can use the Agents Chain to create more interactive applications.
  - title: Chain
    details: Agents-Flex’s Chain includes sequential Chains, asynchronous Chains, and loop Chains, helping developers handle various scenarios.
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
