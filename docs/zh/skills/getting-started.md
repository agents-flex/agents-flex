<div v-pre>

# Skills 快速开始

## 概述

Agent Skills 用一个包含 `SKILL.md` 的目录，把任务说明、脚本、参考资料和模板组织成可复用能力。与把所有说明
直接塞进系统提示词不同，模型启动时只看到每个 Skill 的名称和描述，真正需要时才加载完整说明和相关文件。

例如，一个“发布说明生成” Skill 可以统一规定：

- 提交记录如何分类；
- 哪些内部信息不能出现在公开版本中；
- 输出 Markdown 的标题和章节结构；
- 最终文件写到哪里、如何校验；
- 需要时运行哪个脚本补充贡献者或变更链接。

应用只需把 Skill 和 Runtime 注册给 `SkillsTool`。模型匹配到任务后，会读取 Skill，并在同一个 Runtime 中使用
Shell、文件和搜索工具完成工作。

```text
应用启动或请求开始
        ↓
发现 Skill 的 name / description
        ↓
模型判断当前任务需要 release-notes
        ↓
调用 skill 工具读取完整 SKILL.md
        ↓
使用 bash / read / write / glob / grep 执行流程
        ↓
生成并交付 release-notes.md
```

本章会从零创建一个简单 Skill，并使用 Local Runtime 跑通完整接入链路。

## 适用场景

### 文档和文件生成

把 PPTX、PDF、Excel、周报、合同或图片的生成步骤、模板和校验脚本放入 Skill。模型不只得到一句“帮我生成
文件”，还知道必须使用什么版式、如何验证以及最终产物放在哪里。

### 企业流程与领域规范

把代码发布、故障复盘、合同审查、客服质检等标准流程写入 Skill，并把详细规范放到 `references/`。团队更新
流程时只需发布新 Skill 版本，不必修改每个业务 Prompt。

### 复用确定性脚本

复杂计算、格式转换、文档渲染和结构校验不适合每次都让模型临时编写。可以把成熟脚本放进 `scripts/`，由
`SKILL.md` 告诉模型何时执行、输入输出是什么以及如何判断成功。

### 多模型和多执行环境复用

Skill 使用开放目录格式，不绑定具体模型。Agents-Flex 还通过 `SkillRuntime` 统一本机、OpenSandbox 和 AIO
Sandbox 的命令与文件工具，同一套 Skill 可以在不同环境运行。

### 不适合使用 Skill 的情况

一次性的简短要求直接写入用户 Prompt 更简单；纯 Java 业务逻辑应继续用普通 Tool；需要检索大量动态资料时，
应使用 RAG 或搜索能力。Skill 更适合可复用、有明确触发场景、包含多步说明或配套资源的任务能力。

## 快速开始

### 1. 添加 Maven 依赖

Local Runtime 和 Skills 核心 API 都位于 `agents-flex-skills`：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

还需要一个支持 Tool Calling 的 `ChatModel`，并使用应用现有的模型工具调用循环执行模型返回的 Tool。

### 2. 创建第一个 Skill

在应用可读的绝对路径下创建目录：

```text
/opt/my-app/skills/
└── release-notes/
    └── SKILL.md
```

`SKILL.md` 内容如下：

```markdown
---
name: release-notes
description: Generate user-facing release notes from commit or change lists. Use when preparing a product release or changelog.
---

# Release Notes

When generating release notes:

1. Group changes into Features, Improvements, Bug Fixes, and Breaking Changes.
2. Rewrite internal implementation details as user-facing outcomes.
3. Omit commit hashes, internal ticket URLs, credentials, and contributor emails.
4. Keep each item concise and use Markdown bullet lists.
5. Run `pwd` to identify the Runtime workspace, then write the final document to the absolute path `<workspace>/output/release-notes.md`.
6. Read the finished file and verify that it contains a title and at least one change item.
```

front matter 中的 `name` 必须是模型调用 `skill` 工具时使用的准确名称；`description` 应同时说明“它做什么”和
“什么时候使用”。正文则描述模型激活 Skill 后要遵循的完整步骤。

### 3. 注册 Skill 和 Runtime

下面使用带会话 Workspace 的 Local Runtime。Skill 会先复制到会话目录，执行期间不会修改
`/opt/my-app/skills` 中的原始文件：

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.local.LocalSkillRuntime;

import java.util.List;

try (LocalSkillRuntime runtime = LocalSkillRuntime.builder()
    .conversationId("release-2026-07-26")
    .conversationsRoot("/var/lib/my-app/skill-conversations")
    .build()) {

    List<Tool> tools = SkillsTool.builder()
        .addSkillsDirectory("/opt/my-app/skills", "release-notes")
        .runtime(runtime)
        .buildTools();

    prompt.addTools(tools);

    // 在 runtime 关闭前完成模型和 Tool 调用循环。
    runAgent(prompt);
}
```

`conversationsRoot` 必须是非根绝对路径。示例运行后，Runtime 会创建类似目录：

```text
/var/lib/my-app/skill-conversations/
└── release-2026-07-26/
    ├── skills/
    │   └── release-notes-<source-hash>/
    │       └── SKILL.md
    └── output/
        └── release-notes.md
