<div v-pre>

# Skill Runtime

## 概述

一个 Skill 不只有 Markdown 说明，还可能读取文件、运行 Python 或 Shell 脚本、安装依赖，并生成 PDF、
PPTX、图片等文件。应用把 Skill 交给模型之前，需要先决定：**这些操作在哪里执行，以及能够访问哪些资源？**

`SkillRuntime` 是 Agents-Flex 提供的统一执行边界。它可以代表当前 Java 应用所在的宿主机、按任务创建的
临时 Sandbox，或者一个已经部署好的远程 Sandbox 服务。切换 Runtime 时，Skill 目录和模型使用的
`bash`、`read`、`write`、`glob` 等工具不需要随之改写。

Runtime 主要负责四件事：

| 能力 | 作用 |
| --- | --- |
| `prepare()` | 把 Skill 复制或上传到执行环境，并返回 Runtime 内可访问的路径 |
| `execute()` | 在 Runtime 内按工作目录、环境变量和超时执行命令 |
| `getFileSystem()` | 在同一执行边界内读写、搜索和下载文件 |
| `close()` | 释放 Runtime 拥有的连接、Sandbox 或本地状态 |

Runtime 管理的是 **Skill 的执行环境**，不负责保存 Skill 历史版本。ZIP 的安装、共享和版本物化由
[Skill Artifact Store](./artifact-store) 负责；Skill 执行后生成的文件如何交付给用户，则由
[Skill 产物](./files) 中的文件 API 和 `FilePublisher` 负责。

```text
Skill 目录或 Artifact
        ↓
SkillsTool 发现并选择 Skill
        ↓
SkillRuntime.prepare() 复制或上传到执行环境
        ↓
模型使用 skill / bash / read / write / glob 等工具
        ↓
SkillRuntime.execute() + SkillRuntimeFileSystem
        ↓
下载或发布最终产物
```

## 适用场景

### 本地开发和 Skill 调试

开发者正在编写一个报表 Skill，需要频繁修改 `SKILL.md` 和脚本，并直接查看日志。使用
`LocalSkillRuntime` 可以在当前机器上快速运行，不需要先部署 Sandbox 服务。

Local Runtime 不提供安全隔离。Skill 脚本拥有当前 Java 进程用户的文件和网络权限，因此只适用于可信内容。

### 生产环境执行第三方 Skill

平台允许用户或其他团队上传 Skill，脚本内容不能被完全信任。使用 `OpenSandboxSkillRuntime` 可以为任务或
会话创建独立容器，再配置非 root 用户、CPU、内存、超时和出站网络策略。应用节点不需要直接执行 Skill 命令。

### 连接已有的共享 Sandbox 服务

团队已经部署 AIO Sandbox，并由 Kubernetes 或其他系统统一维护容器。使用 `AioSandboxSkillRuntime` 可以
通过 HTTP 调用已有服务的 Shell 和文件 API；Runtime 关闭时不会停止该服务。

### 多轮对话持续编辑同一份文件

用户先让 Agent 生成 PPTX，下一轮又要求修改其中两页。为 Runtime 配置稳定的 `conversationId` 后，两轮
请求会使用同一个会话 Workspace，后一次可以继续访问前一次生成的文件。

### 多节点恢复远程会话

应用部署在多个 JVM 上，同一会话的后续请求可能被路由到不同节点。OpenSandbox Runtime 配合 JDBC
Conversation Store，可以持久化 `sandboxId` 和已准备的 Skill 映射，让其他节点重新连接同一个 Sandbox。

### 只需要文本提示时

如果 Skill 只提供操作说明，不运行脚本、不访问文件，也可以只使用 `skill` 的说明能力。但
`SkillsTool.buildTools()` 默认仍会提供完整 Runtime 工具组；应用应根据实际信任边界选择 Runtime，而不能因为
当前 Skill 看起来简单就忽略未来可能加入的脚本和资源。

## 快速开始：Local Runtime

先用 Local Runtime 跑通最短链路，再根据部署环境切换到远程实现。

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 准备 Skill 目录

```text
/opt/my-app/skills/
└── report-generator/
    ├── SKILL.md
    ├── scripts/
    │   └── create_report.py
    └── assets/
        └── template.pptx
```

