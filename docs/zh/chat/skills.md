<div v-pre>

# Skills 开发与 Sandbox 部署指南

## 概述

Agents-Flex Skills 是一套基于文件系统的模块化能力扩展机制。一个 Skill 不只是一个 Prompt，
而是一份可以同时包含操作说明、脚本、参考资料和静态资源的可复用工作包。模型先根据元数据选择
合适的 Skill，再按需读取完整说明，并通过 Bash、Read、Write、Edit、Glob、Grep 等工具执行任务。

Skills 模块最重要的设计是 `SkillRuntime`：同一套 Skill 和同一组模型工具可以在本机、容器或远程
Sandbox 中执行。切换 Runtime 不需要修改 Skill 内容，也不需要让模型学习不同的文件 API。

### 为什么需要 Runtime

如果直接在宿主机执行 Skill 脚本，脚本会继承当前 Java 进程的权限，可能读取本机文件、访问内网、
执行系统命令或消耗大量 CPU 和内存。对于来自第三方的 Skill、用户可控输入或生产环境任务，这通常
不是可接受的安全边界。

`SkillRuntime` 把以下能力放到同一个执行边界内：

- Skill 目录准备和远程上传；
- Shell 命令执行、工作目录、环境变量和超时；
- 文本文件读取、写入和精确编辑；
- Glob 文件匹配和 Grep 内容搜索；
- PPTX、PDF、图片、压缩包等二进制产物下载；
- Runtime 或 Sandbox 生命周期管理。

## 模块组成

```text
agents-flex-skills
├── Skill / Skills                       Skill 模型与发现加载
├── SkillsTool                           Skill 工具和 Runtime 工具组装
├── attachment/
│   ├── FilePublishRequest                输入流和待发布文件信息
│   ├── FilePublisher                     应用自定义文件发布接口
│   └── PublishedFile                     URL、有效期和存储结果
├── runtime/
│   ├── SkillRuntime                     统一执行边界
│   ├── SkillExecutionRequest            命令、目录、超时和环境变量
│   ├── SkillExecutionResult             退出码、输出和超时状态
│   ├── SkillRuntimeFileSystem            文本、二进制、列表和下载 API
│   └── SkillRuntimeFiles                 远程上传过滤策略
├── local/
│   └── LocalSkillRuntime                宿主机实现
└── tools/
    ├── SkillRuntimeShellTools           Bash
    ├── SkillRuntimeFileTools            Read / Write / Edit
    ├── SkillRuntimeSearchTools          Glob / Grep
    └── SkillRuntimeFilePublishTools     PublishFile（可选）

agents-flex-skills-sandbox
├── agents-flex-skills-open-sandbox      OpenSandbox SDK 实现
└── agents-flex-skills-aio-sandbox       AIO Sandbox HTTP API 实现
```

| Maven 模块 | 用途 | 是否创建 Sandbox |
| --- | --- | --- |
| `agents-flex-skills` | 核心 API、工具和本地 Runtime | 否 |
| `agents-flex-skills-open-sandbox` | 连接 OpenSandbox Server，按需创建 Sandbox 实例 | 是 |
| `agents-flex-skills-aio-sandbox` | 连接已经运行的 AIO Sandbox 服务 | 否 |

## 一个 Skill 由什么组成

Skill 是一个目录，目录中必须包含 `SKILL.md`。其他目录是约定而不是强制要求，可以根据任务需要选择。

```text
.claude/skills/
└── pptx/
    ├── SKILL.md                 必需：元数据和完整操作说明
    ├── scripts/                 可选：可执行脚本和确定性程序
    │   └── create_report.py
    ├── references/              可选：规范、API 文档、领域知识
    │   └── style-guide.md
    ├── assets/                  可选：模板、图片、字体等输入资源
    │   └── template.pptx
    └── LICENSE                  可选：许可证和第三方声明
```

### SKILL.md

`SKILL.md` 由 YAML 风格的 front matter 和 Markdown 正文组成：

```markdown
---
name: pptx
description: Create and validate PowerPoint presentations. Use for PPT or PPTX tasks.
---

# PPTX Creation

1. 使用 scripts/create_report.py 生成 PPTX。
2. 输出必须放在 Skill 目录之外。
3. 重新打开文件并验证页数、元数据和文本。
```