```

### 4. 向模型发起任务

例如向 Prompt 添加下面的用户消息：

```text
请根据以下变更生成 2.4.0 的发布说明：
- add artifact cache for remote skills
- fix duplicate upload when two nodes start together
- BREAKING: rename workspaceRoot to conversationsRoot
```

一次典型的 Tool 调用顺序是：

```text
skill(command="release-notes")
        ↓ 读取完整流程
bash(command="pwd")
        ↓ 确认 Runtime Workspace
write(filePath="<workspace>/output/release-notes.md", ...)
        ↓ 写入最终文件
read(filePath="<workspace>/output/release-notes.md")
        ↓ 验证结果
模型返回摘要或发布文件
```

具体调用顺序由模型决定，不要求每次完全一致。关键是模型先通过 `skill` 取得完整说明，再使用同一个 Runtime
提供的文件和 Shell 工具执行任务。

### 5. 读取生成结果

业务代码需要消费生成的 Markdown 时，应在 Runtime 关闭前读取：

```java
String releaseNotes = runtime.getFileSystem().readText(
    "output/release-notes.md",
    1024 * 1024
);
System.out.println(releaseNotes);
```

如果最终产物是 PDF、PPTX、图片或压缩包，不要使用文本 API。可以下载到应用节点，或配置
`FilePublisher` 让模型返回用户可访问 URL，详见 [Skill 产物](./files)。

## 这段代码做了什么

`SkillsTool.builder()` 把 Skill 发现、Runtime 准备和模型工具组装在一起：

1. `addSkillsDirectory(...)` 递归发现文件名为 `SKILL.md` 的 Skill；
2. 指定名称后，只保留本次真正需要的 Skill；
3. `buildTools()` 调用 `runtime.prepare()`，Local Workspace 会复制目录，远程 Runtime 会上传目录；
4. Runtime 返回新的 `basePath`，确保模型看到的是执行环境内路径；
5. Builder 注册 `skill`、Shell、文件和搜索工具；
6. 模型先看到名称与描述，调用 `skill` 后才加载完整正文。

这种渐进式披露避免在每轮会话开始时把所有 Skill 的完整说明、脚本和参考资料都塞进模型上下文。

## 按名称加载 Skill

一个根目录可以包含很多 Skill。生产请求通常只加载业务已经授权并且与当前 Agent 相关的部分：

```java
List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory(
        "/opt/my-app/skills",
        "pdf",
        "xlsx",
        "release-notes"
    )
    .runtime(runtime)
    .buildTools();
```

名称来自 `SKILL.md` front matter，匹配区分大小写。指定名称不存在，或者目录中出现多个同名 Skill 时，Builder
会立即抛出 `IllegalArgumentException`。

筛选发生在 `SkillRuntime.prepare()` 之前，因此未选中的 Skill：

- 不会出现在模型可见的 Skill 列表中；
- 不会上传到远程 Runtime；
- 不会执行 bootstrap；
- 不会因为目录中存在就自动获得调用权限。

不传名称时会加载根目录中发现的全部 Skill，只适合目录规模较小且所有 Skill 都允许当前 Agent 使用的场景。

## 模型获得哪些工具

`buildTools()` 返回共享同一个 Runtime 的完整工具组：

| 工具名 | 作用 | 主要限制 |
| --- | --- | --- |
| `skill` | 按名称加载 Skill 正文 | 只能选择已发现的 Skill |
| `bash` | 在 Runtime 内执行 Shell | 默认 120 秒，最大 600 秒，输出最多 30000 字符 |
| `read` | 读取 UTF-8 文件并显示行号 | 最大 4 MiB，默认 2000 行，单行最多 2000 字符 |
| `write` | 创建或覆盖 UTF-8 文件 | Runtime 负责创建父目录 |
| `edit` | 精确字符串替换 | 最大 8 MiB；默认只允许唯一匹配 |
| `ls` | 列出文件和目录 | 默认深度 1、1000 条，最多 5000 条 |
| `glob` | 按 glob 模式匹配文件 | 最多扫描 5000 个文件、返回 1000 条 |
| `grep` | 用 Java 正则搜索 UTF-8 内容 | 跳过大于 2 MiB 的文件和常见构建目录 |
| `publish_file` | 上传最终文件并返回 URL | 仅配置 `FilePublisher` 后注册 |

使用远程 Runtime 时，不要再混入另一套直接操作宿主机的同名 Shell 或文件工具，否则模型可能绕过 Sandbox。

## 常见接入场景

### Skill 需要环境变量或初始化依赖

不要把 API Key 写入 `SKILL.md` 或脚本。使用 `SkillRuntimeConfig` 注入环境变量，并通过 bootstrap 执行一次性
初始化：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("REPORT_API_TOKEN", token)
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "report-generator")
    .skillRuntimeConfig("report-generator", config)
    .runtime(runtime)
    .buildTools();
```

