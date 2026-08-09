---
layout: home
title: Agents-Flex
description: 面向 Java 开发者的 AI 应用与智能体框架
---

<main class="afx-home">
  <section class="afx-hero" aria-labelledby="afx-hero-title">
    <div class="afx-container afx-hero__grid">
      <div class="afx-hero__copy">
        <p class="afx-kicker">JAVA AI APPLICATION FRAMEWORK</p>
        <h1 id="afx-hero-title">Agents-Flex</h1>
        <p class="afx-hero__headline">把模型能力带进真实的 Java 系统</p>
        <p class="afx-hero__summary">从统一的模型调用开始，按需组合 Agent、RAG、多模态、Skills 与可观测能力。保持业务架构清晰，也为长任务的恢复和生产运行留出空间。</p>
        <div class="afx-actions">
          <a class="afx-button afx-button--primary" href="/zh/chat/getting-started">快速开始 <span aria-hidden="true">&rarr;</span></a>
          <a class="afx-button" href="/zh/intro/compare-spring-ai">对比 Spring AI</a>
        </div>
        <p class="afx-requirement">Apache 2.0 开源 · 支持 JDK 8+ · 不强制依赖 Spring</p>
      </div>
      <div class="afx-hero__terminal">
        <div class="afx-terminal__bar">
          <span>ChatQuickStart.java</span>
          <span>JAVA</span>
        </div>
        <pre><code><span class="afx-code-type">ChatModel</span> model = OpenAIChatConfig.builder()
    .apiKey(System.getenv(<span class="afx-code-string">"AI_API_KEY"</span>))
    .model(<span class="afx-code-string">"your-model"</span>)
    .buildModel();<br />