当前解析器支持简单的单行 `key: value`，以及单引号或双引号包裹的值。它不是完整 YAML 解析器，
不要在 front matter 中使用嵌套对象、数组或块文本。

关键字段：

| 字段 | 是否必需 | 说明 |
| --- | --- | --- |
| `name` | 是 | 模型调用 Skill 时使用的名称，同一工具集合中应唯一 |
| `description` | 强烈建议 | 描述能力和触发场景，决定模型能否正确选择 Skill |
| 其他字段 | 否 | 会进入 Skill XML 元数据，可用于版本、许可证等信息 |

### scripts

`scripts/` 适合放确定性、可复用且不应完全交给模型临时编写的程序，例如：

- 生成 PDF、PPTX、Excel 或图片；
- 代码格式化、构建和测试；
- 数据清洗和格式转换；
- 产物结构与元数据验证。

脚本不会自动执行。`SKILL.md` 应说明运行命令、依赖、输入、输出和验收条件，模型再通过 Runtime 的
`Bash` 工具执行。

### references 与 assets

`references/` 放文本参考资料，模型可以用 Read/Grep 按需读取。`assets/` 放模板、图片和其他输入资源，
通常由脚本直接消费。大型参考资料不应全部复制进 `SKILL.md`，否则会失去渐进式披露的优势。

## 渐进式披露

Skills 的加载分为三个阶段：

```text
发现阶段
  Skills.loadDirectory() 只解析 SKILL.md
  SkillsTool 向模型暴露 name / description 等元数据
        ↓
选择阶段
  模型调用 Skill(command="pptx")
  工具返回 Runtime 名称、Skill 根目录和完整 SKILL.md 正文
        ↓
执行阶段
  模型按说明使用 Bash / Read / Write / Edit / Glob / Grep
  脚本和 assets 不进入 Prompt，只在 Runtime 内被访问
```

这种方式避免在会话开始时把所有脚本和参考资料塞入上下文。模型只知道“有哪些能力”，真正选中 Skill
后才知道“如何完成任务”。

## Runtime 执行流程

```text
宿主机 Skills 目录
        │ Skills.loadDirectory
        ▼
List<Skill>，basePath 是本机目录
        │ SkillRuntime.prepare(List<Skill>)
        ├──────── Local：直接返回路径副本
        └──────── Remote：上传目录并重写 basePath
                         │
                         ▼
模型获得 Runtime 内 Skill 路径
        │
        ├── Bash ────────────── SkillRuntime.execute
        ├── Read/Write/Edit ─── SkillRuntimeFileSystem
        ├── Glob/Grep ───────── listFiles + readText
        └── 产物下载 ────────── openInputStream / download
```

### prepare 是批量 API

`SkillRuntime.prepare(List<Skill>)` 一次接收当前配置的全部 Skills，不是只准备一个 Skill。实现必须：

1. 返回与输入数量相同的列表；
2. 保留输入顺序；
3. 不返回 `null` 元素；
4. 让返回 Skill 的 `basePath` 在 Runtime 内真实可访问；
5. 不直接修改调用方传入的 Skill 对象。

OpenSandbox 和 AIO Runtime 会在准备阶段主动上传本机 Skill 目录。上传发生在
`SkillsTool.build()` 或 `buildTools()` 期间，而不是等到模型第一次调用该 Skill 才开始。

### Runtime 对比

| 能力 | LocalSkillRuntime | OpenSandboxSkillRuntime | AioSandboxSkillRuntime |
| --- | --- | --- | --- |
| 命令位置 | 当前宿主机 | OpenSandbox 创建的容器 | 已运行的 AIO 容器/服务 |
| Skill 上传 | 不上传 | 自动上传 | 自动上传 |
| basePath | 本机绝对路径 | `/workspace/skills/...` | `/home/gem/workspace/skills/...` |
| 文件读写 | JDK NIO | OpenSandbox Files SDK | AIO File HTTP API |
| 二进制下载 | 本地文件流 | SDK `readStream` | `/v1/file/download` |
| close 行为 | 无常驻资源 | kill 并关闭 Sandbox | 只清本地缓存，不停止服务 |
| 隔离能力 | 无 | 取决于 OpenSandbox 配置 | 取决于 AIO Docker/部署配置 |

