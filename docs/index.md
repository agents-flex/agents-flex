---
layout: home
hero:
  name: "Agents-Flex"
  text: "Java AI 应用开发框架"
  tagline: "从一次模型调用，到可恢复、可观测的智能任务。"
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
---

<div class="af-home-shell">
  <section class="af-home-intro">
    <header>
      <p class="af-eyebrow">JAVA AI APPLICATION STACK</p>
      <h2>把 AI 能力接进<br /><span>真实的 Java 系统</span></h2>
    </header>
    <p class="af-lead">Agents-Flex 以清晰的模块边界组织模型、对话、工具、知识和运行时。你可以从一个 ChatModel 开始，按业务增长逐步加入 Agent、RAG，以及图片、语音和视频生成。</p>
  </section>
  <section class="af-paths" aria-label="文档入口"><a class="af-path af-path--primary" href="/zh/chat/getting-started"><span class="af-path__index">01</span><span class="af-path__label">FIRST RUN</span><h3>第一次使用</h3><p>安装依赖、配置模型，并完成第一轮对话。</p><span class="af-path__arrow" aria-hidden="true">→</span></a><a class="af-path" href="/zh/agent/getting-started"><span class="af-path__index">02</span><span class="af-path__label">BUILD AGENTS</span><h3>构建 Agent</h3><p>把工具和任务流程交给可恢复的智能体。</p><span class="af-path__arrow" aria-hidden="true">→</span></a><a class="af-path" href="/zh/rag/document"><span class="af-path__index">03</span><span class="af-path__label">ADD KNOWLEDGE</span><h3>接入知识库</h3><p>从文档抽取、切分到向量检索，搭建 RAG 链路。</p><span class="af-path__arrow" aria-hidden="true">→</span></a><a class="af-path" href="/zh/skills/overview"><span class="af-path__index">04</span><span class="af-path__label">RUN SAFELY</span><h3>隔离执行任务</h3><p>使用 Skills 与 Sandbox 承载脚本和文件操作。</p><span class="af-path__arrow" aria-hidden="true">→</span></a></section>
  <section class="af-capabilities" aria-label="Agents-Flex 能力">
    <div class="af-section-heading"><p class="af-eyebrow">CAPABILITIES</p><h2>不止对话，覆盖完整的 AI 应用链路</h2><p>按需引入模块，用同一套 Java 方式处理文本、工具、知识，以及图片、语音和视频生成。</p></div>
    <div class="af-capability-grid">
      <a href="/zh/chat/getting-started"><b>CHAT</b><strong>对话与大模型</strong><span>ChatModel、Prompt、Memory、流式输出与多模型接入</span><i>→</i></a>
      <a href="/zh/agent/overview"><b>AGENT</b><strong>智能体与任务编排</strong><span>工具调用、任务规划、子 Agent、人工审批与持久化恢复</span><i>→</i></a>
      <a href="/zh/rag/document"><b>KNOWLEDGE</b><strong>RAG 与知识库</strong><span>文档抽取、Splitter、Embedding、Vector Store 与 Rerank</span><i>→</i></a>
      <a href="/zh/image/image-generation"><b>IMAGE</b><strong>图片生成</strong><span>OpenAI、Gemini、阿里云、火山引擎与 Gitee AI</span><i>→</i></a>
      <a href="/zh/audio/tts-stt"><b>AUDIO</b><strong>语音合成与识别</strong><span>TTS、STT、流式播放、网页录音及多家服务商</span><i>→</i></a>
      <a href="/zh/video/video-generation"><b>VIDEO</b><strong>视频生成</strong><span>异步视频任务、状态追踪与阿里云、火山等接入</span><i>→</i></a>
      <a href="/zh/chat/mcp"><b>CONNECT</b><strong>MCP 与 Web 能力</strong><span>连接外部工具、WebSearch、WebFetch 与 LLM Wiki</span><i>→</i></a>
      <a href="/zh/skills/overview"><b>RUNTIME</b><strong>Skills 与 Sandbox</strong><span>渐进式加载、文件产物、本机运行与隔离沙箱</span><i>→</i></a>
    </div>
  </section>
  <section class="af-code-section">
    <div class="af-section-heading"><p class="af-eyebrow">QUICK START</p><h2>先调用模型，再逐步扩展</h2><p>保持现有 Java 应用结构，只增加需要的模块。</p></div>
    <div class="af-code-card"><div class="af-code-card__bar"><span><i></i><i></i><i></i></span><strong>ChatDemo.java</strong><em>agents-flex</em></div><pre><code>ChatModel model = OpenAIChatConfig.builder()
    .model("Qwen3-32B")
    .apiKey(System.getenv("API_KEY"))
    .buildModel();
    String reply = model.chat("介绍一下 Agents-Flex");</code></pre><a href="/zh/chat/getting-started">查看完整对话示例 →</a></div>
  </section>
  <section class="af-layers">
    <div class="af-section-heading"><p class="af-eyebrow">ARCHITECTURE</p><h2>一套可组合的工程底座</h2></div>
    <div class="af-layer-list">
      <a href="/zh/chat/getting-started"><b>01</b><strong>模型与对话</strong><span>ChatModel · Prompt · Memory · Tool</span><i>→</i></a>
      <a href="/zh/agent/overview"><b>02</b><strong>Agent 与编排</strong><span>AgentTurn · Planning · Subagent · Snapshot</span><i>→</i></a>
      <a href="/zh/rag/document"><b>03</b><strong>知识与检索</strong><span>Document · Embedding · VectorStore · Rerank</span><i>→</i></a>
      <a href="/zh/skills/runtime"><b>04</b><strong>运行时与生产</strong><span>Skills · Sandbox · Routing · Observability</span><i>→</i></a>
    </div>
  </section>
  <section class="af-home-footer"><div><p class="af-eyebrow">READY WHEN YOU ARE</p><h2>从一段代码开始。</h2></div><div class="af-footer-actions"><a class="af-button af-button--brand" href="/zh/chat/getting-started">开始使用</a><a class="af-button" href="https://github.com/agents-flex/agents-flex">查看源码 ↗</a></div></section>
</div>
