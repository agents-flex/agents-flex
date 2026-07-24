# Skills Runtime Demo

这个 Demo 展示如何让同一套 Skills 在不同 runtime 中执行：

- `local`：在当前主机执行，默认选项，便于开发调试。
- `open-sandbox`：创建 OpenSandbox 实例并上传 Skill 资源后执行。
- `aio-sandbox`：连接已经运行的 AIO Sandbox 服务执行。

Demo 只注册 `SkillsTool.buildTools()` 返回的 `Skill`、`Bash`、`Read`、`Write`、`Edit`、`Glob` 和
`Grep`。这些工具全部由所选 runtime 驱动；远端模式不会注册任何指向宿主机的文件、搜索或 Shell
工具，因此模型无法绕过所选 runtime 在宿主机执行命令或读写文件。

## 准备

需要 JDK 8+、Maven，以及 GiteeAI API Key：

```bash
export GITEE_APIKEY="your-api-key"
mvn -pl demos/skills-demo -am install -DskipTests
```

默认使用 `Qwen3.5-35B-A3B`。可以通过 `GITEE_MODEL`、`GITEE_ENDPOINT`、`GITEE_PROVIDER` 和
`GITEE_REQUEST_PATH` 调整模型配置。

## 本机 Runtime

```bash
SKILLS_RUNTIME=local \
SKILLS_CONVERSATION_ID=conversation-123 \
mvn -f demos/skills-demo/pom.xml exec:java
```

这会运行默认的 PPTX 验收示例：生成一份五页的 Skills Runtime 报告，并检查页数、元数据、文本内容和
文件大小。本机默认输出到 `target/skills-demo-output/agentsflex-skills-runtime-report.pptx`。
配置 `SKILLS_CONVERSATION_ID` 后，Skill 会先复制到对应会话目录的 `skills` 子目录，产物和 Skill
运行时自行创建的文件都保留在该会话中。
也可以通过命令参数或 `SKILLS_PROMPT` 传入问题：

```bash
mvn -f demos/skills-demo/pom.xml exec:java \
  -Dexec.args="请使用 ai-tutor skill 给初学者解释什么是 Transformer"
```

## OpenSandbox

先根据 [OpenSandbox 文档](https://open-sandbox.ai/sdks/kotlin)准备服务端，然后配置：

```bash
export SKILLS_RUNTIME=open-sandbox
export OPEN_SANDBOX_DOMAIN="your-open-sandbox-domain"
export OPEN_SANDBOX_API_KEY="your-open-sandbox-api-key"
export OPEN_SANDBOX_IMAGE="python:3.11"

mvn -f demos/skills-demo/pom.xml exec:java
```

可选配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OPEN_SANDBOX_REMOTE_ROOT` | `/workspace/skills` | Skill 上传目录 |
| `OPEN_SANDBOX_CONVERSATIONS_ROOT` | `/workspace/conversations` | 会话工作目录的父目录 |
| `OPEN_SANDBOX_TIMEOUT_SECONDS` | `600` | 沙箱生命周期 |
| `OPEN_SANDBOX_READY_TIMEOUT_SECONDS` | `30` | 沙箱启动等待时间 |

未配置 `SKILLS_CONVERSATION_ID` 时，程序结束会关闭并销毁本次创建的 OpenSandbox 实例。配置会话 ID 后，
同一 JVM 内后续创建的 Runtime 会通过 Store 中的 `sandboxId` 复用该会话的 Sandbox，普通 `close()` 不会销毁它；共享 Sandbox 最迟会按
`OPEN_SANDBOX_TIMEOUT_SECONDS` 到期。Demo 使用默认内存 Store；生产环境跨 JVM 或应用重启恢复时，应配置
`JdbcOpenSandboxConversationStore` 或其他持久化 `OpenSandboxConversationStore` 实现。

## AIO Sandbox

根据 [AIO Sandbox 快速开始](https://sandbox.agent-infra.com/zh/guide/start/quick-start)启动服务。Demo
连接已有服务，不负责启动或停止容器：

```bash
export SKILLS_RUNTIME=aio-sandbox
export AIO_SANDBOX_BASE_URL="http://localhost:8080"
export SKILLS_CONVERSATION_ID="conversation-123"
# 服务启用 JWT 鉴权时设置：
export AIO_SANDBOX_TOKEN="your-jwt-token"

mvn -f demos/skills-demo/pom.xml exec:java
```

可选配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AIO_SANDBOX_REMOTE_ROOT` | `/home/gem/workspace/skills` | Skill 上传目录 |
| `AIO_SANDBOX_CONVERSATIONS_ROOT` | `/home/gem/workspace/conversations` | 会话工作目录的父目录 |
| `AIO_SANDBOX_HTTP_TIMEOUT_SECONDS` | `660` | 单次 HTTP 请求超时 |

## 通用配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SKILLS_DIR` | classpath 中的 `.claude/skills` | 自定义 Skills 文件系统目录 |
| `SKILLS_CONVERSATION_ID` | 未配置 | 为任意 Runtime 启用稳定的会话工作目录 |
| `SKILLS_LOCAL_CONVERSATIONS_ROOT` | `target/skills-runtime/conversations` | Local 会话工作目录的父目录 |
| `SKILLS_PROMPT` | PPTX 验收示例 | 未传命令参数时的用户问题，可用 `${outputFile}` 引用输出路径 |
| `SKILLS_OUTPUT_FILE` | runtime 对应的默认路径 | runtime 内的 PPTX 输出路径 |
| `SKILLS_LOCAL_OUTPUT_FILE` | `target/skills-demo-output/<文件名>` | 远端产物下载到本机的路径 |
| `SKILLS_DOWNLOAD_ENABLED` | `true` | OpenSandbox/AIO 是否自动下载远端产物 |
| `SKILLS_GENERATION_TIMEOUT_SECONDS` | `300` | 默认 PPTX 生成命令超时 |
| `SKILLS_DEMO_TIMEOUT_SECONDS` | `900` | 整个工具调用会话的等待时间 |
| `CHAT_LOG_ENABLED` | `false` | 是否打印模型请求日志 |

远端 runtime 会在构建工具时上传全部已配置的 Skill 目录，但会忽略 `.git`、`.env`、`.env.*` 和 `credentials*` 等敏感文件，
且不会跟随符号链接。默认 PPTX 任务完成后，OpenSandbox/AIO 产物会自动下载到本机；下载失败或远端
文件不存在时 Demo 会以失败结束。自定义任务需要设置 `SKILLS_OUTPUT_FILE` 才会启用相同的产物回收流程。
