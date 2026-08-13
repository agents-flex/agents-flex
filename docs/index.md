---
layout: page
title: Agents-Flex
description: 面向 Java 开发者的 AI 应用与智能体框架
---

<main class="afx-home">
  <span class="afx-home__hydration-marker" aria-hidden="true">{{ $frontmatter.title }}</span>
  <section class="afx-hero" aria-labelledby="afx-hero-title">
    <div class="afx-container afx-hero__grid">
      <div class="afx-hero__copy">
        <h1 id="afx-hero-title">Agents-Flex</h1>
        <p class="afx-hero__headline">Java AI，从模型调用到生产运行</p>
        <p class="afx-hero__summary">从统一的模型调用开始，按需组合 Agent、RAG、多模态、OCR、Skills 与可观测能力。保持业务架构清晰，也通过异步任务为长任务恢复和生产运行留出空间。</p>
        <div class="afx-actions">
          <a class="afx-button afx-button--primary" href="/zh/chat/getting-started.html">快速开始 <span aria-hidden="true">&rarr;</span></a>
          <a class="afx-button" href="/zh/intro/compare-spring-ai.html">对比 Spring AI</a>
        </div>
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

  <div class="afx-facts" aria-label="项目特性">
    <div class="afx-container afx-facts__inner">
      <span>Apache 2.0 开源</span>
      <span>支持 JDK 8+</span>
      <span>不强制依赖 Spring</span>
    </div>
  </div>

  <nav class="afx-jump" aria-label="选择开始路径">
    <div class="afx-container afx-jump__grid">
      <a href="/zh/chat/getting-started.html"><span>ChatModel</span><strong>调用模型</strong><small>完成第一次对话</small></a>
      <a href="/zh/agent/getting-started.html"><span>Agent</span><strong>构建 Agent</strong><small>接入工具与任务循环</small></a>
      <a href="/zh/rag/document.html"><span>RAG</span><strong>连接知识</strong><small>搭建完整检索链路</small></a>
      <a href="/zh/skills/getting-started.html"><span>Skills</span><strong>执行 Skills</strong><small>交付文件与运行结果</small></a>
    </div>
  </nav>

  <section class="afx-section" aria-labelledby="afx-map-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <h2 id="afx-map-title">按需组合，覆盖 AI 应用的完整链路</h2>
        <p>Agents-Flex 将模型、Agent、知识、多模态、执行环境与运行治理组织为可独立引入的模块。可以只使用 ChatModel，也可以随业务逐步扩展。</p>
      </header>
      <div class="afx-capabilities">
        <a href="/zh/chat/chat-model.html"><span>MODEL</span><h3>模型与对话</h3><p>ChatModel、Prompt、Memory、流式输出、Tool Calling 与 MCP。</p></a>
        <a href="/zh/agent/overview.html"><span>AGENT</span><h3>智能体运行时</h3><p>任务规划、子 Agent、人工审批、预算、重试、挂起与恢复。</p></a>
        <a href="/zh/rag/document.html"><span>KNOWLEDGE</span><h3>RAG 与存储</h3><p>文档抽取、切分、Embedding、Rerank 与多种 Vector Store。</p></a>
        <a href="/zh/image/image-generation.html"><span>MEDIA</span><h3>多模态与文档</h3><p>图片、TTS、STT、视频生成与 OCR 文档解析，共享一致的 Java 接入体验。</p></a>
        <a href="/zh/skills/overview.html"><span>EXECUTION</span><h3>Skills 与 Sandbox</h3><p>封装专业方法，在本机或隔离环境执行脚本并交付产物。</p></a>
        <a href="/zh/observability/observability.html"><span>OPERATIONS</span><h3>可观测与治理</h3><p>关联 Model、Agent、Tool 与 Runtime，追踪延迟、Token 和状态。</p></a>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--skills" aria-labelledby="afx-skills-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <div><p class="afx-kicker">SKILLS & SANDBOX</p><h2 id="afx-skills-title">从 Skill 加载到 Sandbox 执行</h2></div>
        <p>Skill 封装说明、脚本与资源，SkillRuntime 提供统一执行边界，再按任务路由到本机或 Sandbox。能力定义保持不变，运行环境可以独立选择。</p>
      </header>
      <div class="afx-skill-architecture" aria-label="Skill 通过 SkillRuntime 在本机或 Sandbox 环境中执行">
        <article class="afx-skill-node afx-skill-node--package">
          <small>SKILL</small>
          <h3>Skill Package</h3>
          <p>可复用、可版本化的能力定义。</p>
          <div class="afx-skill-manifest" aria-label="Skill 包的组成">
            <span><b>SKILL.md</b><em>说明与流程</em></span>
            <span><b>scripts/</b><em>可执行脚本</em></span>
            <span><b>assets/</b><em>模板与资源</em></span>
          </div>
          <div class="afx-skill-node__note"><small>DISCOVER</small><span>从本地目录或远程存储发现并加载。</span></div>
        </article>
        <div class="afx-skill-flow__connector" aria-hidden="true"><span>加载</span><b>&rarr;</b></div>
        <article class="afx-skill-node afx-skill-node--runtime">
          <small>RUNTIME</small>
          <h3>SkillRuntime</h3>
          <p>为不同运行环境提供一致的命令与文件接口。</p>
          <div class="afx-skill-runtime__apis" aria-label="SkillRuntime 提供的能力">
            <span><strong>执行命令</strong><small>Shell</small></span>
            <span><strong>读写文件</strong><small>Files</small></span>
            <span><strong>搜索内容</strong><small>Search</small></span>
          </div>
          <div class="afx-skill-node__note"><small>CONTROL</small><span>在统一边界内执行命令、读写文件与搜索内容。</span></div>
        </article>
        <div class="afx-skill-flow__connector" aria-hidden="true"><span>路由</span><b>&rarr;</b></div>
        <article class="afx-skill-node afx-skill-node--targets">
          <small>EXECUTION TARGET</small>
          <h3>选择运行环境</h3>
          <a class="afx-skill-local" href="/zh/skills/local-runtime.html"><span><small>LOCAL</small><strong>Local Runtime</strong></span><em>可信任务与本地调试</em><b aria-hidden="true">&rarr;</b></a>
          <div class="afx-sandbox-group">
            <div class="afx-sandbox-group__header"><span><small>SANDBOX</small><strong>隔离或远程执行</strong></span></div>
            <a href="/zh/skills/open-sandbox.html"><span><strong>OpenSandbox</strong><em>按任务创建隔离容器</em></span><b aria-hidden="true">&rarr;</b></a>
            <a href="/zh/skills/aio-sandbox.html"><span><strong>AIO Sandbox</strong><em>连接集中部署的服务</em></span><b aria-hidden="true">&rarr;</b></a>
          </div>
          <div class="afx-skill-node__note"><small>DELIVER</small><span>将文件下载到本地，或发布为可访问结果。</span></div>
        </article>
      </div>
      <a class="afx-text-link" href="/zh/skills/overview.html">了解 Skills 完整架构 <span aria-hidden="true">&rarr;</span></a>
    </div>
  </section>

  <section class="afx-section" aria-labelledby="afx-media-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <h2 id="afx-media-title">用一套 Java 方式处理文本、图像、语音、视频与文档</h2>
        <p>每种内容保留适合自身的调用语义，同时通过统一的 Config、Request、Response 和模型接口降低服务商切换成本。</p>
      </header>
      <div class="afx-media-grid">
        <a href="/zh/chat/getting-started.html">
          <b class="afx-media-mark" aria-hidden="true">T</b>
          <h3>对话与文本</h3>
          <p>同步或流式调用大模型，组合 Prompt、Memory、Tool Calling 与结构化输出。</p>
          <small>ChatModel · chat() · chatStream()</small>
        </a>
        <a href="/zh/image/image-generation.html">
          <b class="afx-media-mark" aria-hidden="true">I</b>
          <h3>图片生成与编辑</h3>
          <p>覆盖文生图、参考图、多图融合、局部编辑和多图输出。</p>
          <small>ImageModel · GenerateImageRequest</small>
        </a>
        <a href="/zh/audio/tts-stt.html">
          <b class="afx-media-mark" aria-hidden="true">A</b>
          <h3>语音合成与识别</h3>
          <p>提供 TTS、STT 和流式语音合成，适配实时 AI 语音交互。</p>
          <small>TextToSpeech · SpeechToText</small>
        </a>
        <a href="/zh/video/video-generation.html">
          <b class="afx-media-mark" aria-hidden="true">V</b>
          <h3>视频生成任务</h3>
          <p>统一提交、查询和等待异步任务，支持文生视频、图生视频与编辑。</p>
          <small>VideoModel · TaskStatus · Video</small>
        </a>
        <a class="afx-media-grid__wide" href="/zh/ocr/overview.html">
          <b class="afx-media-mark" aria-hidden="true">O</b>
          <h3>OCR 文档识别</h3>
          <p>统一解析图片与 PDF，支持百度智能云、Gitee AI 和 MinerU，并输出文本、Markdown 或结果资源。</p>
          <small>OcrModel · OcrRequest · OcrResponse</small>
        </a>
      </div>
      <div class="afx-media-notes" aria-label="多模态模型的调用方式">
        <span><b>流式响应</b>对话与语音内容生成即返回</span>
        <span><b>同步返回</b>图片生成完成后直接获得结果</span>
        <span><b>异步任务</b>OCR 与视频提交后按任务状态查询结果</span>
      </div>
    </div>
  </section>

  <section class="afx-section afx-section--production" aria-labelledby="afx-production-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <h2 id="afx-production-title">让智能任务可控、可恢复、可观测</h2>
        <p>AgentTurn 管理智能体执行状态，Async Task 持久化跟踪 OCR、视频等供应商长任务。人工审批、外部等待、进程重启和后台接管都有明确的恢复路径。</p>
      </header>
      <ol class="afx-runtime" aria-label="请求经过 Agent Runner 和工具执行后写入状态存储及可观测系统">
        <li><small>输入</small><strong>Request</strong><span>Business context</span></li>
        <li class="afx-runtime__core"><small>编排</small><strong>AgentRunner</strong><span>Policy + Middleware</span></li>
        <li><small>执行</small><strong>Model + Tools</strong><span>Skills + Sandbox</span></li>
        <li><small>持久化</small><strong>Turn Store</strong><span>Snapshot + Events</span></li>
      </ol>
      <div class="afx-production-links">
        <a href="/zh/agent/suspend-resume.html"><strong>挂起与恢复</strong><span>处理审批和外部回调</span></a>
        <a href="/zh/agent/store.html"><strong>状态持久化</strong><span>支持 JDBC、Redis 与自定义 Store</span></a>
        <a href="/zh/async-task/overview.html"><strong>异步任务调度</strong><span>持久化跟踪、限流、配额与多 Worker 接管</span></a>
        <a href="/zh/observability/getting-started.html"><strong>运行可观测</strong><span>查看模型、工具和任务状态</span></a>
      </div>
    </div>
  </section>

  <section class="afx-section afx-docs" aria-labelledby="afx-docs-title">
    <div class="afx-container">
      <header class="afx-section__header">
        <h2 id="afx-docs-title">按开发阶段深入文档</h2>
        <p>从模型接入、Agent 编排到知识检索与生产运行，选择当前任务继续阅读。</p>
      </header>
      <div class="afx-docs__grid">
        <div><h3>模型与工具</h3><a href="/zh/chat/getting-started.html">模型调用快速开始</a><a href="/zh/ocr/getting-started.html">OCR 文档识别</a><a href="/zh/chat/tool-build.html">构建与调用 Tool</a><a href="/zh/chat/mcp.html">接入 MCP 服务</a><a href="/zh/chat/text2sql.html">构建 Text2SQL 应用</a></div>
        <div><h3>Agent 编排</h3><a href="/zh/agent/getting-started.html">Agent 快速开始</a><a href="/zh/agent/task-planning.html">任务规划</a><a href="/zh/agent/human-approval.html">人工审批</a><a href="/zh/agent/form-input.html">表单输入</a></div>
        <div><h3>知识与检索</h3><a href="/zh/rag/document.html">文档处理与切分</a><a href="/zh/store/overview.html">Vector Store</a><a href="/zh/rag/embedding.html">Embedding</a><a href="/zh/rag/rerank.html">Rerank</a></div>
        <div><h3>生产与交付</h3><a href="/zh/async-task/getting-started.html">异步任务与恢复</a><a href="/zh/skills/getting-started.html">Skills 快速开始</a><a href="/zh/skills/open-sandbox.html">Sandbox 隔离执行</a><a href="/zh/observability/observability.html">可观测性</a><a href="/changes.html">更新记录</a></div>
      </div>
    </div>
  </section>

  <section class="afx-final" aria-labelledby="afx-final-title">
    <div class="afx-container afx-final__inner">
      <div><h2 id="afx-final-title">开始构建你的 Java AI 应用</h2><p>从 ChatModel 起步，按需加入知识、工具、Skills 与 Agent。</p></div>
      <div class="afx-actions"><a class="afx-button afx-button--primary" href="/zh/chat/getting-started.html">开始使用 <span aria-hidden="true">&rarr;</span></a><a class="afx-button" href="https://github.com/agents-flex/agents-flex" target="_blank" rel="noreferrer">查看 GitHub</a></div>
    </div>
  </section>
</main>