Skill 目录格式详见 [Skill 目录与开发](./skill-package)。

### 3. 创建 Runtime 并注册工具

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.local.LocalSkillRuntime;

import java.util.List;

try (LocalSkillRuntime runtime = new LocalSkillRuntime()) {
    List<Tool> tools = SkillsTool.builder()
        .addSkillsDirectory("/opt/my-app/skills", "report-generator")
        .runtime(runtime)
        .buildTools();

    prompt.addTools(tools);
    // 把 prompt 交给支持 Tool Calling 的 ChatModel。
}
```

`buildTools()` 会在返回前完成以下工作：

1. 在目录中发现并解析 `SKILL.md`；
2. 调用 `runtime.prepare()`；
3. 把准备后的 Runtime 路径写入 Skill；
4. 注册 `skill`、`bash`、文件和搜索工具。

因此模型看到的 Skill 路径一定属于当前 Runtime。远程 Runtime 也会在 `buildTools()` 时上传 Skill，而不是
等模型第一次选择它时才上传。

### 4. 推荐：为会话配置 Workspace

无参 Local Runtime 直接使用原始 Skill 目录，并允许文件 API 访问 Java 进程能够访问的本机路径。应用集成时，
通常建议为每个会话配置独立 Workspace：

```java
try (LocalSkillRuntime runtime = LocalSkillRuntime.builder()
    .conversationId(conversationId)
    .conversationsRoot("/var/lib/my-app/skill-conversations")
    .build()) {

    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory("/opt/my-app/skills", "report-generator")
        .runtime(runtime)
        .buildTools());
}
```

Runtime 会把 Skill 复制到下面的目录，再在副本中运行脚本：

```text
/var/lib/my-app/skill-conversations/
└── <conversationId>/
    ├── skills/
    │   └── report-generator-<source-hash>/
    └── output/                              任务按需创建
```

这样可以避免 bootstrap 和 Skill 脚本修改原始目录，并让相同会话继续访问先前文件。但它只提供路径约束，
Local Shell 仍然能够访问宿主机上的其他绝对路径，不能把 Workspace 当作安全 Sandbox。

## 选择 Runtime

Agents-Flex 当前提供三种内置实现：

| 能力 | LocalSkillRuntime | OpenSandboxSkillRuntime | AioSandboxSkillRuntime |
| --- | --- | --- | --- |
| 命令执行位置 | 当前 Java 宿主机 | OpenSandbox 创建的容器 | 已运行的 AIO 服务 |
| Skill 准备 | 直接使用，或复制到会话目录 | 自动上传 | 自动上传 |
| 文件访问 | JDK NIO | OpenSandbox Files SDK | AIO File HTTP API |
| Sandbox 生命周期 | 不创建 Sandbox | Runtime 创建或恢复 | 由外部部署系统管理 |
| 会话跨 JVM 恢复 | 依赖共享文件系统和业务状态 | 支持 JDBC Conversation Store | 依赖同一 AIO 服务和持久目录 |
| `close()` | 清理对象状态，不删除文件 | 非会话模式销毁 Sandbox；会话模式保留 | 清理对象状态，不停止服务 |
| 典型用途 | 开发、测试、可信内部任务 | 不可信任务、按会话隔离 | 已有固定 Sandbox 服务 |

选择时可以遵循下面的顺序：

1. Skill 或任务输入不可信时，优先选择独立 Sandbox，不使用 Local Runtime。
2. 需要按任务创建、限制和销毁容器时，选择 OpenSandbox。
3. 已有长期运行的 AIO 服务，并接受其共享与隔离模型时，选择 AIO Sandbox。
4. 只在开发机或受控 CI 中运行可信 Skill 时，选择 Local Runtime。

Runtime 抽象统一了工具调用，但不会抹平底层隔离差异。最终的文件权限、网络访问、CPU、内存和进程限制仍由
宿主机、容器或 Sandbox 平台决定。

## 切换到远程 Runtime

### OpenSandbox

OpenSandbox 适合让应用按任务或会话创建隔离容器。添加依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-open-sandbox</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

在 OpenSandbox Server 已启动的前提下，替换 Runtime 创建代码即可：

```java
import com.agentsflex.skill.runtime.opensandbox.OpenSandboxSkillRuntime;

