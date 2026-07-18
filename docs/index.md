---
layout: home
---
<main class="af-home">
  <section class="af-hero">
    <div class="af-hero__copy">
      <p class="af-eyebrow"><span aria-hidden="true"></span> Java AI Agent Framework / Spatial Workspace</p>
      <h1>
        <span class="af-hero__brand">Agents-flex</span>
        <span class="af-hero__claim">轻量级、高性能的</span>
        <span class="af-hero__descriptor">Java Agent 开发框架</span>
      </h1>
      <p class="af-hero__lead">Agents-Flex 为 Java 开发者统一大模型、图片生成、语音 TTS / STT、视频生成、Tool Calling、Skills、Sandbox、RAG 与 Agent 编排能力，帮助团队更快构建可上线的多模态 AI 应用。</p>
      <div class="af-actions" aria-label="首页快捷入口">
        <a class="af-button af-button--primary" href="/zh/chat/getting-started">快速开始</a>
        <a class="af-button" href="/zh/intro/what-is-agentsflex">了解框架</a>
        <a class="af-button" href="/zh/intro/maven">Maven 依赖</a>
      </div>
      <div class="af-metrics" aria-label="框架能力摘要">
        <div><strong>多模态</strong><span>统一对话、图片、语音与视频</span></div>
        <div><strong>隔离执行</strong><span>Skills 可运行于本机或远程 Sandbox</span></div>
        <div><strong>中国生态</strong><span>适配国产模型、云服务与私有化部署</span></div>
      </div>
    </div>
    <div class="af-hero__visual" aria-label="Agents-Flex 能力结构">
      <img src="./assets/images/home-hero-motion.svg" width="960" height="660" alt="空间界面风格的 Agents-Flex 工作区，以前后分层的悬浮平面展示模型、智能体、知识、技能与生产系统" />
    </div>
  </section>
  <section class="af-stack-stage">
    <div class="af-stack-stage__inner">
      <div class="af-stack-intro">
        <div><p class="af-stack-overline"><span aria-hidden="true"></span> Interactive Capability Map</p><h2>从模型到生产落地的<br /><em>一套 Java AI 工程栈</em></h2></div>
        <p>不把 AI 应用拆成孤立功能点，而是按真实开发流程组织：接入模型，连接工具与知识，再完成 Agent 编排和生产可观测。</p>
      </div>
      <div class="af-stack-field" aria-label="Agents-Flex Java AI 工程栈能力地图">
        <span class="af-stack-cursor" aria-hidden="true"><i></i></span>
        <div class="af-stack-axis af-stack-axis--x" aria-hidden="true"></div>
        <div class="af-stack-axis af-stack-axis--y" aria-hidden="true"></div>
        <div class="af-stack-core"><span>AF</span><strong>Agents-Flex</strong><small>统一工程底座</small></div>
        <article class="af-stack-node af-stack-node--model">
          <div class="af-stack-node__head"><span>01 / MODEL</span><i aria-hidden="true"></i></div><h3>统一模型抽象</h3>
          <p>统一接入对话、图片、视频、Embedding、Rerank 与语音模型。</p>
          <div><span>流式响应</span><span>模型路由</span><span>多协议</span></div>
        </article>
        <article class="af-stack-node af-stack-node--agent">
          <div class="af-stack-node__head"><span>02 / AGENT</span><i aria-hidden="true"></i></div><h3>工具与智能体编排</h3>
          <p>连接 Java 方法、MCP、Skills 与 Subagent，组织复杂任务流程。</p>
          <div><span>Tool Calling</span><span>ReAct</span><span>Routing</span></div>
        </article>
        <article class="af-stack-node af-stack-node--knowledge">
          <div class="af-stack-node__head"><span>03 / KNOWLEDGE</span><i aria-hidden="true"></i></div><h3>RAG 与结构化知识</h3>
          <p>覆盖文档处理、向量检索、重排、WebSearch 与层级知识导航。</p>
          <div><span>Vector Store</span><span>Rerank</span><span>LLM Wiki</span></div>
        </article>
        <article class="af-stack-node af-stack-node--production">
          <div class="af-stack-node__head"><span>04 / PRODUCTION</span><i aria-hidden="true"></i></div><h3>生产级保障</h3>
          <p>提供路由、重试熔断、调用链追踪、指标采集与自动配置。</p>
          <div><span>Retry</span><span>OpenTelemetry</span><span>Spring Boot</span></div>
        </article>
      </div>
      <div class="af-stack-legend"><span><i></i>模型层</span><span><i></i>编排层</span><span><i></i>知识层</span><span><i></i>生产层</span><strong>四层能力协同</strong></div>
    </div>
  </section>
  <section class="af-skills-stage">
    <div class="af-skills-stage__inner">
      <div class="af-skills-intro">
        <div>
          <p class="af-skills-overline"><span aria-hidden="true"></span> Skills / Sandbox</p>
          <h2 class="af-skills-title">
            <span>把能力装进 <em>Skill</em></span>
            <span>把风险留在 <em>Sandbox</em></span>
          </h2>
        </div>
        <div class="af-skills-intro__copy">
          <p>操作说明、脚本、参考资料和模板组成可复用的 Skill。统一的 SkillRuntime 接管命令与文件能力，让同一套 Skill 可以在本机开发，也可以进入远程隔离环境运行。</p>
          <a class="af-skills-link" href="/zh/chat/skills">阅读 Skills 与 Sandbox 指南 <span aria-hidden="true">→</span></a>
        </div>
      </div>
      <div class="af-runtime-motion" role="img" aria-label="Skill 工作包进入 SkillRuntime 核心后，按任务分流到本机或远程 Sandbox，并输出可下载或发布的产物">
        <div class="af-runtime-motion__grid" aria-hidden="true"></div>
        <div class="af-motion-source">
          <span class="af-motion-caption">可复用能力包</span>
          <div class="af-motion-packets" aria-hidden="true">
            <i style="--packet: 0"></i><i style="--packet: 1"></i><i style="--packet: 2"></i><i style="--packet: 3"></i>
          </div>
          <strong>Skill</strong>
          <p>说明 · 脚本 · 参考 · 模板</p>
        </div>
        <div class="af-motion-rail af-motion-rail--in" aria-hidden="true"><i></i><i></i><i></i></div>
        <div class="af-motion-core">
          <span class="af-motion-core__orbit" aria-hidden="true"><i></i><i></i><i></i></span>
          <span class="af-motion-core__pulse" aria-hidden="true"></span>
          <div><small>统一执行边界</small><strong>SkillRuntime</strong></div>
        </div>
        <div class="af-motion-rail af-motion-rail--out" aria-hidden="true"><i></i><i></i><i></i></div>
        <div class="af-motion-targets">
          <div class="af-motion-target af-motion-target--local"><span>01</span><strong>本机运行</strong><small>可信开发任务</small></div>
          <div class="af-motion-target af-motion-target--open"><span>02</span><strong>OpenSandbox</strong><small>远程容器隔离</small></div>
          <div class="af-motion-target af-motion-target--aio"><span>03</span><strong>AIO Sandbox</strong><small>连接隔离服务</small></div>
        </div>
        <div class="af-motion-output">
          <span>READ</span><span>WRITE</span><span>EDIT</span><span>BASH</span>
          <i aria-hidden="true"></i>
          <strong>验证 · 下载 · 发布</strong>
        </div>
      </div>
      <div class="af-skills-facts">
        <div><span>01</span><strong>按需加载</strong><p>选中后再读取完整 Skill，脚本和素材不挤占初始上下文。</p></div>
        <div><span>02</span><strong>边界一致</strong><p>命令与文件工具共享同一个 Runtime，执行不会意外越界。</p></div>
        <div><span>03</span><strong>产物直达</strong><p>生成文件可下载到本地，也可发布为用户可访问的 URL。</p></div>
      </div>
    </div>
  </section>
  <section class="af-multimodal-stage">
    <div class="af-multimodal-stage__inner">
      <div class="af-multimodal-intro">
        <div>
          <p class="af-multimodal-overline"><span aria-hidden="true"></span> Multimodal Generation</p>
          <h2 class="af-multimodal-title"><span>不止文本</span><span>统一构建 <em>图片</em>、<em>语音</em>与<em>视频</em></span></h2>
        </div>
        <p>通过稳定的 Java 接口屏蔽不同服务商的请求与响应差异，让内容生成、实时语音交互和异步视频任务自然接入现有业务系统。</p>
      </div>
      <div class="af-ai-workbench" aria-label="Agents-Flex 多模态生成工作台示意">
        <div class="af-ai-workbench__bar">
          <div><span class="af-ai-mark" aria-hidden="true"></span><strong>Agents-Flex</strong><small>多模态生成</small></div>
          <span class="af-ai-live"><i aria-hidden="true"></i>模型已连接</span>
        </div>
        <div class="af-ai-workbench__body">
          <div class="af-ai-context">
            <span class="af-ai-label">当前任务</span>
            <p class="af-ai-prompt">为产品发布稿生成主视觉、中文旁白和 6 秒演示视频。</p>
            <div class="af-ai-thinking">
              <div class="af-ai-thinking__head"><span class="af-ai-avatar">AF</span><strong>正在编排多模态任务</strong><span class="af-ai-dots" aria-hidden="true"><i></i><i></i><i></i></span></div>
              <ol>
                <li><span>01</span>提取视觉主题与镜头节奏</li>
                <li><span>02</span>选择匹配的模型与生成参数</li>
                <li><span>03</span>并行执行并统一返回结果</li>
              </ol>
            </div>
            <div class="af-ai-context__meta"><span>统一请求</span><span>自动路由</span><span>流式返回</span></div>
          </div>
          <div class="af-ai-router">
            <div class="af-ai-router__head"><div><span class="af-ai-label">模型路由</span><strong>4 个任务并行执行</strong></div><span>JAVA API</span></div>
            <div class="af-ai-jobs">
              <article class="af-ai-job">
                <span class="af-ai-job__index">01</span><div><small>IMAGE MODEL</small><h3>图片生成</h3><p>文生图、图片编辑与变体</p></div><span class="af-ai-job__state"><i></i>已就绪</span><a href="/zh/core/image" aria-label="查看图片生成文档">→</a>
              </article>
              <article class="af-ai-job">
                <span class="af-ai-job__index">02</span><div><small>STREAMING TTS</small><h3>文字转语音</h3><p>音色、语速与流式输出</p></div><span class="af-ai-job__state"><i></i>流式中</span><a href="/zh/audio/getting-started" aria-label="查看文字转语音文档">→</a>
              </article>
              <article class="af-ai-job">
                <span class="af-ai-job__index">03</span><div><small>SPEECH TO TEXT</small><h3>语音转文字</h3><p>文件、URL 与音频流输入</p></div><span class="af-ai-job__state"><i></i>已识别</span><a href="/zh/audio/tts-stt" aria-label="查看语音转文字文档">→</a>
              </article>
              <article class="af-ai-job af-ai-job--active">
                <span class="af-ai-job__index">04</span><div><small>VIDEO MODEL</small><h3>视频生成</h3><p>异步任务与状态追踪</p></div><span class="af-ai-job__state"><i></i>生成中</span><a href="/zh/video/getting-started" aria-label="查看视频生成文档">→</a>
              </article>
            </div>
          </div>
        </div>
        <div class="af-ai-workbench__footer"><span>ChatModel</span><i></i><span>ImageModel</span><i></i><span>SpeechModel</span><i></i><span>VideoModel</span><strong>同一套 Java 接口</strong></div>
      </div>
    </div>
  </section>
  <section class="af-section">
    <div class="af-section__heading">
      <p class="af-kicker">Development Flow</p>
      <h2>一条更贴近工程实践的开发路径</h2>
    </div>
    <div class="af-flow" aria-label="Agents-Flex 开发路径">
      <div class="af-flow__item"><span>01</span><strong>接入模型</strong><p>按场景配置 ChatModel、ImageModel、语音模型或 VideoModel。</p></div>
      <div class="af-flow__item"><span>02</span><strong>暴露工具</strong><p>用注解或 Builder 将 Java 业务方法变成 Agent 可调用工具。</p></div>
      <div class="af-flow__item"><span>03</span><strong>接入知识</strong><p>组合 RAG、WebSearch、LLM Wiki，为回答提供外部上下文。</p></div>
      <div class="af-flow__item"><span>04</span><strong>编排 Agent</strong><p>用 ReAct、Routing、Subagent 处理多步骤和多角色任务。</p></div>
      <div class="af-flow__item"><span>05</span><strong>上线观测</strong><p>接入路由、重试、熔断和 OpenTelemetry，稳定运行。</p></div>
    </div>
  </section>
  <section class="af-section af-section--split">
    <div>
      <p class="af-kicker">Use Cases</p>
      <h2>适合这些 AI 应用场景</h2>
      <p class="af-section__text">Agents-Flex 更偏向“可集成、可扩展、可上线”的 Java 框架，而不是只能演示单轮对话的样例工程。</p>
    </div>
    <div class="af-usecases">
      <a href="/zh/samples/chat">智能客服与聊天助手</a>
      <a href="/zh/samples/rag">企业知识库与 RAG 问答</a>
      <a href="/zh/chat/text2sql">智能问数与数据分析</a>
      <a href="/zh/chat/mcp">MCP 工具连接与自动化</a>
      <a href="/zh/chat/skills">Skills 与 Sandbox 隔离执行</a>
      <a href="/zh/core/image">营销素材与创意图片生成</a>
      <a href="/zh/audio/tts-stt">语音助手与音频转写</a>
      <a href="/zh/video/video-generation">短视频与动态内容生产</a>
      <a href="/zh/chat/llm-wiki">层级文档导航与 LLM Wiki</a>
      <a href="/zh/intro/model-router">多模型网关与高可用路由</a>
    </div>
  </section>
  <section class="af-section af-section--code">
    <div class="af-code-copy">
      <p class="af-kicker">Quick Start</p>
      <h2>几行代码完成一次模型调用</h2>
      <p>Agents-Flex 不要求你重写现有应用结构。你可以先从一个 ChatModel 开始，再按业务需要接入图片、语音、视频、工具和知识库。</p>
      <div class="af-actions">
        <a class="af-button af-button--primary" href="/zh/chat/getting-started">查看快速开始</a>
        <a class="af-button" href="#multimodal-examples">浏览多模态示例</a>
      </div>
    </div>
    <pre class="af-code"><code>ChatModel model = OpenAIChatConfig.builder()&#10;    .endpoint("https://ai.gitee.com")&#10;    .provider("GiteeAI")&#10;    .model("Qwen3-32B")&#10;    .apiKey(System.getenv("GITEE_API_KEY"))&#10;    .buildModel();&#10;&#10;String answer = model.chat("介绍一下 Agents-Flex");&#10;System.out.println(answer);</code></pre>
  </section>
  <section id="multimodal-examples" class="af-section af-section--examples">
    <div class="af-section__heading">
      <p class="af-kicker">Multimodal Examples</p>
      <h2>用一致的 API 处理图片、语音与视频</h2>
      <p>以下代码展示每类能力的核心调用路径。服务商依赖、鉴权参数和完整异常处理请进入对应文档查看。</p>
    </div>
    <div class="af-example-grid">
      <article class="af-example">
        <div class="af-example__header">
          <div><span class="af-capability__tag">Image</span><h3>生成并保存图片</h3></div>
          <a class="af-example__link" href="/zh/core/image">图片文档</a>
        </div>
        <pre class="af-code af-code--example"><code>OpenAIImageModelConfig config = new OpenAIImageModelConfig();&#10;config.setApiKey(System.getenv("OPENAI_API_KEY"));&#10;ImageModel model = new OpenAIImageModel(config);&#10;&#10;GenerateImageRequest request = new GenerateImageRequest();&#10;request.setPrompt("雨后的未来城市，电影感光影");&#10;request.setSize(1024, 1024);&#10;&#10;ImageResponse response = model.generate(request);&#10;response.getImages().get(0)&#10;    .writeToFile(new File("output/city.png"));</code></pre>
      </article>
      <article class="af-example">
        <div class="af-example__header">
          <div><span class="af-capability__tag">TTS</span><h3>将文本合成为语音</h3></div>
          <a class="af-example__link" href="/zh/audio/getting-started">TTS 文档</a>
        </div>
        <pre class="af-code af-code--example"><code>AliyunTextToSpeechConfig config = new AliyunTextToSpeechConfig();&#10;config.setAppKey(System.getenv("ALIYUN_APP_KEY"));&#10;config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));&#10;config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));&#10;&#10;TextToSpeechModel model = new AliyunTextToSpeechModel(config);&#10;TextToSpeechRequest request = new TextToSpeechRequest(&#10;    "欢迎使用 Agents-Flex 多模态能力"&#10;);&#10;TextToSpeechResponse response = model.tts(request);&#10;response.writeTo(new File("output/reply.mp3"));</code></pre>
      </article>
      <article class="af-example">
        <div class="af-example__header">
          <div><span class="af-capability__tag">STT</span><h3>将音频转写为文本</h3></div>
          <a class="af-example__link" href="/zh/audio/tts-stt">STT 文档</a>
        </div>
        <pre class="af-code af-code--example"><code>AliyunSpeechToTextConfig config = new AliyunSpeechToTextConfig();&#10;config.setAppKey(System.getenv("ALIYUN_APP_KEY"));&#10;config.setAccessKeyId(System.getenv("ALIYUN_ACCESS_KEY_ID"));&#10;config.setAccessKeySecret(System.getenv("ALIYUN_ACCESS_KEY_SECRET"));&#10;&#10;SpeechToTextModel model = new AliyunSpeechToTextModel(config);&#10;SpeechToTextRequest request = new SpeechToTextRequest();&#10;request.setAudioFile(new File("meeting.mp3"));&#10;&#10;SpeechToTextResponse response = model.stt(request);&#10;System.out.println(response.getResult());</code></pre>
      </article>
      <article class="af-example">
        <div class="af-example__header">
          <div><span class="af-capability__tag">Video</span><h3>视频生成并保存到本地</h3></div>
          <a class="af-example__link" href="/zh/video/getting-started">视频文档</a>
        </div>
        <pre class="af-code af-code--example"><code>AliyunWanVideoModelConfig config = new AliyunWanVideoModelConfig();&#10;config.setApiKey(System.getenv("DASHSCOPE_API_KEY"));&#10;AliyunWanVideoModel model = new AliyunWanVideoModel(config);&#10;&#10;GenerateVideoRequest request = new GenerateVideoRequest();&#10;request.setPrompt("纸飞机飞过日出时的未来城市");&#10;request.setSize(1280, 720);&#10;request.setDuration(5);&#10;&#10;VideoResponse response = model.generateAndWait(request);&#10;response.getVideo().writeToFile(&#10;    new File("output/city.mp4")&#10;);</code></pre>
      </article>
    </div>
  </section>
  <section class="af-ecosystem-stage">
    <div class="af-ecosystem-stage__inner">
      <header class="af-swiss-header">
        <div class="af-swiss-count"><span>AF / MODULES</span><strong>17</strong></div>
        <div class="af-swiss-title"><p>JAVA AI ECOSYSTEM / 2026</p><h2>按需组合的<br />模块生态</h2></div>
        <p class="af-swiss-lead">从模型接入到隔离执行，每个模块保持清晰边界，并通过一致的 Java 接口组合成完整应用。</p>
      </header>
      <div class="af-swiss-grid" aria-label="Agents-Flex 可组合模块列表">
        <a class="af-swiss-module" href="/zh/chat/chat-model"><span><b>01</b> MODEL</span><strong>Chat</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/tool"><span><b>02</b> AGENT</span><strong>Tool</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/mcp"><span><b>03</b> PROTOCOL</span><strong>MCP</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/skills"><span><b>04</b> RUNTIME</span><strong>Skills</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/skills"><span><b>05</b> ISOLATE</span><strong>OpenSandbox</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/skills"><span><b>06</b> ISOLATE</span><strong>AIO Sandbox</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/subagent"><span><b>07</b> AGENT</span><strong>Subagent</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/text2sql"><span><b>08</b> DATA</span><strong>Text2SQL</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/websearch"><span><b>09</b> SEARCH</span><strong>WebSearch</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/chat/llm-wiki"><span><b>10</b> KNOWLEDGE</span><strong>LLM Wiki</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/rag/vector-store"><span><b>11</b> RAG</span><strong>Vector Store</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/models/embedding"><span><b>12</b> MODEL</span><strong>Embedding</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/models/rerank"><span><b>13</b> MODEL</span><strong>Rerank</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/core/image"><span><b>14</b> MEDIA</span><strong>Image</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/audio/tts-stt"><span><b>15</b> MEDIA</span><strong>TTS / STT</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/video/video-generation"><span><b>16</b> MEDIA</span><strong>Video</strong><i aria-hidden="true">→</i></a>
        <a class="af-swiss-module" href="/zh/observability/observability"><span><b>17</b> PRODUCTION</span><strong>Observability</strong><i aria-hidden="true">→</i></a>
      </div>
    </div>
  </section>
</main>
