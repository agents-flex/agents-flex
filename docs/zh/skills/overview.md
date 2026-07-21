<div v-pre>

# Skills 模块概述

## 什么是 Agent Skills

[Agent Skills](https://agentskills.io/home) 是一种为 AI Agent 扩展专业知识和工作流程的轻量级开放格式。
它最初由 Anthropic 开发，随后作为开放标准发布，并由越来越多的 Agent 产品共同采用和维护。

按照[官方规范](https://agentskills.io/specification)，一个 Skill 是一个至少包含 `SKILL.md` 的目录。
`SKILL.md` 使用 YAML front matter 声明 `name`、`description` 等元数据，并使用 Markdown 编写任务说明；
目录还可以包含 `scripts/`、`references/`、`assets/` 以及完成任务需要的其他文件。

Skill 将流程知识、领域经验和配套资源组织成可移植、可版本化的目录。兼容 Agent 可以先读取所有 Skill
的名称和描述，在任务匹配时再加载完整说明，随后按需执行脚本或读取参考资料。这种渐进式披露方式让
Agent 可以同时发现大量 Skill，而不必在会话开始时把全部内容放入上下文。

Agent Skills 是独立于 Agents-Flex 的开放标准。规范、参考实现和讨论可以在
[Agent Skills GitHub 仓库](https://github.com/agentskills/agentskills)中查看。

## Agents-Flex 如何支持 Agent Skills

Agents-Flex 负责发现和加载 Skill 目录，将 Skill 元数据及完整说明按需提供给模型，并为模型组合
Shell、文件读写、搜索和产物发布等工具。Skill 内容仍然遵循 Agent Skills 的目录格式，不需要绑定到
某个特定模型或 Sandbox 实现。

`SkillRuntime` 定义 Skill 执行时使用的命令环境和文件系统。同一套 Skill 可以在本机、容器或远程
Sandbox 中执行；切换 Runtime 不需要修改 Skill 内容，也不需要让模型学习不同的文件 API。

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

### SkillArtifactStore

`SkillArtifactStore` 是 Agents-Flex 提供的安装与存储抽象，不属于 Agent Skills 格式规范。它解决的是
Skill 已经编写完成后，如何在单体或分布式应用中安装、保存、删除并提供给执行节点的问题：

- `install()`：校验并持久化一个确定版本的 Skill 安装包；
- `materialize()`：把指定 Artifact 转换为当前节点可读取、包含 `SKILL.md` 的稳定本地目录；
- `delete()`：幂等删除不再使用的 Artifact；
- 文件系统实现适用于单体部署；OSS、COS、OBS、TOS 和 S3 实现适用于共享对象存储及分布式部署；
- 远程实现负责摘要校验、安全解压和节点缓存，`SkillsTool` 与 `SkillRuntime` 不需要感知远程存储来源。

`SkillArtifactStore` 管理安装包及其本地物化，`SkillRuntime` 管理执行环境。两者职责独立：Artifact
可以先从对象存储物化到应用节点，再由 Local、OpenSandbox 或 AIO Sandbox Runtime 准备和执行。

具体安装方式和对象存储实现见 [Skill Artifact Store](./artifact-store)。

## 模块组成

```text
agents-flex-skills
├── Skill / Skills                       Skill 模型与发现加载
├── SkillsTool                           Skill 工具和 Runtime 工具组装
├── artifact/
│   ├── SkillArtifact                    已安装 Skill 的确定版本引用
│   ├── SkillArtifactStore               Artifact 存储与本地物化接口
│   ├── SkillInstallRequest / SkillPackage 存储无关的安装输入
│   └── FileSystemSkillArtifactStore     单体部署的文件系统实现
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

agents-flex-skills-artifact
├── agents-flex-skills-artifact-core      通用对象存储 Artifact Store 与节点缓存
├── agents-flex-skills-artifact-aliyun-oss       阿里云 OSS SDK V2 适配器
├── agents-flex-skills-artifact-tencent-cos      腾讯云 COS 适配器
├── agents-flex-skills-artifact-huawei-obs       华为云 OBS 适配器
├── agents-flex-skills-artifact-volcengine-tos   火山引擎 TOS 适配器
└── agents-flex-skills-artifact-s3                AWS S3 与 S3-compatible 适配器
```

| Maven 模块 | 用途 | 是否创建 Sandbox |
| --- | --- | --- |
| `agents-flex-skills` | 核心 API、工具和本地 Runtime | 否 |
| `agents-flex-skills-artifact-core` | 通用对象存储接口、摘要校验与节点缓存 | 否 |
| `agents-flex-skills-artifact-aliyun-oss` | 在阿里云 OSS 持久化 Skill ZIP，并缓存到当前节点 | 否 |
| `agents-flex-skills-artifact-tencent-cos` | 在腾讯云 COS 持久化 Skill ZIP，并缓存到当前节点 | 否 |
| `agents-flex-skills-artifact-huawei-obs` | 在华为云 OBS 持久化 Skill ZIP，并缓存到当前节点 | 否 |
| `agents-flex-skills-artifact-volcengine-tos` | 在火山引擎 TOS 持久化 Skill ZIP，并缓存到当前节点 | 否 |
| `agents-flex-skills-artifact-s3` | 在 AWS S3 或 S3-compatible 存储中持久化 Skill ZIP | 否 |
| `agents-flex-skills-open-sandbox` | 连接 OpenSandbox Server，按需创建 Sandbox 实例 | 是 |
| `agents-flex-skills-aio-sandbox` | 连接已经运行的 AIO Sandbox 服务 | 否 |

## 下一步

- [了解 Skill 目录结构](./skill-package)
- [使用 LocalSkillRuntime 快速开始](./getting-started)
- [选择和配置 Skill Runtime](./runtime)
- [安装和分发 Skill Artifact](./artifact-store)

</div>