import java.time.Duration;

OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
    .connectionConfig(connection -> connection
        .domain("localhost:8080")
        .apiKey(System.getenv("OPEN_SANDBOX_API_KEY")))
    .image("python:3.11")
    .remoteRoot("/workspace/skills")
    .sandboxTimeout(Duration.ofMinutes(10))
    .readyTimeout(Duration.ofSeconds(30))
    .build();
```

后面的 `SkillsTool.builder().runtime(runtime)` 代码保持不变。Server 安装、镜像、网络策略和会话恢复详见
[OpenSandbox 安装与配置](./open-sandbox)。

### AIO Sandbox

AIO 适合连接已经运行、由外部系统统一管理的 Sandbox 服务。添加依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-aio-sandbox</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
import com.agentsflex.skill.runtime.aiosandbox.AioSandboxSkillRuntime;

import java.util.concurrent.TimeUnit;

AioSandboxSkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .remoteRoot("/home/gem/workspace/skills")
    .httpTimeoutMillis((int) TimeUnit.MINUTES.toMillis(11))
    .build();
```

AIO Runtime 不创建也不停止容器。服务启动、端口暴露、JWT 鉴权和共享限制详见
[AIO Sandbox 安装与配置](./aio-sandbox)。

## 核心机制

### Skill 准备与路径转换

应用传给 `addSkillsDirectory(...)` 的路径是 Java 应用节点可读路径。`prepare()` 完成后，Skill 的
`basePath` 则必须是 Runtime 内真实可访问的路径：

```text
应用节点：/opt/my-app/skills/report-generator
                    │ prepare()
                    ▼
OpenSandbox：/workspace/skills/report-generator-...
```

`prepare(SkillPreparationRequest)` 是批量 API，一次接收本次配置的全部 Skill。Runtime 实现必须：

- 返回与输入相同数量的 Skill，并保持顺序；
- 不直接修改调用方传入的 Skill 对象；
- 保证返回的 `basePath` 已经可以访问；
- 在上传和初始化全部成功后才把 Skill 记为已准备；
- 在同一个 Runtime 生命周期内尽量避免重复上传和初始化。

调用方通常不需要直接调用 `prepare()`，`SkillsTool.buildTools()` 会自动完成这一步。

### 环境变量与 bootstrap

某些 Skill 需要额外 CLI、Python 包或 API 环境变量。使用 `SkillRuntimeConfig` 配置执行环境，不需要向标准
`SKILL.md` 添加 Agents-Flex 专用字段：

```java
import com.agentsflex.skill.runtime.SkillRuntimeConfig;

SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("REPORT_TEMPLATE_DIR", "/workspace/templates")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "report-generator")
    .skillRuntimeConfig("report-generator", config)
    .runtime(runtime)
    .buildTools();
```

准备顺序如下：

1. 合并本批次所有 Skill 的 Runtime 环境变量；
2. 复制或上传尚未准备的 Skill；
3. 以准备后的 Skill 根目录为工作目录执行 bootstrap；
4. 全部命令成功后记录准备缓存。

bootstrap 超时或返回非零退出码时，`prepare()` 失败且不会记录成功状态，下次准备仍会重试。环境变量保存在
Runtime 对象中，后续每次 `execute()` 都会重新注入；单次执行请求的同名变量优先级更高。

API Key 等敏感值不应写入 Skill 目录、日志或异常信息。Secret 获取、轮换和审计仍应由应用的 Vault、KMS 或
凭证系统负责。完整配置规则见 [Skill Runtime Config](./skill-runtime-config)。

### 命令执行

模型通常通过 `bash` 工具间接调用 `execute()`。业务代码也可以直接使用标准化 API：

```java
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;

import java.util.Collections;

SkillExecutionResult result = runtime.execute(new SkillExecutionRequest(
    "python scripts/create_report.py",
    "/workspace/skills/report-generator",
    60_000L,
    Collections.singletonMap("REPORT_FORMAT", "pdf")
));

if (result.isTimedOut() || result.getExitCode() != 0) {
    throw new IllegalStateException(result.getStderr());
}
System.out.println(result.getStdout());
```

