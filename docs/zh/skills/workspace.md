<div v-pre>

# Skills Workspace

## 概述

Skills Workspace 是一个由 `SkillRuntimeWorkspace` 描述的会话工作目录。启用后，每个业务会话都使用
独立的目录，Runtime 的相对文件路径、默认命令工作目录以及准备后的 Skill 都位于该目录中：

```text
<conversationsRoot>/
└── <conversationId>/                 当前会话的 Workspace
    ├── skills/                       Runtime 准备后的 Skill 副本
    │   └── <skill-name>-<source-hash>/
    └── output/                       Skill 或业务按需创建的产物目录
```

Workspace 主要解决两个问题：

- 让同一会话产生的 Skill 副本、中间文件和最终产物保存在稳定位置；
- 让不同会话通过不同目录减少文件误读、误写和相互覆盖。

Workspace 是 Runtime 的文件组织与路径约束，不是容器或操作系统级安全沙箱。它与 Local、OpenSandbox、
AIO Sandbox Runtime 配合使用，但不能替代进程权限、容器、资源限额和网络策略。

## 快速开始

下面使用 Local Runtime 创建一个会话 Workspace：

```java
import com.agentsflex.skill.local.LocalSkillRuntime;

try (LocalSkillRuntime runtime = LocalSkillRuntime.builder()
    .conversationsRoot("/var/lib/my-app/skill-conversations")
    .conversationId("thread-20260726-001")
    .build()) {

    System.out.println(runtime.getDefaultWorkingDirectory());
    // /var/lib/my-app/skill-conversations/thread-20260726-001

    runtime.getFileSystem().writeText("output/result.txt", "done");
    // 写入 /var/lib/my-app/skill-conversations/thread-20260726-001/output/result.txt
}
```

`conversationsRoot` 必须是非根绝对路径。`conversationId` 应由业务层提供，并在所需隔离范围内保持稳定且
唯一。再次使用相同的两个值创建 Runtime，可以继续访问之前保留的文件。

三个内置 Runtime 的 Builder 都提供相同的 Workspace 配置入口：

```java
.conversationsRoot("/absolute/path/to/conversations")
.conversationId(conversationId)
```

对于远程 Runtime，这里的绝对路径是 Runtime 或 Sandbox 内的路径，不是 Java 应用节点上的路径。

## Workspace 如何参与执行

启用 Workspace 后，Runtime 会统一处理以下路径：

| 操作 | Workspace 行为 |
| --- | --- |
| `getDefaultWorkingDirectory()` | 返回当前会话 Workspace 根目录 |
| 相对文件路径 | 基于 Workspace 根目录解析 |
| 绝对文件路径 | 仅允许 Workspace 根目录自身或其内部路径 |
| 未指定工作目录的命令 | 以 Workspace 根目录作为工作目录 |
| 显式指定工作目录的命令 | 先校验目录是否位于当前 Workspace 内 |
| `prepare()` | 将 Skill 复制或上传到 `skills` 子目录，并改写 `Skill.basePath` |
| bootstrap | 在准备后的 Skill 副本目录执行 |

例如，当前 Workspace 为 `/srv/conversations/thread-a` 时：

```text
output/report.md                         -> 允许，解析到当前 Workspace
/srv/conversations/thread-a/output/a.md -> 允许
../thread-b/private.txt                 -> 拒绝
/srv/conversations/thread-b/private.txt -> 拒绝
```

空路径也会被拒绝。路径在校验前会统一分隔符并消除 `.`、`..` 等词法片段，避免通过普通路径回退进入
其他会话目录。

## 会话 ID 与根目录约束

`conversationId` 必须满足以下规则：

- 长度为 1-128 个字符；
- 首字符必须是字母或数字；
- 其余字符只能是字母、数字、点、下划线或连字符；
- `.` 和 `..` 不能作为会话 ID。

常见做法是把租户、用户和业务会话 ID 组合成一个稳定值，例如
`tenant42-user7-thread20260726`。如果原始 ID 含有斜杠、空格或其他不允许的字符，应在业务层生成不可碰撞的
安全编码，不要只删除字符后直接使用。

