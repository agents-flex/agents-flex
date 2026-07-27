<div v-pre>

# Skills 完整示例

## SkillsDemoMain：远程生成 PPTX 并下载

仓库中的 `demos/skills-demo` 演示同一套 `pptx` Skill 在 Local、OpenSandbox 和 AIO Sandbox 中执行。
默认任务会：

1. 发现 Skills，并只准备默认任务需要的 `pptx` Skill；
2. 在选定 Runtime 中运行 PPTX 生成器；
3. 生成 5 页 16:9 PowerPoint；
4. 验证页数、元数据、文本和文件大小；
5. 让模型继续使用 Runtime 工具完成验收；
6. 将远程 PPTX 下载到本机 `target/skills-demo-output`。

默认任务为了确定性生成产物，会在调用模型前主动准备 `pptx`。自定义问题和
`SkillsConsoleDemoMain` 使用正常的按需路径：构建工具不会创建远程 Sandbox，只有模型首次调用 `skill`、
`bash` 或文件工具时才会使用 Runtime；其中 `skill` 调用还会按名称准备对应目录。

先构建：

```bash
mvn -pl demos/skills-demo -am install -DskipTests
export GITEE_APIKEY="your-gitee-ai-key"
```

### Local

```bash
SKILLS_RUNTIME=local \
mvn -f demos/skills-demo/pom.xml exec:java
```

### OpenSandbox

```bash
export SKILLS_RUNTIME=open-sandbox
export OPEN_SANDBOX_DOMAIN="localhost:8080"
export OPEN_SANDBOX_API_KEY="your-open-sandbox-api-key"
export OPEN_SANDBOX_IMAGE="python:3.11"

mvn -f demos/skills-demo/pom.xml exec:java
```

### AIO Sandbox

```bash
export SKILLS_RUNTIME=aio-sandbox
export AIO_SANDBOX_BASE_URL="http://localhost:8080"

# 仅当 AIO 启用了 JWT 时设置
export AIO_SANDBOX_TOKEN="your-jwt"

mvn -f demos/skills-demo/pom.xml exec:java
```

### Demo 环境变量

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GITEE_APIKEY` | 无，必填 | GiteeAI API Key |
| `GITEE_PROVIDER` | `GiteeAI` | 模型 Provider 名称 |
| `GITEE_ENDPOINT` | `https://ai.gitee.com` | OpenAI-compatible 服务地址 |
| `GITEE_REQUEST_PATH` | `/v1/chat/completions` | Chat Completions 请求路径 |
| `GITEE_MODEL` | `Qwen3.5-35B-A3B` | 对话模型 |
| `CHAT_LOG_ENABLED` | `false` | 是否输出模型请求日志 |
| `SKILLS_RUNTIME` | `local` | `local`、`open-sandbox` 或 `aio-sandbox` |
| `SKILLS_DIR` | classpath `.claude/skills` | 本机 Skills 根目录 |
| `SKILLS_CONVERSATION_ID` | 未配置 | 为任意 Runtime 启用稳定的会话工作目录 |
| `SKILLS_LOCAL_CONVERSATIONS_ROOT` | `target/skills-runtime/conversations` | Local 会话工作目录的父目录 |
| `SKILLS_PROMPT` | PPTX 验收任务 | 自定义问题，可用 `${outputFile}` |
| `SKILLS_OUTPUT_FILE` | Runtime 对应路径 | Runtime 内产物路径 |
| `SKILLS_LOCAL_OUTPUT_FILE` | `target/skills-demo-output/<文件名>` | 下载后的本机路径 |
| `SKILLS_DOWNLOAD_ENABLED` | `true` | 是否自动回收远程产物 |
| `SKILLS_GENERATION_TIMEOUT_SECONDS` | `300` | PPTX 生成命令超时 |
| `SKILLS_DEMO_TIMEOUT_SECONDS` | `900` | 整个模型工具会话超时 |
| `OPEN_SANDBOX_DOMAIN` | 无，OpenSandbox 必填 | SDK 使用的 OpenSandbox domain/port |
| `OPEN_SANDBOX_API_KEY` | 无，OpenSandbox 必填 | OpenSandbox API Key |
| `OPEN_SANDBOX_IMAGE` | `python:3.11` | OpenSandbox 镜像 |
| `OPEN_SANDBOX_REMOTE_ROOT` | `/workspace/skills` | OpenSandbox 上传根目录 |
| `OPEN_SANDBOX_CONVERSATIONS_ROOT` | `/workspace/conversations` | OpenSandbox 会话工作目录的父目录 |
| `OPEN_SANDBOX_TIMEOUT_SECONDS` | `600` | OpenSandbox 生命周期 |
| `OPEN_SANDBOX_READY_TIMEOUT_SECONDS` | `30` | OpenSandbox 启动等待时间 |
| `AIO_SANDBOX_BASE_URL` | `http://localhost:8080` | AIO 服务根地址 |
| `AIO_SANDBOX_TOKEN` | 未配置 | AIO 启用 JWT 时使用的 Token |
| `AIO_SANDBOX_REMOTE_ROOT` | `/home/gem/workspace/skills` | AIO 上传根目录 |
| `AIO_SANDBOX_CONVERSATIONS_ROOT` | `/home/gem/workspace/conversations` | AIO 会话工作目录的父目录 |
| `AIO_SANDBOX_HTTP_TIMEOUT_SECONDS` | `660` | AIO HTTP 读取超时 |

指定本地下载位置：

```bash
export SKILLS_LOCAL_OUTPUT_FILE="$PWD/output/runtime-report.pptx"
```

自定义任务只有显式配置 `SKILLS_OUTPUT_FILE` 时才会自动下载，因为 Demo 无法从任意模型回答中可靠推断
哪个文件是最终产物。

未配置会话 ID 的 Local 模式默认直接生成到 `demos/skills-demo/target/skills-demo-output`。配置会话 ID 后，
默认产物改为 Local 会话目录下的 `output/`，不会再额外复制到 `SKILLS_LOCAL_OUTPUT_FILE`；该变量只用于
OpenSandbox/AIO 远端产物回收。

</div>