`workingDirectory` 和路径都是 Runtime 内路径，不一定存在于 Java 应用宿主机。结果统一包含 `exitCode`、
`stdout`、`stderr` 和 `timedOut`，调用方不能只检查是否抛出异常，还应处理非零退出码与超时。

### 文件系统与产物

`SkillRuntimeFileSystem` 与 Shell 使用同一个执行边界：

```java
SkillRuntimeFileSystem fileSystem = runtime.getFileSystem();

fileSystem.writeText("output/summary.md", "# Summary");
String text = fileSystem.readText("output/summary.md", 1024 * 1024);
Path localFile = fileSystem.download(
    "output/report.pptx",
    Paths.get("downloads/report.pptx")
);
```

传给文件 API 的源路径始终是 Runtime 内路径。文本和小型二进制文件可以直接读取；大型 PDF、PPTX、图片或
压缩包应使用流或 `download()`，不要全部读入 Java 堆。需要把最终文件转换成用户可访问 URL 时，配置
`FilePublisher`，详见 [Skill 产物](./files)。

## 持续会话与 Workspace

三个内置 Runtime 都支持 `conversationId(...)` 和 `conversationsRoot(...)`。配置后，默认工作目录固定为：

```text
<conversationsRoot>/<conversationId>
```

相对文件路径基于该目录解析，Skill 会复制或上传到其 `skills` 子目录。`conversationId` 必须为 1-128 个
字符，只能包含字母、数字、点、下划线和连字符，首字符必须是字母或数字；`conversationsRoot` 必须是非根
绝对路径。

不同 Runtime 的会话恢复语义并不相同：

| Runtime | 新建对象后如何恢复相同会话 |
| --- | --- |
| Local | 重新使用本机会话目录；Skill 源文件会再次复制并覆盖同名文件 |
| AIO | 重新连接同一个 AIO 服务和远端目录；Skill 会再次上传 |
| OpenSandbox | 通过 Conversation Store 找到 `sandboxId`；已准备 Skill 不重复上传 |

OpenSandbox 默认使用 JVM 内存 Store。多节点或应用重启后需要恢复会话时，应配置共享的
`JdbcOpenSandboxConversationStore`。普通 `close()` 在会话模式下只释放本地 SDK 资源，不销毁远端 Sandbox；
业务会话真正结束时调用：

```java
runtime.destroyConversationSandbox();
```

不要在每轮对话结束时销毁，否则下一轮无法继续访问先前文件。Workspace 的路径规则、并发注意事项和清理策略
详见 [Skill Runtime Workspace](./skill-runtime-workspace)。

## 生命周期与并发

Runtime 具有明确生命周期，建议始终使用 try-with-resources：

```java
try (SkillRuntime runtime = createRuntime()) {
    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools());

    // 在 Runtime 关闭前完成模型和工具调用循环。
}
```

不要在 `buildTools()` 后立刻关闭 Runtime，再把工具保存到其他位置延迟使用。工具后续执行命令或读取文件时仍然
依赖该 Runtime。

`close()` 的资源语义取决于实现：Local 不删除 Workspace；AIO 不停止外部服务；OpenSandbox 非会话模式会
销毁本次 Sandbox，而会话模式会保留它。应用应另外制定会话目录、远端文件和长期 Sandbox 的过期清理策略。

同一个 Workspace 中的文件操作不具备事务或业务锁。多个请求并发修改同一份 PPTX、JSON 或中间文件时可能
相互覆盖，上层会话调度应对同一 `conversationId` 串行执行，或实现明确的文件级并发控制。

## 生产落地建议

### 保持工具处于同一执行边界

使用远程 Runtime 时，Shell、文件和搜索工具都应由同一个 `SkillsTool` 构建：

```java
prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory)
    .runtime(runtime)
    .buildTools());
```

不要再注册一套功能相同、却直接操作宿主机的 Shell 或文件工具。否则模型可能绕过 Sandbox，在 Java 应用节点
读取文件或执行命令。