`conversationsRoot` 必须满足以下规则：

- 使用绝对路径；
- 不能是文件系统根目录；
- Local Runtime 所在的 Java 进程用户必须有权创建和读写该目录；
- 远程 Runtime 中的路径必须与 Sandbox 镜像、挂载目录和持久化策略一致。

Builder 会在构建阶段校验这些配置。Local Runtime 还会立即创建会话目录。

## Skill 准备与会话恢复

Workspace 模式下，原始 Skill 目录只作为准备阶段的来源。Runtime 返回一个新的 `Skill` 对象，其
`basePath` 指向当前会话中的副本。bootstrap 生成的依赖、脚本或缓存也写入副本，不会污染原始 Skill。

不同 Runtime 对相同会话的恢复方式有所区别：

| Runtime | 再次连接同一会话时的行为 |
| --- | --- |
| Local | 复用固定目标目录；重新复制源文件，保留目标中仅由运行过程生成的文件 |
| AIO Sandbox | 复用远端固定目录；重新上传源文件，保留仅在目标生成的文件 |
| OpenSandbox | 通过 `OpenSandboxConversationStore` 恢复 Sandbox 与已准备 Skill 的路径映射 |

同一个 Runtime 对象内，已成功准备的 Skill 不会重复复制、上传或执行 bootstrap。OpenSandbox 恢复已有
映射时也不会因本机源目录变化而自动发布新版 Skill；版本升级应配合新的会话、Sandbox 清理或上层 Skill
版本策略。

## 文件访问与命令边界

Workspace 会包装 `SkillRuntimeFileSystem`，因此 `read`、`write`、`edit`、`ls`、`glob`、`grep` 以及
应用直接调用的文件 API 都使用相同的会话路径规则。`SkillExecutionRequest` 未提供工作目录时，Runtime
也会自动使用 Workspace 根目录。

自定义 Runtime 可以复用相同能力：

```java
SkillRuntimeWorkspace workspace = SkillRuntimeWorkspace.create(
    "/srv/skill-conversations",
    conversationId
);

SkillRuntimeFileSystem scopedFiles = workspace.scopeFileSystem(runtimeFiles);
SkillExecutionRequest scopedRequest = workspace.scopeExecution(request);
```

文件系统包装器只负责校验传入 API 的路径。自定义 Runtime 仍需确保命令和文件操作最终发生在同一个实际
执行环境中。

## 生命周期与清理

`close()` 不是通用的 Workspace 删除操作：

- Local Runtime 清理内存中的环境变量与准备缓存，不删除会话文件；
- AIO Sandbox Runtime 不停止外部服务，也不删除远端会话文件；
- OpenSandbox 配置会话 ID 后，普通 `close()` 只释放当前 SDK 资源，不销毁远端 Sandbox。

因此，Workspace 保留多久、何时归档产物、何时删除目录，应由业务会话生命周期决定。不要让多个请求并发
修改同一个 Workspace；内置路径约束不会提供文件锁或事务。

OpenSandbox 会话结束时，可通过 `destroyConversationSandbox()` 显式销毁远端 Sandbox。Local 与 AIO
场景应由应用或运维任务按保留策略清理对应目录。

## 安全边界

Workspace 提供的是词法路径边界，主要防止工具参数误入其他会话。它不构成安全隔离，原因包括：

- Local Runtime 的 Shell 命令仍拥有当前 Java 进程用户的全部权限；
- Shell 命令文本可以主动访问 Workspace 外的绝对路径；
- Workspace 内的符号链接可能指向目录外；
- 路径约束不限制网络、CPU、内存、进程数量或系统调用。

处理第三方 Skill、不可信输入或多租户生产任务时，应使用独立 Sandbox、非 root 用户、最小文件权限、
资源限额和出站网络策略。Workspace 应作为这些安全措施之上的会话文件边界。

## 下一步

- [Local Runtime](./local-runtime)
- [OpenSandbox](./open-sandbox)
- [Sandbox 会话隔离实战](./sandbox-conversation-isolation-lab)

</div>
