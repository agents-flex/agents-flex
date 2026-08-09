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
          <a class="afx-button" href="/zh/intro/what-is-agentsflex">了解框架</a>
        </div>
        <p class="afx-requirement">支持 JDK 8+，MCP 模块需要 JDK 17+</p>
      </div>
      <div class="afx-hero__terminal" role="img" aria-label="使用 Agents-Flex 发起一次模型对话的 Java 示例">
        <div class="afx-terminal__bar">
          <span>ChatQuickStart.java</span>
          <span>JAVA</span>
        </div>
        <pre><code><span class="afx-code-type">ChatModel</span> model = OpenAIChatConfig.builder()
    .apiKey(System.getenv(<span class="afx-code-string">"AI_API_KEY"</span>))
    .model(<span class="afx-code-string">"your-model"</span>)
    .buildModel();
String answer = model.chat(
    <span class="afx-code-string">"用一句话介绍 Agents-Flex"</span>
);</code></pre>
        <div class="afx-terminal__result"><span>RESULT</span><p>面向 Java 开发者的 AI 应用与智能体框架。</p></div>
      </div>
    </div>
  </section>

  <nav class="afx-jump" aria-label="选择开始路径">
    <div class="afx-container afx-jump__grid">
      <a href="/zh/chat/getting-started"><span>01</span><strong>调用模型</strong><small>完成第一次对话</small><i aria-hidden="true">&rarr;</i></a>
      <a href="/zh/agent/getting-started"><span>02</span><strong>构建 Agent</strong><small>接入工具与任务循环</small><i aria-hidden="true">&rarr;</i></a>
      <a href="/zh/rag/document"><span>03</span><strong>连接知识</strong><small>搭建完整 RAG 链路</small><i aria-hidden="true">&rarr;</i></a>
      <a href="/zh/skills/getting-started"><span>04</span><strong>执行 Skills</strong><small>交付文件与运行结果</small><i aria-hidden="true">&rarr;</i></a>
    </div>
  </nav>

  <section class="afx-section" aria-labelledby="afx-map-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">CAPABILITY MAP</p><h2 id="afx-map-title">按业务需要组合，不被单一场景绑住</h2></div>
        <p>配置中的核心文档域被整理为六条能力线。可以只使用 ChatModel，也可以逐步扩展为具备知识、工具、执行环境与运行治理的完整系统。</p>
      </header>
      <div class="afx-capabilities">
        <a href="/zh/chat/chat-model"><span>MODEL</span><h3>模型与对话</h3><p>ChatModel、Prompt、Memory、流式输出、Tool Calling 与 MCP。</p><b aria-hidden="true">&rarr;</b></a>
        <a href="/zh/agent/overview"><span>AGENT</span><h3>智能体运行时</h3><p>任务规划、子 Agent、人工审批、预算、重试、挂起与恢复。</p><b aria-hidden="true">&rarr;</b></a>
        <a href="/zh/rag/document"><span>KNOWLEDGE</span><h3>RAG 与存储</h3><p>文档抽取、切分、Embedding、Rerank 与多种 Vector Store。</p><b aria-hidden="true">&rarr;</b></a>
        <a href="/zh/image/image-generation"><span>MEDIA</span><h3>多模态生成</h3><p>图片、TTS、STT 与视频生成，共享一致的 Java 接入体验。</p><b aria-hidden="true">&rarr;</b></a>
        <a href="/zh/skills/overview"><span>EXECUTION</span><h3>Skills 与 Sandbox</h3><p>封装专业方法，在本机或隔离环境执行脚本并交付产物。</p><b aria-hidden="true">&rarr;</b></a>
        <a href="/zh/observability/observability"><span>OPERATIONS</span><h3>可观测与治理</h3><p>关联 Model、Agent、Tool 与 Runtime，追踪延迟、Token 和状态。</p><b aria-hidden="true">&rarr;</b></a>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--skills" aria-labelledby="afx-skills-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">SKILLS & SANDBOX</p><h2 id="afx-skills-title">让 Skill 在可控环境中完成真实工作</h2></div>
        <p>Skill 把说明、脚本与资源封装为可复用能力；SkillRuntime 提供统一的命令和文件接口，让同一套 Skill 可以在本机或隔离 Sandbox 中执行。</p>
      </header>
      <div class="afx-skill-flow" role="img" aria-label="Skill 从定义、安装、执行到产物交付的完整流程">
        <div><small>DEFINE</small><strong>Skill Package</strong><span>SKILL.md + scripts + assets</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div><small>INSTALL</small><strong>Artifact Store</strong><span>版本、校验与节点物化</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div class="afx-skill-flow__core"><small>EXECUTE</small><strong>SkillRuntime</strong><span>Shell + Files + Search</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div><small>DELIVER</small><strong>Artifacts</strong><span>文件、数据与发布结果</span></div>
      </div>
      <div class="afx-runtime-options">
        <a href="/zh/skills/local-runtime"><span>LOCAL</span><strong>Local Runtime</strong><p>适合可信 Skill、本地开发与快速调试，直接使用宿主机环境。</p><i aria-hidden="true">&rarr;</i></a>
        <a href="/zh/skills/open-sandbox"><span>ISOLATED</span><strong>OpenSandbox</strong><p>按任务创建隔离容器，控制文件、网络、超时与生命周期。</p><i aria-hidden="true">&rarr;</i></a>
        <a href="/zh/skills/aio-sandbox"><span>REMOTE</span><strong>AIO Sandbox</strong><p>连接已运行的 Sandbox 服务，适合集中部署与远程执行。</p><i aria-hidden="true">&rarr;</i></a>
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
          <div><span>TEXT</span><b aria-hidden="true">T</b></div>
          <h3>对话与文本</h3>
          <p>同步或流式调用大模型，组合 Prompt、Memory、Tool Calling 与结构化输出。</p>
          <small>ChatModel · chat() · chatStream()</small>
          <i aria-hidden="true">&rarr;</i>
        </a>
        <a href="/zh/image/image-generation">
          <div><span>IMAGE</span><b aria-hidden="true">I</b></div>
          <h3>图片生成与编辑</h3>
          <p>覆盖文生图、参考图、多图融合、局部编辑和多图输出。</p>
          <small>ImageModel · GenerateImageRequest</small>
          <i aria-hidden="true">&rarr;</i>
        </a>
        <a href="/zh/audio/tts-stt">
          <div><span>AUDIO</span><b aria-hidden="true">A</b></div>
          <h3>语音合成与识别</h3>
          <p>提供 TTS、STT 和流式语音合成，适配实时 AI 语音交互。</p>
          <small>TextToSpeech · SpeechToText</small>
          <i aria-hidden="true">&rarr;</i>
        </a>
        <a href="/zh/video/video-generation">
          <div><span>VIDEO</span><b aria-hidden="true">V</b></div>
          <h3>视频生成任务</h3>
          <p>统一提交、查询和等待异步任务，支持文生视频、图生视频与编辑。</p>
          <small>VideoModel · TaskStatus · Video</small>
          <i aria-hidden="true">&rarr;</i>
        </a>
      </div>
      <div class="afx-media-notes" aria-label="多模态模块共同特性">
        <span><b>统一配置</b>模型与连接参数集中管理</span>
        <span><b>能力声明</b>调用前识别模型支持范围</span>
        <span><b>服务商适配</b>业务代码不绑定供应商协议</span>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--dark" aria-labelledby="afx-production-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">BUILT FOR LONG-RUNNING WORK</p><h2 id="afx-production-title">让智能任务可控制、可恢复、可观察</h2></div>
        <p>AgentTurn 把一次任务建模为可持久化状态。人工审批、外部等待、进程重启和后台接管都能在同一执行契约下处理。</p>
      </header>
      <div class="afx-runtime" role="img" aria-label="请求经过 Agent Runner 和工具执行后写入状态存储及可观测系统">
        <div><small>INPUT</small><strong>Request</strong><span>Business context</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div class="afx-runtime__core"><small>ORCHESTRATE</small><strong>AgentRunner</strong><span>Policy + Middleware</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div><small>EXECUTE</small><strong>Model + Tools</strong><span>Skills + Sandbox</span></div>
        <i aria-hidden="true">&rarr;</i>
        <div><small>PERSIST</small><strong>Turn Store</strong><span>Snapshot + Events</span></div>
      </div>
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
        <div><p class="afx-kicker">DOCUMENTATION</p><h2 id="afx-docs-title">沿着问题找到下一篇文档</h2></div>
        <p>首页只保留方向选择，完整概念、API、示例和生产建议都在对应文档域中持续维护。</p>
      </header>
      <div class="afx-docs__grid">
        <div><h3>构建应用</h3><a href="/zh/chat/getting-started">ChatModel 快速开始</a><a href="/zh/chat/tool-build">Tool 构建</a><a href="/zh/chat/mcp">MCP</a><a href="/zh/chat/text2sql">Text2SQL</a></div>
        <div><h3>运行 Agent</h3><a href="/zh/agent/architecture">架构设计</a><a href="/zh/agent/task-planning">任务规划</a><a href="/zh/agent/human-approval">人工审批</a><a href="/zh/agent/worker">AgentWorker</a></div>
        <div><h3>连接数据</h3><a href="/zh/rag/document">RAG 文档</a><a href="/zh/store/overview">Vector Store</a><a href="/zh/rag/embedding">Embedding</a><a href="/zh/rag/rerank">Rerank</a></div>
        <div><h3>进入生产</h3><a href="/zh/skills/runtime">Skill Runtime</a><a href="/zh/skills/open-sandbox">Sandbox</a><a href="/zh/observability/observability">可观测性</a><a href="/changes">更新记录</a></div>
      </div>
    </div>
  </section>

  <section class="afx-final" aria-labelledby="afx-final-title">
    <div class="afx-container afx-final__inner">
      <div><p class="afx-kicker">READY TO BUILD</p><h2 id="afx-final-title">从一次模型调用开始。</h2><p>先完成最小闭环，再按业务需要加入知识、工具与智能体能力。</p></div>
      <div class="afx-actions"><a class="afx-button afx-button--primary" href="/zh/chat/getting-started">开始使用 <span aria-hidden="true">&rarr;</span></a><a class="afx-button" href="https://github.com/agents-flex/agents-flex" target="_blank" rel="noreferrer">查看 GitHub</a></div>
    </div>
  </section>
</main>
