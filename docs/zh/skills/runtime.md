<div v-pre>

# Skill Runtime

## 什么是 Skill Runtime

[Agent Skills 规范](https://agentskills.io/specification)定义了 Skill 的目录、`SKILL.md` 和渐进式披露
方式，但不规定脚本必须在哪里运行，也不规定 Agent 应使用哪一种 Shell 或文件系统。

`SkillRuntime` 是 Agents-Flex 提供的执行抽象。它把 Skill 目录准备、命令执行、文件访问和资源生命周期
放在同一个边界内，使同一套 Skill 可以运行在当前 Java 进程所在主机、临时 Sandbox 或已有的远程
Sandbox 服务中。

`SkillRuntime` 主要负责：

- `prepare()`：让一批 Skill 在目标执行环境中可访问，并返回 Runtime 内的路径；
- `execute()`：执行命令，统一传递工作目录、环境变量和超时，并返回退出码及输出；
- `getFileSystem()`：提供与命令相同执行边界内的文本、二进制、列表和下载能力；
- `close()`：释放当前 Runtime 拥有的 Sandbox、连接或缓存状态。

它不负责定义 Agent Skills 格式，也不负责 Skill 的版本目录、引用关系和持久化。安装与分发由
[SkillArtifactStore](./artifact-store) 处理；模型何时选择 Skill 则由 `SkillsTool` 暴露的元数据和模型决策
共同完成。

## 执行流程

```text
本地目录 / classpath / SkillArtifactStore 物化目录
        │ SkillsTool 发现并解析 SKILL.md
        ▼
List<Skill>，basePath 是当前应用节点可读路径
        │ SkillRuntime.prepare(SkillPreparationRequest)
        ├──────── 未启用会话目录的 Local：保留本地路径
        └──────── 会话目录 / Remote：复制或上传目录并改写 basePath
                         │
                         ▼
模型激活 Skill，获得完整说明和 Runtime 内路径
        │
        ├── Bash ────────────── SkillRuntime.execute
        ├── Read/Write/Edit ─── SkillRuntimeFileSystem
        ├── Glob/Grep ───────── listFiles + readText
        └── 产物回收/发布 ───── openInputStream / download / PublishFile
```

`SkillsTool.build()` 或 `buildTools()` 会先完成发现、筛选和 `prepare()`，再创建模型工具。因此远程 Skill
通常在构建工具时上传，而不是等到模型第一次激活 Skill 时才上传。

### Runtime 配置与 bootstrap

`Skill` 和 `SKILL.md` 保持 Agent Skills 标准结构。运行所需的环境变量和初始化命令通过独立的
`SkillRuntimeConfig` 配置，不需要向 Skill front matter 添加 Agents-Flex 专用字段：

```java
SkillRuntimeConfig kdocsConfig = SkillRuntimeConfig.builder()
    .environment("KDOCS_CLI_DIR", "/home/gem/.local/bin")
    .environment("PATH", "/home/gem/.local/bin:/usr/local/bin:/usr/bin:/bin")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "kdocs-skill")
    .skillRuntimeConfig("kdocs-skill", kdocsConfig)
    .runtime(runtime)
    .buildTools());
```

准备顺序固定为：

1. 合并当前批次的 Runtime 环境变量；
2. 上传尚未准备的 Skill；
3. 以远端 Skill 根目录为工作目录依次执行 bootstrap；
4. 所有命令成功后才记录上传缓存。

同一个 Runtime 生命周期内，命中上传缓存的 Skill 不会重复执行 bootstrap。Local Runtime 没有上传
过程，因此把首次准备本地 Skill 视为一次物化，并执行一次 bootstrap。bootstrap 超时或返回非零退出码时，
`prepare()` 失败且不记录成功状态，下次准备仍会重试。

环境变量保存在 Runtime 对象中，而不是通过 `export`、`.bashrc` 或 `.profile` 持久化。后续每次
`execute()` 都会重新注入这些变量，单次 `SkillExecutionRequest.environment` 的同名值具有更高优先级。
多个 Skill 声明同名变量时，按传给 `prepare()` 的 Skill 顺序合并，后面的配置覆盖前面的值。

环境变量可能包含 Token 或 API Key。应用不得记录完整配置、将其写入 Skill 上传目录，或在异常信息中
输出变量值。当前 API 不区分普通值与 Secret；需要 Vault、KMS 或短期凭证时，应在应用层取得值后再构建
`SkillRuntimeConfig`。

### prepare() 是批量 API

`SkillRuntime.prepare(SkillPreparationRequest)` 是批量 API，一次接收当前配置的全部 Skills 及其
Runtime 配置，不是只准备一个 Skill。实现必须：

1. 返回与输入数量相同的列表；
2. 保留输入顺序；
3. 不返回 `null` 元素；
4. 让返回 Skill 的 `basePath` 在 Runtime 内真实可访问；
5. 不直接修改调用方传入的 Skill 对象。

同一个 Runtime 生命周期内重复准备相同 Skill 时，实现还应尽量保持幂等。远程 Runtime 应先完成上传或
复制，再返回新的 `Skill` 对象，不能让模型看到尚未准备完成的目录。

## Runtime 选择

| 能力 | LocalSkillRuntime | OpenSandboxSkillRuntime | AioSandboxSkillRuntime |
| --- | --- | --- | --- |
| 命令位置 | 当前宿主机 | OpenSandbox 创建的容器 | 已运行的 AIO 容器/服务 |
| Skill 准备 | 无会话目录时直接使用；启用后复制 | 自动上传 | 自动上传 |
| basePath | 原路径或 `<会话目录>/skills/...` | `/workspace/skills/...` 或会话路径 | `/home/gem/workspace/skills/...` 或会话路径 |
| 文件读写 | JDK NIO | OpenSandbox Files SDK | AIO File HTTP API |
| 二进制下载 | 本地文件流 | SDK `readStream` | `/v1/file/download` |
| Runtime 所有权 | 不创建外部资源 | 拥有本次创建的 Sandbox | 连接外部管理的服务 |
| `close()` 行为 | 无常驻资源 | kill 并关闭 Sandbox | 清理客户端状态，不停止服务 |
| 隔离能力 | 无 | 取决于 OpenSandbox 配置 | 取决于 AIO Docker/部署配置 |

选择建议：

- 本地开发、测试或完全可信的内部 Skill，可以使用 `LocalSkillRuntime`；
- 希望按任务创建并销毁隔离环境，可以使用 `OpenSandboxSkillRuntime`；
- 已经部署并统一管理 AIO Sandbox 服务，可以使用 `AioSandboxSkillRuntime`；
- 生产环境不要把 Local Runtime 当作安全隔离方案。

Runtime 具有生命周期。建议使用 try-with-resources，确保正常结束或异常退出时都调用 `close()`：

```java
try (SkillRuntime runtime = createRuntime()) {
    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools());

    // 执行模型与工具调用循环
}
```

## 会话工作目录

三个内置 Runtime 都支持相同的会话目录配置：

```java
LocalSkillRuntime runtime = LocalSkillRuntime.builder()
    .conversationId(conversationId)
    .conversationsRoot("/absolute/path/to/conversations")
    .build();
```

OpenSandbox 和 AIO Sandbox 的 Builder 同样提供 `conversationId(...)` 与 `conversationsRoot(...)`。
配置后，默认工作目录固定为 `<conversationsRoot>/<conversationId>`；相对文件路径基于该目录解析，
文件 API 和显式命令工作目录都只能落在该目录内。`prepare()` 会先把全部 Skill 复制或上传到会话目录的
`skills` 子目录并改写 `basePath`，再在复制或上传后的 Skill 目录执行 bootstrap。因此 Skill 在自身当前
目录生成的脚本、缓存和中间文件也属于当前会话，不会写回宿主机上的原始 Skill 目录。

同一会话再次准备 Skill 时，源文件会覆盖更新到原来的固定目标路径，但不会清空目标目录中仅由运行过程生成的
文件。这样后续对话既能取得最新的 Skill 资源，也能继续使用前一次生成的文件。

业务层应使用稳定的 `conversationId`。Local 与连接长期 AIO 服务的 Runtime 可以通过稳定目录找到之前的
文件。OpenSandbox Runtime 还通过 `OpenSandboxConversationStore` 保存 `sandboxId` 和 Skill 准备状态。
默认内存 Store 只支持单 JVM；配置所有节点共享的 `JdbcOpenSandboxConversationStore` 后，应用重启或请求切换
到其他 JVM 时会通过 SDK Connector 恢复同一个远端 Sandbox。

OpenSandbox 持久化键由服务连接标识和 `conversationId` 组成。业务层必须保证 `conversationId` 在所需隔离范围内
唯一，多租户系统可以把租户和用户边界编码进业务会话 ID。JDBC Store 通过唯一主键解决多个节点同时创建 Sandbox
的竞争，但不串行同一会话的 Skill 执行或文件修改；上层会话调度应避免并发修改同一个工作目录。普通 `close()`
只释放当前 Runtime 的 SDK 资源，不销毁远端 Sandbox，业务会话结束时应调用 `destroyConversationSandbox()`。

这是一层防止误读误写的词法路径边界，不是安全沙箱。Shell 命令仍可显式访问绝对路径，符号链接也可能越过
目录边界。处理不可信用户或租户时，仍应使用独立容器、非 root 用户和底层文件权限；不要只依赖目录隔离。

## 保持同一执行边界

使用远程 Runtime 时，应注册：

```java
prompt.addTools(
    SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools()
);
```

文件、搜索与 Shell 工具应统一由 Skill Runtime 提供。不要同时注册功能相同但直接操作宿主机的
Commons Tools，否则模型可能绕过 Sandbox，在宿主机读取文件或执行命令。

Runtime 只是执行边界的一部分。生产环境仍需在 Sandbox 或容器层配置非 root 用户、CPU 和内存限制、
执行超时、最小文件权限及出站网络策略。

## 下一步

- [OpenSandbox](./open-sandbox)
- [AIO Sandbox](./aio-sandbox)
- [自定义 Runtime](./custom-runtime)

</div>
