<div v-pre>

# Local Runtime

## 概述

`LocalSkillRuntime` 直接在当前 Java 应用所在的宿主机执行 Skill。它使用本机 Shell、文件系统、环境变量
和进程权限，不创建容器，也不连接外部 Sandbox 服务。

Local Runtime 适合以下场景：

- 在开发机快速编写和调试 Skill；
- 在 CI 中测试可信的内部 Skill；
- 执行来源可控、权限和资源需求明确的内部自动化任务。

它不适合直接执行第三方 Skill、用户提交的任意命令或其他不可信内容。`Local` 描述的是执行位置，不代表
安全隔离。

## 快速开始

先添加核心 Skills 模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

然后加载 Skill 目录，并把完整工具组注册给 Prompt：

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.local.LocalSkillRuntime;

import java.util.List;

try (LocalSkillRuntime runtime = new LocalSkillRuntime()) {
    List<Tool> tools = SkillsTool.builder()
        .addSkillsDirectory("/absolute/path/to/skills")
        .runtime(runtime)
        .buildTools();

    prompt.addTools(tools);
    // 将 prompt 交给支持 Tool Calling 的 ChatModel。
}
```

`buildTools()` 会发现 Skill、调用 `runtime.prepare()`，并注册 `skill`、`bash`、`read`、`write`、
`edit`、`ls`、`glob` 和 `grep` 等工具。模型读取 Skill 后，可以使用这些工具在本机完成任务。

## 推荐：启用 Workspace

无参构造器保留最直接的本地行为：Skill 使用原始目录，文件 API 可访问 Java 进程有权访问的任意本机路径。
需要持续会话或同时处理多个会话时，建议通过 Builder 启用 Workspace：

```java
try (LocalSkillRuntime runtime = LocalSkillRuntime.builder()
    .conversationsRoot("/var/lib/my-app/skill-conversations")
    .conversationId(conversationId)
    .build()) {

    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory("/opt/my-app/skills", "report-generator")
        .runtime(runtime)
        .buildTools());
}
```

启用后的默认目录是 `<conversationsRoot>/<conversationId>`。Local Runtime 会把 Skill 复制到当前会话的
`skills` 子目录，再执行 bootstrap；文件工具也只能接收当前 Workspace 内的路径。详细规则见
[Skills Workspace](./workspace)。

## 两种运行模式

| 行为 | `new LocalSkillRuntime()` | Builder + `conversationId` |
| --- | --- | --- |
| 默认命令目录 | Java 进程当前工作目录 | 当前会话 Workspace |
| Skill `basePath` | 原始 Skill 目录 | Workspace 中的 Skill 副本 |
| bootstrap 写入位置 | 原始 Skill 目录 | Skill 副本目录 |
| 文件 API 范围 | 宿主机上进程可访问的路径 | 词法上限制为当前 Workspace |
| 会话文件恢复 | 由调用方管理路径 | 复用相同会话目录 |
| 安全隔离 | 无 | 无；只增加路径约束 |

Builder 模式更适合应用集成，但它不会限制 Shell 命令本身访问宿主机其他路径。需要真正隔离时，应切换到
OpenSandbox 或 AIO Sandbox Runtime。

## Skill 准备与 bootstrap

`SkillsTool.build()` 或 `buildTools()` 会调用 Local Runtime 的 `prepare()`。准备阶段按以下顺序执行：

1. 合并所有 Skill 的 `SkillRuntimeConfig.environment`；
2. 在 Workspace 模式下复制尚未准备的 Skill；
3. 在实际 Skill 根目录中依次执行 bootstrap 命令；
4. 全部成功后，把该 Skill 记录为当前 Runtime 已准备。

可以为某个 Skill 配置本机依赖初始化和后续命令所需的环境变量：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("REPORT_TEMPLATE_DIR", "/opt/my-app/templates")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory("/opt/my-app/skills", "report-generator")
    .skillRuntimeConfig("report-generator", config)
    .runtime(runtime)
    .buildTools();
```

同一个 Runtime 对象重复准备同一源路径时，不会再次执行 bootstrap。如果 bootstrap 超时或返回非零退出码，
准备会失败且不会写入成功缓存，下次准备仍可重试。

