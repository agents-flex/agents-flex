<div v-pre>

# Skill 目录与开发

## 一个 Skill 由什么组成

按照 [Agent Skills 规范](https://agentskills.io/specification)，Skill 是一个至少包含 `SKILL.md` 的目录。
目录名称必须与 `SKILL.md` 中的 `name` 一致。`scripts/`、`references/` 和 `assets/` 是常见的可选目录，
也可以根据任务需要添加其他文件或目录。

```text
skills/                            Skill 根目录位置由应用或 Agent 客户端决定
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

`SKILL.md` 由 YAML front matter 和 Markdown 正文组成：

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

规范字段：

| 字段 | 是否必需 | 说明 |
| --- | --- | --- |
| `name` | 是 | 1-64 个字符，只能使用小写字母、数字和连字符，并且必须与目录名一致 |
| `description` | 是 | 1-1024 个字符，应同时说明 Skill 做什么以及何时使用 |
| `license` | 否 | 许可证名称，或指向 Skill 内许可证文件的引用 |
| `compatibility` | 否 | 最多 500 个字符，用于声明目标产品、系统依赖或网络要求 |
| `metadata` | 否 | 扩展元数据键值映射 |
| `allowed-tools` | 否 | 预先批准的工具列表；该字段仍是实验性能力，各 Agent 的支持程度可能不同 |

`name` 不能以连字符开头或结尾，也不能包含连续连字符。`description` 不应只写宽泛的能力名称，应该包含
有助于 Agent 判断触发时机的任务关键词。

> Agents-Flex 当前的 `MarkdownParser` 只解析顶层的单行 `key: value`，支持无引号、单引号或双引号值，
> 尚不是完整 YAML 解析器。因此嵌套的 `metadata`、数组和块文本目前不会按完整 YAML 语义解析；
> `allowed-tools` 也只会作为元数据提供给模型，不会自动限制 Runtime 工具权限。

Markdown 正文用于编写任务步骤、输入输出示例和常见边界情况。Agent 激活 Skill 后会读取整份正文，
官方规范建议主 `SKILL.md` 保持在 500 行以内，并把详细资料拆到 `references/`。

### scripts

`scripts/` 适合放确定性、可复用且不应完全交给模型临时编写的程序，例如：

- 生成 PDF、PPTX、Excel 或图片；
- 代码格式化、构建和测试；
- 数据清洗和格式转换；
- 产物结构与元数据验证。

脚本不会因为放入目录就自动执行。`SKILL.md` 应说明运行命令、依赖、输入、输出和验收条件。脚本语言
和执行方式由 Agent 实现决定；在 Agents-Flex 中，模型通过 Runtime 的 `Bash` 工具执行脚本。

### references 与 assets

`references/` 放 Agent 按需读取的补充文档；`assets/` 放模板、图片、字体、数据文件等静态资源，通常由
脚本或任务流程直接使用。在 Agents-Flex 中，模型可以使用 Read/Grep 读取参考资料。大型资料不应全部
复制进 `SKILL.md`，否则会失去渐进式披露的优势。

## 渐进式披露

Agent Skills 规范采用三个层次的渐进式披露：启动时加载所有 Skill 的 `name` 和 `description`；匹配任务
后加载完整 `SKILL.md`；脚本、参考资料和资源只在执行任务确有需要时读取。

在 Agents-Flex 中，对应流程如下：

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

这种方式避免在会话开始时把所有脚本和参考资料塞入上下文。模型先知道“有哪些能力”，真正选中 Skill
后才加载“如何完成任务”。

## 校验 Skill

Agent Skills 官方仓库提供 `skills-ref` 参考工具，可以检查 front matter 和命名约束：

```bash
skills-ref validate ./skills/pptx
```

规范详情和参考实现见 [Agent Skills Specification](https://agentskills.io/specification) 与
[agentskills/agentskills](https://github.com/agentskills/agentskills)。

## 下一步

- [快速开始](./getting-started)
- [Skill Runtime](./runtime)

</div>