## 添加 Maven 依赖

### 本地 Runtime

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### OpenSandbox

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-open-sandbox</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块会传递依赖 `agents-flex-skills` 和 OpenSandbox Kotlin/Java SDK。OpenSandbox SDK 使用 Kotlin 与
OkHttp 4.x；如果应用的依赖管理强制降级 Kotlin 或 OkHttp，需要检查最终依赖树。

### AIO Sandbox

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-aio-sandbox</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

AIO 适配器直接调用 HTTP API，不要求额外安装官方 Python/TypeScript SDK。

## 快速开始：LocalSkillRuntime

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.local.LocalSkillRuntime;

import java.util.List;

try (LocalSkillRuntime runtime = new LocalSkillRuntime()) {
    List<Tool> tools = SkillsTool.builder()
        .addSkillsDirectory("/absolute/path/to/.claude/skills")
        .runtime(runtime)
        .buildTools();

    prompt.addTools(tools);
    // 把 prompt 交给支持 Tool Calling 的 ChatModel。
}
```

如果一个根目录中包含大量 Skill，可以按 `SKILL.md` front matter 的 `name` 只加载需要的部分：

```java
List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory(
        "/absolute/path/to/.claude/skills",
        "pdf",
        "xlsx",
        "pptx"
    )
    .runtime(runtime)
    .buildTools();
```

名称匹配区分大小写。指定的 Skill 不存在，或者目录中存在多个同名 Skill 时，Builder 会立即抛出
`IllegalArgumentException`。筛选发生在 `SkillRuntime.prepare()` 之前，因此未选中的 Skill 不会上传到远程
Runtime，也不会出现在模型可见的 Skill 列表中。不传 Skill 名称时，原有重载仍会加载目录中的全部 Skill。

`buildTools()` 返回完整工具组：

| 工具名 | 作用 | 主要限制 |
| --- | --- | --- |
| `Skill` | 按名称加载 Skill 正文 | 只能选择已发现的 Skill |
| `Bash` | 在 Runtime 内执行 Shell | 默认 120 秒，最大 600 秒，输出会截断 |
| `Read` | 读取 UTF-8 文件并显示行号 | 最大 4 MiB，默认 2000 行 |
| `Write` | 创建或覆盖 UTF-8 文件 | Runtime 负责创建父目录 |
| `Edit` | 精确字符串替换 | 默认只允许唯一匹配 |
| `Glob` | 按 glob 模式匹配文件 | 限制深度、文件数和结果数 |
| `Grep` | 用 Java 正则搜索内容 | 跳过大文件和常见构建目录 |
| `PublishFile` | 上传最终文件并返回用户可访问 URL | 仅配置 `FilePublisher` 后注册 |

Local Runtime 没有安全隔离。不要因为工具名是 `Bash` 就误以为命令在容器中执行。

## 为什么不要混用 Commons Tools

使用远程 Runtime 时，应注册：

```java
prompt.addTools(
    SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools()
);
```

不要同时注册指向宿主机的 `ShellTools`、`FileSystemTools`、`GlobTool` 或 `GrepTool`。这些 Commons
工具本身仍然适用于非 Skills 场景，但如果与远程 Skills 工具混在同一次会话中，模型可能绕过 Sandbox
读取或执行宿主机内容，破坏 Runtime 的安全边界。

## OpenSandbox 安装与配置

OpenSandbox 官方架构由 OpenSandbox Server 和执行 Runtime 组成。本地快速开始使用 Docker Engine
作为执行 Runtime：`opensandbox-server` 连接本机 Docker daemon，并按请求创建隔离容器。

> OpenSandbox 官方当前推荐通过 PyPI/uvx 运行 Server，再由 Server 管理 Docker 容器。不要把 AIO 的
> 单容器启动命令套用到 OpenSandbox。

官方文档：

- [OpenSandbox Quick Start](https://open-sandbox.ai/getting-started/)
- [OpenSandbox Installation](https://open-sandbox.ai/getting-started/installation)
- [OpenSandbox Configuration](https://open-sandbox.ai/getting-started/configuration)
- [OpenSandbox Kotlin/Java SDK](https://open-sandbox.ai/sdks/kotlin)

### 前置条件

- Docker Engine 20.10+；
- Python 3.10+；
- `uv`，推荐使用；也可以使用 `pip`；
- Linux、macOS，或 Windows WSL2。

先确认 Docker daemon 可用：

```bash
docker version
docker run --rm hello-world
```

### 使用 uvx 启动 OpenSandbox Server

```bash
# 生成使用 Docker Runtime 的配置文件
uvx opensandbox-server init-config ~/.sandbox.toml --example docker