### 为不同信任等级使用不同 Runtime

内部只读 Skill、用户上传 Skill 和拥有生产系统写权限的 Skill 不应共用同一执行策略。可以在业务层按来源、
审批状态和任务权限选择 Runtime，并为高风险任务使用更严格的镜像、网络和凭证配置。

### 固定并预热运行环境

生产环境应使用固定版本镜像，把 Python、浏览器、字体和常用 CLI 预装进去。每个任务临时访问公网安装依赖会
增加延迟、供应链风险和失败概率。bootstrap 更适合做轻量、幂等且与当前 Skill 版本相关的初始化。

### 设置多层限制

Runtime 请求超时只能限制单次命令等待时间。生产环境还应在容器或 Sandbox 层配置非 root 用户、CPU、内存、
进程数、磁盘配额、最小文件权限和出站网络白名单，并在应用层限制并发、输出长度和产物大小。

### 记录可审计信息

建议记录 Runtime 类型、Skill 名称与版本、conversationId、命令耗时、退出码和 Sandbox 标识，但不要记录
完整环境变量、API Key、用户文件正文或可能含敏感数据的完整命令输出。

## 自定义 Runtime

需要接入 Kubernetes Job、自研容器平台或其他远程执行服务时，实现 `SkillRuntime` 和
`SkillRuntimeFileSystem`：

```java
public final class MySandboxRuntime implements SkillRuntime {
    @Override
    public String getName() {
        return "my-sandbox";
    }

    @Override
    public List<Skill> prepare(SkillPreparationRequest request) {
        // 上传 Skill、执行 bootstrap，并返回 Runtime 内的新路径。
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return "/workspace";
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        // 映射命令、工作目录、环境变量、超时和结果。
    }

    @Override
    public void close() {
        // 只释放当前 Runtime 真正拥有的资源。
    }
}
```

自定义实现尤其需要保证：批量准备的数量和顺序一致、上传与 bootstrap 幂等、超时后终止命令、二进制文件使用
流式传输、路径和 Shell 参数正确转义、上传不跟随符号链接，以及 `close()` 的资源所有权清晰。完整检查清单见
[自定义 SkillRuntime](./custom-runtime)。

## 常见问题

### Runtime 和 Sandbox 是一回事吗？

不是。Runtime 是 Agents-Flex 的 Java 抽象；Sandbox 是某些 Runtime 使用的底层隔离环境。
`LocalSkillRuntime` 不创建 Sandbox，OpenSandbox 和 AIO Runtime 则把接口映射到各自平台。

### 配置 Workspace 后，Local Runtime 是否安全？

不是。Workspace 会约束框架文件 API，并避免不同会话误用相对路径，但 Local Shell 仍继承 Java 进程权限，
脚本也可能使用绝对路径或符号链接访问目录外内容。

### 为什么远程 Runtime 在 buildTools() 时就上传 Skill？

工具构建完成时，模型就可能读取 Skill 路径并立即执行命令。提前完成 `prepare()` 可以确保模型看到的每个路径
已经可访问，也能在进入模型调用循环前暴露上传或 bootstrap 错误。

### 每条消息都要创建新的 Runtime 吗？

一次性任务可以创建一次并在结束后关闭。持续会话可以为每次请求创建 Runtime 对象，但必须使用稳定的
`conversationId`，并根据具体实现配置共享目录或 Conversation Store。不要让多个请求同时修改同一会话。

### SkillArtifactStore 和 SkillRuntime 如何配合？

Artifact Store 先把确定版本物化为应用节点上的目录，`SkillsTool` 再把它交给 Runtime 准备。前者解决版本安装
与分发，后者解决执行位置与文件访问，二者职责独立。

## 下一步

- [深入了解 Local Runtime](./local-runtime)
- [配置 Skill Runtime Config](./skill-runtime-config)
- [配置 Skill Runtime Workspace](./skill-runtime-workspace)
- [部署 OpenSandbox](./open-sandbox)
- [部署 AIO Sandbox](./aio-sandbox)
- [实现自定义 Runtime](./custom-runtime)
- [交付 Runtime 产物](./files)

</div>