String answer = model.chat(
    <span class="afx-code-string">"用一句话介绍 Agents-Flex"</span>
);</code></pre>
        <div class="afx-terminal__result"><span>输出：</span><p>一个面向 Java 开发者的 AI 应用与智能体开发框架。</p></div>
      </div>
    </div>
  </section>

  <nav class="afx-jump" aria-label="选择开始路径">
    <div class="afx-container afx-jump__grid">
      <a href="/zh/chat/getting-started"><span>01</span><strong>调用模型</strong><small>完成第一次对话</small></a>
      <a href="/zh/agent/getting-started"><span>02</span><strong>构建 Agent</strong><small>接入工具与任务循环</small></a>
      <a href="/zh/rag/document"><span>03</span><strong>连接知识</strong><small>搭建完整 RAG 链路</small></a>
      <a href="/zh/skills/getting-started"><span>04</span><strong>执行 Skills</strong><small>交付文件与运行结果</small></a>
    </div>
  </nav>

  <section class="afx-section" aria-labelledby="afx-map-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><h2 id="afx-map-title">按需组合，覆盖 AI 应用的完整链路</h2></div>
        <p>Agents-Flex 将模型、Agent、知识、多模态、执行环境与运行治理组织为可独立引入的模块。可以只使用 ChatModel，也可以随业务逐步扩展。</p>
      </header>
      <div class="afx-capabilities">
        <a href="/zh/chat/chat-model"><span>MODEL</span><h3>模型与对话</h3><p>ChatModel、Prompt、Memory、流式输出、Tool Calling 与 MCP。</p></a>
        <a href="/zh/agent/overview"><span>AGENT</span><h3>智能体运行时</h3><p>任务规划、子 Agent、人工审批、预算、重试、挂起与恢复。</p></a>
        <a href="/zh/rag/document"><span>KNOWLEDGE</span><h3>RAG 与存储</h3><p>文档抽取、切分、Embedding、Rerank 与多种 Vector Store。</p></a>
        <a href="/zh/image/image-generation"><span>MEDIA</span><h3>多模态生成</h3><p>图片、TTS、STT 与视频生成，共享一致的 Java 接入体验。</p></a>
        <a href="/zh/skills/overview"><span>EXECUTION</span><h3>Skills 与 Sandbox</h3><p>封装专业方法，在本机或隔离环境执行脚本并交付产物。</p></a>
        <a href="/zh/observability/observability"><span>OPERATIONS</span><h3>可观测与治理</h3><p>关联 Model、Agent、Tool 与 Runtime，追踪延迟、Token 和状态。</p></a>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--skills" aria-labelledby="afx-skills-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">SKILLS & SANDBOX</p><h2 id="afx-skills-title">Skill 的加载、执行与运行环境 Sandbox</h2></div>
        <p>说明、脚本与参考资料组成可复用的 Skill。统一的 SkillRuntime 承接命令与文件能力，让同一套 Skill 可以在本机开发，也可以进入隔离环境运行。</p>
      </header>
      <div class="afx-skill-architecture" aria-label="Skill 通过 SkillRuntime 在本机或 Sandbox 环境中执行">
        <div class="afx-skill-package">
          <small>可复用能力包</small>
          <div class="afx-skill-package__files" aria-hidden="true"><i></i><i></i><i></i></div>
          <h3>Skill Package</h3>
          <p>SKILL.md · scripts · assets</p>
        </div>
        <div class="afx-skill-connector" aria-hidden="true"><span></span></div>
        <div class="afx-skill-runtime">
          <small>统一执行边界</small>
          <strong>SkillRuntime</strong>
          <span>Shell · Files · Search</span>
        </div>
        <div class="afx-skill-connector" aria-hidden="true"><span></span></div>
        <div class="afx-skill-targets">
          <a href="/zh/skills/local-runtime"><small>01 · 本地</small><strong>Local Runtime</strong><span>可信任务与本地调试</span></a>
          <a href="/zh/skills/open-sandbox"><small>02 · 隔离</small><strong>OpenSandbox</strong><span>按任务创建隔离容器</span></a>
          <a href="/zh/skills/aio-sandbox"><small>03 · 远程</small><strong>AIO Sandbox</strong><span>连接集中部署的服务</span></a>
        </div>
      </div>
      <div class="afx-skill-principles" aria-label="Skills 与 Sandbox 的设计原则">
        <div><small>01</small><strong>灵活加载</strong><span>从本地目录或远程存储发现 Skill，使用时加载完整内容。</span></div>
        <div><small>02</small><strong>受控执行</strong><span>按任务选择本机或 Sandbox，命令和文件操作遵循统一边界。</span></div>
        <div><small>03</small><strong>产物直达</strong><span>生成文件可以下载到本地，也可以发布为可访问的结果。</span></div>
      </div>
      <a class="afx-text-link" href="/zh/skills/overview">了解 Skills 完整架构 <span aria-hidden="true">&rarr;</span></a>
    </div>
  </section>

  <section class="afx-section" aria-labelledby="afx-media-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">MULTIMODAL MODELS</p><h2 id="afx-media-title">用一套 Java 方式处理文本、图像、语音与视频</h2></div>
        <p>每种媒介保留适合自身的调用语义，同时通过统一的 Config、Request、Response 和模型接口降低服务商切换成本。</p>
      </header>
      <div class="afx-media-grid">
        <a href="/zh/chat/getting-started">
          <b class="afx-media-mark" aria-hidden="true">T</b>
          <h3>对话与文本</h3>
          <p>同步或流式调用大模型，组合 Prompt、Memory、Tool Calling 与结构化输出。</p>
          <small>ChatModel · chat() · chatStream()</small>
        </a>
        <a href="/zh/image/image-generation">
          <b class="afx-media-mark" aria-hidden="true">I</b>
          <h3>图片生成与编辑</h3>
          <p>覆盖文生图、参考图、多图融合、局部编辑和多图输出。</p>
          <small>ImageModel · GenerateImageRequest</small>
        </a>
        <a href="/zh/audio/tts-stt">
          <b class="afx-media-mark" aria-hidden="true">A</b>
          <h3>语音合成与识别</h3>
          <p>提供 TTS、STT 和流式语音合成，适配实时 AI 语音交互。</p>
          <small>TextToSpeech · SpeechToText</small>
        </a>
        <a href="/zh/video/video-generation">
          <b class="afx-media-mark" aria-hidden="true">V</b>
          <h3>视频生成任务</h3>
          <p>统一提交、查询和等待异步任务，支持文生视频、图生视频与编辑。</p>
          <small>VideoModel · TaskStatus · Video</small>
        </a>
      </div>
      <div class="afx-media-notes" aria-label="多模态模型的调用方式">
        <span><b>流式响应</b>对话与语音内容生成即返回</span>
        <span><b>同步返回</b>图片生成完成后直接获得结果</span>
        <span><b>异步任务</b>视频提交后按任务状态查询结果</span>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--dark" aria-labelledby="afx-production-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">BUILT FOR LONG-RUNNING WORK</p><h2 id="afx-production-title">让智能任务可控、可恢复、可观测</h2></div>
        <p>AgentTurn 把一次任务建模为可持久化状态。人工审批、外部等待、进程重启和后台接管都能在同一执行契约下处理。</p>
      </header>
      <ol class="afx-runtime" aria-label="请求经过 Agent Runner 和工具执行后写入状态存储及可观测系统">
        <li><small>01 · 输入</small><strong>Request</strong><span>Business context</span></li>
        <li class="afx-runtime__core"><small>02 · 编排</small><strong>AgentRunner</strong><span>Policy + Middleware</span></li>
        <li><small>03 · 执行</small><strong>Model + Tools</strong><span>Skills + Sandbox</span></li>
        <li><small>04 · 持久化</small><strong>Turn Store</strong><span>Snapshot + Events</span></li>
      </ol>
      <div class="afx-production-links">
        <a href="/zh/agent/suspend-resume"><strong>挂起与恢复</strong><span>处理审批和外部回调</span></a>
        <a href="/zh/agent/store"><strong>状态持久化</strong><span>支持 JDBC、Redis 与自定义 Store</span></a>
        <a href="/zh/observability/getting-started"><strong>运行可观测</strong><span>查看模型、工具和任务状态</span></a>
      </div>
    </div>
  </section>

  <section class="afx-section afx-docs" aria-labelledby="afx-docs-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><h2 id="afx-docs-title">按开发阶段深入文档</h2></div>
        <p>从模型接入、Agent 编排到知识检索与生产运行，选择当前任务继续阅读。</p>
      </header>
      <div class="afx-docs__grid">
        <div><h3>模型与工具</h3><a href="/zh/chat/getting-started">模型调用快速开始</a><a href="/zh/chat/tool-build">构建与调用 Tool</a><a href="/zh/chat/mcp">接入 MCP 服务</a><a href="/zh/chat/text2sql">构建 Text2SQL 应用</a></div>
        <div><h3>Agent 编排</h3><a href="/zh/agent/getting-started">Agent 快速开始</a><a href="/zh/agent/task-planning">任务规划</a><a href="/zh/agent/human-approval">人工审批</a><a href="/zh/agent/form-input">表单输入</a></div>
        <div><h3>知识与检索</h3><a href="/zh/rag/document">文档处理与切分</a><a href="/zh/store/overview">Vector Store</a><a href="/zh/rag/embedding">Embedding</a><a href="/zh/rag/rerank">Rerank</a></div>
        <div><h3>生产与交付</h3><a href="/zh/skills/getting-started">Skills 快速开始</a><a href="/zh/skills/open-sandbox">Sandbox 隔离执行</a><a href="/zh/observability/observability">可观测性</a><a href="/changes">更新记录</a></div>
      </div>
    </div>
  </section>

  <section class="afx-final" aria-labelledby="afx-final-title">
    <div class="afx-container afx-final__inner">
      <div><h2 id="afx-final-title">开始构建你的 Java AI 应用</h2><p>从 ChatModel 起步，按需加入知识、工具、Skills 与 Agent。</p></div>
      <div class="afx-actions"><a class="afx-button afx-button--primary" href="/zh/chat/getting-started">开始使用 <span aria-hidden="true">&rarr;</span></a><a class="afx-button" href="https://github.com/agents-flex/agents-flex" target="_blank" rel="noreferrer">查看 GitHub</a></div>
    </div>
  </section>
</main>