# 启动 Server，默认读取 ~/.sandbox.toml
uvx opensandbox-server
```

也可以先安装再执行：

```bash
uv pip install opensandbox-server
opensandbox-server init-config ~/.sandbox.toml --example docker
opensandbox-server
```

指定其他配置路径：

```bash
export SANDBOX_CONFIG_PATH=/opt/opensandbox/sandbox.toml
opensandbox-server

# 或者
opensandbox-server --config /opt/opensandbox/sandbox.toml
```

### 验证服务

```bash
curl http://127.0.0.1:8080/health
# 预期：{"status":"healthy"}
```

服务启动后还可以访问：

- Swagger UI：`http://localhost:8080/docs`
- ReDoc：`http://localhost:8080/redoc`

### API Key

编辑 `~/.sandbox.toml` 的 `[server]` 配置，为 `api_key` 设置随机高强度密钥。配置 API Key 后，除
`/health`、`/docs` 和 `/redoc` 外的 API 都要求请求头：

```text
OPEN-SANDBOX-API-KEY: your-secret-api-key
```

验证鉴权：

```bash
curl \
  -H "OPEN-SANDBOX-API-KEY: your-secret-api-key" \
  http://localhost:8080/v1/sandboxes
```

生产环境必须启用 API Key。如果在 Docker/Kubernetes/CI 等非交互环境中明确选择无鉴权模式，
OpenSandbox 要求设置 `OPENSANDBOX_INSECURE_SERVER=YES` 来确认风险；该模式不适合暴露到公网。

### Java 配置

```java
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.opensandbox.OpenSandboxSkillRuntime;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;

import java.time.Duration;

ConnectionConfig connection = ConnectionConfig.builder()
    .domain("localhost:8080")
    .apiKey(System.getenv("OPEN_SANDBOX_API_KEY"))
    .build();

SkillRuntime runtime = OpenSandboxSkillRuntime.builder()
    .connectionConfig(connection)
    .image("python:3.11")
    .remoteRoot("/workspace/skills")
    .sandboxTimeout(Duration.ofMinutes(10))
    .readyTimeout(Duration.ofSeconds(30))
    .build();
```

完整使用时必须关闭 Runtime：

```java
try (SkillRuntime runtime = createOpenSandboxRuntime()) {
    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools());
    // 执行模型工具循环
} // 自动 kill 并关闭本次创建的 Sandbox
```

### OpenSandbox 配置项

| 配置 | 作用 |
| --- | --- |
| `connectionConfig` | Server 域名、协议与 API Key |
| `image` | 每个 Sandbox 使用的镜像，必须包含 Skill 所需运行时 |
| `remoteRoot` | Skill 上传根目录，必须是非根绝对路径 |
| `sandboxTimeout` | Sandbox 最长存活时间 |
| `readyTimeout` | 等待实例启动就绪的时间 |
| `resources` | 传递 CPU、内存等资源限制 |
| `environment` | 创建 Sandbox 时注入环境变量 |
| `networkPolicy` | 出站网络策略 |

镜像必须自行包含任务需要的程序，或允许任务安装依赖。例如 PPTX Demo 使用 `python3` 和
`python-pptx`。生产环境推荐构建固定版本的专用镜像，不要在每次任务中临时从公网安装依赖。

## AIO Sandbox Docker 安装与配置

AIO Sandbox 是 All-in-One Sandbox。官方镜像同时提供 Shell、文件、浏览器、Jupyter、VNC、终端和
Code Server 等服务。Agents-Flex 当前 Skills Runtime 使用其中的 Shell 与文件 API。

官方文档：

