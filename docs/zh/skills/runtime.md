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
        │ SkillRuntime.prepare(List<Skill>)
        ├──────── Local：保留本地路径
        └──────── Remote：上传目录并改写 basePath
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

### prepare() 是批量 API

`SkillRuntime.prepare(List<Skill>)` 一次接收当前配置的全部 Skills，不是只准备一个 Skill。实现必须：

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
| Skill 上传 | 不上传 | 自动上传 | 自动上传 |
| basePath | 本机绝对路径 | `/workspace/skills/...` | `/home/gem/workspace/skills/...` |
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