在 Workspace 模式下，新建 Runtime 并复用同一会话时，会重新把源 Skill 文件复制到固定目标目录。复制会
覆盖同名源文件，但不会清空只在目标目录中生成的其他文件。

## 命令执行

Local Runtime 在 Unix-like 系统上通过 `/bin/bash -c` 执行命令，在 Windows 上通过 `cmd.exe /c` 执行。
可以不经过模型工具，直接调用 Runtime：

```java
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;

import java.util.Collections;

SkillExecutionResult result = runtime.execute(new SkillExecutionRequest(
    "./scripts/build-report.sh",
    null,
    60_000L,
    Collections.singletonMap("REPORT_FORMAT", "pdf")
));

if (result.isTimedOut() || result.getExitCode() != 0) {
    throw new IllegalStateException(result.getStderr());
}
System.out.println(result.getStdout());
```

运行规则如下：

- `workingDirectory` 为 `null` 或空值时，使用 Runtime 默认工作目录；
- Workspace 模式下，显式工作目录必须位于当前 Workspace 内；
- `SkillRuntimeConfig` 中的环境变量会注入后续命令；
- 单次请求的环境变量优先级更高，可覆盖同名 Runtime 环境变量；
- 达到超时后先终止进程，必要时再强制终止，并返回 `timedOut=true`、`exitCode=-1`；
- 标准输出与标准错误会并行消费，结果统一写入 `SkillExecutionResult`。

`bash` 模型工具还会应用自身的超时和输出长度上限；直接调用 `execute()` 时，由调用方提供有效的正超时值并
处理输出内容。

## 文件与产物

`runtime.getFileSystem()` 使用 JDK NIO 直接操作本机文件，支持文本读写、文件信息、目录遍历、二进制流与
下载等 `SkillRuntimeFileSystem` 能力：

```java
runtime.getFileSystem().writeText("output/summary.md", "# Summary");
String summary = runtime.getFileSystem().readText("output/summary.md", 1024 * 1024);
```

相对路径是否限定在会话目录，取决于是否启用 Workspace。Local Runtime 的“下载”本质上是从一个本机路径
复制或流式读取到另一个目标；远程 Runtime 则需要先从 Sandbox 取回数据。大型产物的流式处理与发布方式见
[Skill 产物](./files)。

## 生命周期与并发

建议使用 try-with-resources 管理 Runtime。`close()` 会清理当前对象内保存的环境变量和 Skill 准备缓存，
不会删除原始 Skill、Workspace 或其中的产物。

`prepare()`、环境变量读取和 `close()` 对 Runtime 内部状态做了同步保护，但这不代表同一 Workspace 中的文件
操作具有事务或锁。业务层应避免让多个请求并发修改同一个 `conversationId` 对应的目录。

如果应用长期保存 Workspace，应自行制定容量、过期、归档和删除策略。不要把临时产物的生命周期隐式绑定到
Java 对象的 `close()`。

## 安全与生产建议

Local Runtime 创建的子进程继承当前 Java 进程用户的本机文件和网络权限。启用 Workspace 后，文件 API 会
拒绝目录外路径，但 Shell、符号链接以及 Skill 自身代码仍可能越过这层词法约束。

使用 Local Runtime 时至少应做到：

- 只运行经过审核且来源可信的 Skill；
- 使用低权限专用系统用户启动 Java 应用；
- 不把云凭证、SSH Key 或生产密钥暴露给 Skill 进程；
- 在应用层限制任务来源、并发量、超时和输出大小；
- 对网络、CPU、内存和子进程数量有隔离需求时，改用受控 Sandbox。

同一套 Skill 和 `SkillsTool` 集成代码可以切换到其他 Runtime，通常不需要修改 `SKILL.md` 或模型使用的工具
名称。

## 下一步

- [Skill Config](./skill-config)
- [Skills Workspace](./workspace)
- [Skill Runtime](./runtime)
- [OpenSandbox](./open-sandbox)
- [AIO Sandbox](./aio-sandbox)

</div>