详细的环境合并、超时和重试规则见 [Skill Runtime Config](./skill-runtime-config)。

### 多轮对话需要保留文件

为同一个业务会话使用稳定的 `conversationId`。后续请求重新创建 Runtime 后，可以继续访问先前生成的文件。
不同 Runtime 的恢复语义并不相同，详见 [Skill Runtime Workspace](./skill-runtime-workspace)。

`conversationId` 只提供会话到工作目录的稳定映射，不会自动解决同一会话的并发写问题。业务层应避免多个请求
同时编辑同一个文件。

### 生产环境执行不可信 Skill

Local Runtime 直接继承 Java 进程用户的宿主机权限，即使启用了 Workspace，Shell 仍可能访问其他绝对路径。
第三方 Skill、用户上传脚本和不可信输入应使用独立 Sandbox，并在底层配置非 root 用户、CPU、内存、磁盘、
超时和出站网络策略。

### Skill 需要在线安装和版本回滚

开发阶段可以直接加载目录。生产环境需要上传、审批、灰度和回滚时，先通过 `SkillArtifactStore` 保存确定版本，
再用 `addSkillArtifact(...)` 加载，详见 [Skill Artifact Store](./artifact-store)。

### 任务需要把文件发给用户

Runtime 内路径不是用户可访问地址。固定工作流可以由业务代码下载文件；开放式对话可以配置 `FilePublisher`，
让模型通过 `publish_file` 返回 URL，详见 [Skill 产物](./files)。

## 选择 Runtime

快速开始使用 Local Runtime，是为了减少前置依赖，不代表它适合所有生产任务：

| Runtime | Maven artifactId | 适用场景 | 安全边界 |
| --- | --- | --- | --- |
| Local | `agents-flex-skills` | 开发、测试、可信内部任务 | 无容器隔离 |
| OpenSandbox | `agents-flex-skills-open-sandbox` | 按任务或会话创建 Sandbox | 取决于 OpenSandbox 和容器配置 |
| AIO Sandbox | `agents-flex-skills-aio-sandbox` | 连接已有长期运行的 AIO 服务 | 取决于 AIO 部署和租户划分 |

OpenSandbox 模块会传递 OpenSandbox Kotlin/Java SDK；如果应用的依赖管理强制降级 Kotlin 或 OkHttp，需要检查
最终依赖树。AIO 适配器直接调用 HTTP API，不要求安装官方 Python 或 TypeScript SDK。

选择和切换方式见 [Skill Runtime](./runtime)，完整部署步骤见 [OpenSandbox](./open-sandbox) 与
[AIO Sandbox](./aio-sandbox)。

## 常见问题

### 为什么模型没有选择我的 Skill？

先检查 `description` 是否明确包含任务能力和触发场景，再确认 Skill 已被当前 Builder 加载。只写
“Useful helper”之类宽泛描述，模型很难判断什么时候应该使用。

### Skill 目录为什么没有被发现？

入口文件名必须严格为 `SKILL.md`。同时检查路径是否为 Java 应用可读的绝对路径、front matter 是否包含单行
`name` 和 `description`，以及是否存在重复名称。

### 创建 SkillsTool 后，Skill 会立即执行吗？

不会。`buildTools()` 会发现并准备 Skill，也可能执行已配置的 bootstrap；实际业务步骤只有在模型选择 Skill
并调用相关工具后才会运行。

### Local Runtime 配置 Workspace 后是否已经安全隔离？

没有。Workspace 限制框架文件 API 的路径，并避免修改原始 Skill，但 Local Shell 仍拥有 Java 进程用户权限。
处理不可信内容时应切换到独立 Sandbox。

### 可以同时加载本地目录和已安装 Artifact 吗？

可以。Builder 支持组合 `addSkillsDirectory(...)` 与 `addSkillArtifact(...)`。应用仍需保证名称不冲突，并只向
当前 Agent 暴露有权限使用的 Skill。

## 下一步

- [理解 Skills 模块组成](./overview)
- [开发和校验 Skill](./skill-package)
- [深入了解 Skill Runtime](./runtime)
- [配置 Runtime 环境与 bootstrap](./skill-runtime-config)
- [配置持续会话 Workspace](./skill-runtime-workspace)
- [安装和分发 Skill Artifact](./artifact-store)
- [交付 Skill 生成的文件](./files)
- [运行仓库完整示例](./demo)

</div>