- [AIO Sandbox 快速开始](https://sandbox.agent-infra.com/zh/guide/start/quick-start)
- [Shell 终端](https://sandbox.agent-infra.com/zh/guide/basic/shell)
- [文件操作](https://sandbox.agent-infra.com/zh/guide/basic/file)
- [鉴权](https://sandbox.agent-infra.com/zh/guide/basic/authentication)
- [安全](https://sandbox.agent-infra.com/zh/guide/advanced/security)

### 前置条件

- 已安装并启动 Docker；
- 至少 2 GiB 可用内存；
- 宿主机 8080 端口可用，或选择其他本机端口。

### 官方镜像启动

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

中国大陆镜像：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:latest
```

生产环境应固定版本，避免 `latest` 在未验证的情况下变化。官方文档给出的版本格式示例：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:1.11.0
```

如果 8080 被占用：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:3000:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

此时 Java 的 `baseUrl` 应配置为 `http://localhost:3000`。

### 为什么绑定 127.0.0.1

`-p 127.0.0.1:8080:8080` 只允许宿主机访问。不要为了方便直接使用 `-p 8080:8080` 把未鉴权的
Sandbox 暴露到所有网卡。云主机部署应保持 8080 为私有端口，通过带 TLS、鉴权和访问控制的反向
代理或 Ingress 发布。

### 验证 AIO

```bash
docker ps --filter name=agentsflex-aio-sandbox
docker logs agentsflex-aio-sandbox
```

浏览器访问：

- 仪表板：`http://localhost:8080/index.html`
- OpenAPI：`http://localhost:8080/v1/docs`
- 终端：`http://localhost:8080/terminal`
- VNC：`http://localhost:8080/vnc/index.html?autoconnect=true`

### Java 配置

```java
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.aiosandbox.AioSandboxSkillRuntime;

import java.util.concurrent.TimeUnit;

SkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .remoteRoot("/home/gem/workspace/skills")
    .httpTimeoutMillis((int) TimeUnit.MINUTES.toMillis(11))
    .build();
```

`close()` 不会停止 AIO 容器。容器由部署系统或操作者管理：

```bash
docker stop agentsflex-aio-sandbox
```

### JWT 鉴权

AIO 支持使用 Base64 编码的 RSA 公钥启用 JWT 鉴权。生成密钥：

```bash
openssl genrsa -out private_key.pem 2048
openssl rsa -in private_key.pem -pubout -out public_key.pem

# macOS/Linux 的 base64 参数可能不同；关键是得到不带换行的 Base64 公钥。
export JWT_PUBLIC_KEY=$(base64 < public_key.pem | tr -d '\n')
```

把公钥作为容器环境变量传入：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -e JWT_PUBLIC_KEY="$JWT_PUBLIC_KEY" \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

业务服务使用私钥签发 RS256 JWT，然后配置到 Runtime：

```java
SkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .bearerToken(jwt)
    .build();
```

适配器会发送：

```text
Authorization: Bearer <JWT>
```

私钥只能保存在业务服务或密钥管理系统中，不能放进 Skill 目录、容器镜像或 Git 仓库。

## Skill 上传规则与安全边界

远程 Runtime 会上传每个已配置 Skill 的完整目录，而不只是 `SKILL.md`。默认过滤规则：

- 不进入任何 `.git` 目录；
- 不上传 `.env`；
- 不上传 `.env.*`；
- 不上传文件名以 `credentials` 开头的文件；
- 文件树遍历不会跟随符号链接；
- 尽可能保留脚本可执行权限。

这些规则只能拦截常见误操作。诸如 `secret.json`、`id_rsa`、云厂商配置文件或业务数据并不会因为名字
不同而自动识别。把 Skill 交给远程服务前，仍需审计整个目录。

推荐做法：

1. Skill 目录只存可公开或允许进入 Sandbox 的资源；
2. 密钥通过 Runtime 环境变量、凭据服务或短期令牌注入；
3. 为 Sandbox 配置最小出站网络策略；
4. 限制 CPU、内存、进程数、磁盘和执行时间；
5. 为不同用户或租户使用独立 Sandbox；
6. 记录 Skill 名称、版本、命令、退出码和产物，不记录密钥值；
7. 生产镜像固定 digest 或版本，并定期更新漏洞补丁。

## 文本读取与二进制产物下载

### 文本 API

```java
SkillRuntimeFileSystem files = runtime.getFileSystem();

String text = files.readText("/runtime/path/report.txt", 1024 * 1024);
files.writeText("/runtime/path/result.json", "{\"ok\":true}");
SkillFileInfo info = files.stat("/runtime/path/result.json");
List<SkillFileInfo> entries = files.listFiles("/runtime/path", 10, 1000);
```

### 读取小型二进制文件

```java
byte[] bytes = files.readBytes("/runtime/path/image.png", 5 * 1024 * 1024);
```

`maxBytes` 是强制内存上限。大型文件不要使用 `readBytes`。

### 下载到本地文件

```java
import java.nio.file.Path;
import java.nio.file.Paths;

Path localFile = files.download(
    "/runtime/output/report.pptx",
    Paths.get("output/report.pptx")
);

System.out.println(localFile.toAbsolutePath());
```

下载先写同目录 `.part` 临时文件，完成后再替换最终文件，避免失败时留下半个目标文件。

### 上传到第三方文件中心

`download(String, OutputStream)` 不会关闭调用方传入的目标流，因此可以直接接对象存储 SDK：

```java
try (OutputStream objectStorage = objectStorageClient.openUpload(
        "reports/runtime-report.pptx")) {
    files.download("/runtime/output/report.pptx", objectStorage);
}
```

也可以直接读取流：

```java
try (InputStream input = files.openInputStream("/runtime/output/report.pptx")) {
    thirdPartyFileCenter.upload("runtime-report.pptx", input);
}
```

调用方必须关闭 `openInputStream` 返回的流。对于远程 Runtime，关闭动作还会释放 HTTP 连接。

## 让 AI 主动发布文件 URL

业务代码主动调用 `download()` 适合固定工作流。如果希望模型在生成并验证文件后主动完成交付，可以
配置 `FilePublisher`。配置后，`SkillsTool.buildTools()` 会增加一个 `PublishFile` Tool；
未配置时不会暴露该工具。

```java
import com.agentsflex.skill.file.FilePublishRequest;
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;

FilePublisher publisher = new FilePublisher() {
    @Override
    public PublishedFile publish(FilePublishRequest request) {
        String objectKey = attachmentCenter.upload(
                request.getFileName(),
                request.getContentType(),
                request.getContentLength(),
                request.getInputStream()
        );

        return PublishedFile.builder()
                .url(attachmentCenter.createDownloadUrl(objectKey))
                .storageKey(objectKey)
                .fileName(request.getFileName())
                .contentType(request.getContentType())
                .contentLength(request.getContentLength())
                .expiresAt(System.currentTimeMillis() + 60 * 60_000L)
                .build();
    }
};

prompt.

addTools(SkillsTool.builder()
    .

addSkillsDirectory(skillsDirectory)
    .

runtime(runtime)
    .

filePublisher(publisher)
    .

buildTools());
```

模型完成文件后可以调用：

```json
{
  "filePath": "/home/gem/workspace/skills/output/report.pptx",
  "fileName": "runtime-report.pptx",
  "contentType": "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}
```

Tool 会先通过 Runtime 文件系统确认路径存在且不是目录，再打开二进制流并构造
`FilePublishRequest`。请求包含：

| 字段 | 说明 |
| --- | --- |
| `inputStream` | Runtime 文件流，只能同步消费一次 |
| `fileName` | 用户看到的文件名，Tool 会去掉目录部分 |
| `contentType` | 模型指定或根据扩展名推断的 MIME 类型 |
| `contentLength` | Runtime 报告的文件大小，未知时可以是 `-1` |
| `sourcePath` | Runtime 内原始路径，用于审计和策略判断 |
| `runtimeName` | `local`、`open-sandbox`、`aio-sandbox` 或自定义名称 |
| `checksum` | 可选内容校验值 |
| `metadata` | 租户、会话和业务分类等扩展信息 |

流的生命周期约定：

1. Publisher 必须在 `publish()` 返回前完整消费 InputStream；
2. Publisher 不得关闭 InputStream；
3. Publisher 不得缓存该流或交给异步线程继续读取；
4. Tool 在 `publish()` 返回或抛出异常后自动关闭流；
5. 异步上传实现应先同步落盘或改用独立的可重复打开内容源，而不是持有当前流。

`FilePublisher` 也是最终的发布安全边界。应用实现至少应根据 `sourcePath`、租户和文件信息限制
允许发布的目录、类型与大小；重新生成对象存储 Key；设置 URL 有效期；执行病毒或恶意文件扫描；
不得直接信任模型提供的文件名。对于本机发布，实现可以先把流保存到受控静态目录，再根据应用配置的
HTTP `baseUrl` 返回地址，但不应返回只有宿主机能够访问的 `file://` URL。

## SkillsDemoMain：远程生成 PPTX 并下载

仓库中的 `demos/skills-demo` 演示同一套 `pptx` Skill 在 Local、OpenSandbox 和 AIO Sandbox 中执行。
默认任务会：

1. 发现并批量准备 Skills；
2. 在选定 Runtime 中运行 PPTX 生成器；
3. 生成 5 页 16:9 PowerPoint；
4. 验证页数、元数据、文本和文件大小；
5. 让模型继续使用 Runtime 工具完成验收；
6. 将远程 PPTX 下载到本机 `target/skills-demo-output`。

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
| `SKILLS_RUNTIME` | `local` | `local`、`open-sandbox` 或 `aio-sandbox` |
| `SKILLS_DIR` | classpath `.claude/skills` | 本机 Skills 根目录 |
| `SKILLS_PROMPT` | PPTX 验收任务 | 自定义问题，可用 `${outputFile}` |
| `SKILLS_OUTPUT_FILE` | Runtime 对应路径 | Runtime 内产物路径 |
| `SKILLS_LOCAL_OUTPUT_FILE` | `target/skills-demo-output/<文件名>` | 下载后的本机路径 |
| `SKILLS_DOWNLOAD_ENABLED` | `true` | 是否自动回收远程产物 |
| `SKILLS_GENERATION_TIMEOUT_SECONDS` | `300` | PPTX 生成命令超时 |
| `SKILLS_DEMO_TIMEOUT_SECONDS` | `900` | 整个模型工具会话超时 |
| `OPEN_SANDBOX_REMOTE_ROOT` | `/workspace/skills` | OpenSandbox 上传根目录 |
| `OPEN_SANDBOX_TIMEOUT_SECONDS` | `600` | OpenSandbox 生命周期 |
| `OPEN_SANDBOX_READY_TIMEOUT_SECONDS` | `30` | OpenSandbox 启动等待时间 |
| `AIO_SANDBOX_REMOTE_ROOT` | `/home/gem/workspace/skills` | AIO 上传根目录 |
| `AIO_SANDBOX_HTTP_TIMEOUT_SECONDS` | `660` | AIO HTTP 读取超时 |

指定本地下载位置：

```bash
export SKILLS_LOCAL_OUTPUT_FILE="$PWD/output/runtime-report.pptx"
```

自定义任务只有显式配置 `SKILLS_OUTPUT_FILE` 时才会自动下载，因为 Demo 无法从任意模型回答中可靠推断
哪个文件是最终产物。

## 自定义 SkillRuntime

接入其他 Sandbox 时实现 `SkillRuntime` 和 `SkillRuntimeFileSystem`：

```java
public final class MySandboxRuntime implements SkillRuntime {
    @Override
    public String getName() {
        return "my-sandbox";
    }

    @Override
    public List<Skill> prepare(List<Skill> skills) {
        // 1. 上传每个 Skill 目录
        // 2. 返回数量和顺序一致、basePath 已改为远端路径的新列表
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return "/workspace/skills";
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        // 映射 command、workingDirectory、environment 和 timeoutMillis
    }

    @Override
    public void close() {
        // 只释放当前 Runtime 真正拥有的资源
    }
}
```

实现检查清单：

- `prepare` 支持多个 Skill，并保持数量和顺序；
- 同一个 Skill 在同一 Runtime 内重复准备时应幂等；
- 远程路径必须使用目标系统格式；
- Shell 参数和路径必须正确转义，不能拼接未验证输入；
- 超时后主动终止远端命令；
- stdout/stderr 和退出码映射清晰；
- 文本读取有大小上限；
- 二进制下载使用流，不把大型文件全部加载到内存；
- 下载失败不留下伪装成完整文件的本地产物；
- `close` 幂等，并明确是否拥有远端实例生命周期；
- 上传默认排除敏感文件且不跟随符号链接；
- 为网络失败、404、非零退出码、超时和二进制文件编写测试。

## 故障排查

### Skill not found

检查：

1. 根目录是否真实存在；
2. 文件名是否严格为 `SKILL.md`；
3. front matter 是否包含 `name`；
4. 模型调用名称是否与 `name` 完全一致；
5. `SkillsTool` 是否添加了正确目录。

### 远程路径仍然是本机路径

这通常说明只加载了 `Skills.loadDirectory()`，但没有使用远程 Runtime 执行
`prepare(List<Skill>)`。推荐统一通过 `SkillsTool.builder().runtime(runtime).buildTools()` 构建工具。

### OpenSandbox 无法连接

```bash
curl http://127.0.0.1:8080/health
```

然后检查：

- `OPEN_SANDBOX_DOMAIN` 是否只包含 SDK 需要的 domain/port；
- API Key 是否与 Server 配置一致；
- Docker daemon 是否运行；
- Sandbox 镜像能否拉取；
- `readyTimeout` 是否过短；
- Server 日志是否显示资源或网络策略错误。

### AIO 返回 Connection refused

```bash
docker ps --filter name=agentsflex-aio-sandbox
docker logs agentsflex-aio-sandbox
curl -I http://127.0.0.1:8080/v1/docs
```

如果映射到了 3000，`AIO_SANDBOX_BASE_URL` 也必须使用 3000。

### AIO 返回 401

- 服务启用了 `JWT_PUBLIC_KEY`，但 Runtime 没配置 `bearerToken`；
- JWT 不是 RS256 或签名私钥不匹配；
- JWT 已过期；
- Token 字符串错误地包含了 `Bearer ` 前缀，Runtime 会自动添加该前缀。

### 命令成功但找不到产物

- 模型输出路径和下载路径是否完全一致；
- 是否把产物错误地写进了只读 Skill 目录；
- `SKILLS_OUTPUT_FILE` 是否是 Runtime 内绝对路径；
- 自定义 Prompt 是否设置了 `SKILLS_OUTPUT_FILE`；
- 生成程序是否在非零退出前留下了不完整文件。

### 依赖安装失败

Sandbox 可能没有公网访问权限，或者网络策略禁止访问包仓库。生产环境推荐把 Python、Node、LibreOffice、
字体和所需库预装进固定镜像，而不是运行时执行 `pip install` 或 `npm install`。

### Maven exec:java 完成后仍不退出

如果业务输出和文件下载都已完成，但 Maven 仍等待，检查模型 SDK、OkHttp 或 OpenTelemetry 是否留下
非 daemon 后台线程。应用服务应统一管理这些客户端生命周期；Demo 调试时也可以先关闭可观测导出。

## 生产环境建议

1. 默认选择远程 Sandbox，只有可信内部 Skill 才允许 Local Runtime；
2. 为每个任务或租户建立清晰的 Sandbox 生命周期；
3. 固定镜像版本和依赖版本，禁止未经验证的 `latest` 自动升级；
4. 使用非 root 用户、只读基础文件系统和最小 Linux capabilities；
5. 配置 CPU、内存、PID、磁盘和执行时间限制；
6. 默认拒绝出站网络，只开放任务需要的域名；
7. Server 端口只放在私网，通过 TLS 网关暴露；
8. OpenSandbox 启用 API Key，AIO 启用 JWT；
9. 密钥使用短期令牌或凭据系统注入，不写入 Skill；
10. 对上传内容、执行命令、退出码、耗时和下载产物建立审计日志；
11. 对产物进行类型、大小、恶意内容和压缩炸弹检查后再交付用户；
12. 定期清理过期 Sandbox、上传目录、临时文件和本地下载缓存。

## 相关资源

- [Skills Demo README](https://github.com/agents-flex/agents-flex/tree/main/demos/skills-demo)
- [OpenSandbox 官方文档](https://open-sandbox.ai/getting-started/)
- [AIO Sandbox 官方文档](https://sandbox.agent-infra.com/zh/guide/start/quick-start)
- [Tool 工具调用](./tool)
- [Tool 构建](./tool-build)

</div>
