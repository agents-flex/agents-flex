# Skills 开发文档


## 1. 概述

### 1.1 什么是 Skills

AgentsFlex Skills 是一套**基于文件系统的模块化能力扩展系统**，遵循 Agent Skills 行业标准。它允许开发者将重复性、专业化的工作流程封装为可复用的技能包，使 AI Agent 能够按需加载、精准调用外部能力。

```
┌─────────────────────────────────────────┐
│              Agent Core                 │
├─────────────────────────────────────────┤
│  ┌─────────┐ ┌─────────┐ ┌─────────┐    │
│  │  Shell  │ │  Grep   │ │  Glob   │    │  ← 内置工具
│  └─────────┘ └─────────┘ └─────────┘    │
│  ┌─────────────────────────────┐        │
│  │   .claude/skills/           │        │
│  │   ├── pdf_generator/        │        │  ← 自定义 Skills
│  │   │   ├── SKILL.md          │        │
│  │   │   ├── scripts/          │        │
│  │   │   └── assets/           │        │
│  │   └── code_review/          │        │
│  └─────────────────────────────┘        │
└─────────────────────────────────────────┘
```

### 1.2 核心价值

| 特性 | 说明 | 收益 |
|------|------|------|
| 🎯 **渐进式披露** | 元数据→指令→资源，按需加载 | 节省 Token，提升注意力 |
| 🔧 **模块化封装** | 每个 Skill 独立目录，职责单一 | 易维护、易复用、易测试 |
| 📦 **文件系统驱动** | 基于 `.claude/skills` 目录自动发现 | 零配置接入，开箱即用 |
| 🔒 **安全沙箱** | 路径校验、超时控制、命令白名单 | 企业级安全合规 |
| 🌐 **标准兼容** | 遵循 Agent Skills 社区规范 | 与 Cursor/Codex/OpenCode 等生态互通 |

### 1.3 适用场景

```java
// ✅ 推荐场景
- 代码仓库操作（git 工作流、代码审查）
- 文件批量处理（格式转换、内容提取）
- 本地知识检索（结合 Grep/Glob 工具）
- 自动化脚本执行（构建、部署、测试）
- 专业领域 SOP 封装（文档生成、数据分析）

// ❌ 不推荐场景
- 高频简单交互（直接使用 Prompt 更高效）
- 跨网络远程 API 调用（建议使用 MCP 协议）
- 实时流式数据处理（建议使用专用 Stream 工具）
```


## 2. 架构和概念

### 2.1 架构设计

```
User Request
     │
     ▼
┌─────────────────┐
│  MemoryPrompt   │  ← 消息上下文管理
└─────────────────┘
     │
     ▼
┌─────────────────┐
│  UserMessage    │  ← 携带可用 Tools/Skills
│  + SkillsTool   │
│  + CommonTools  │
└─────────────────┘
     │
     ▼
┌─────────────────┐
│  OpenAIChatModel│  ← LLM 推理 & 工具决策
│  (GiteeAI等)    │
└─────────────────┘
     │
     ▼
┌─────────────────┐
│  Tool Execution │  ← 执行 Shell/Grep/Glob/FileSystem
└─────────────────┘
     │
     ▼
┌─────────────────┐
│  ToolMessage    │  ← 工具结果回传，继续对话
└─────────────────┘
```

### 2.2 关键组件

#### 2.2.1 SkillsTool

```java
public class SkillsTool {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private List<Skill> skills = new ArrayList<>();

        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        protected Builder() {

        }

        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }


        public Builder addSkillsDirectory(String skillsRootDirectory) {
            this.addSkillsDirectories(Collections.singletonList(skillsRootDirectory));
            return this;
        }

        public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
            for (String skillsRootDirectory : skillsRootDirectories) {
                this.skills.addAll(Skills.loadDirectory(skillsRootDirectory));
            }
            return this;
        }

        public Tool build() {

            String skillsXml = this.skills.stream().map(s -> s.toXml()).collect(Collectors.joining("\n"));
            return Tool.builder()
                .name("Skill")
                .description(String.format(this.toolDescriptionTemplate, skillsXml))
                .addParameter(
                    Parameter.builder()
                        .name("command")
                        .type("string")
                        .description("The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"").build()
                )
                .function(stringStringMap -> {
                    String command = (String) stringStringMap.get("command");
                    Skill skill = null;
                    for (Skill s : skills) {
                        if (s.name().equals(command)) {
                            skill = s;
                            break;
                        }
                    }

                    if (skill != null) {
                        return String.format("Base directory for this skill: %s\n\n%s", skill.getBasePath(), skill.getContent());
                    }

                    return "Skill not found: " + command;
                }).build();

        }
    }
}
```

#### 2.2.2 CommonTools

```java
/**
 * 内置通用工具集合，提供开箱即用的基础能力
 */
public class CommonTools {

    /**
     * 获取所有内置工具实例
     * @return Tool 列表
     */
    public static List<Tool> getAllCommonsTools() {
        List<Tool> tools = new ArrayList<>();
        tools.addAll(ToolScanner.scan(GrepTool.builder().build()));
        tools.addAll(ToolScanner.scan(GlobTool.builder().build()));
        tools.addAll(ToolScanner.scan(FileSystemTools.builder().build()));
        tools.addAll(ToolScanner.scan(ShellTools.builder().build()));
        return tools;
    }
}
```

### 2.3 渐进式披露机制

```
┌─────────────────────────────────────────┐
│  Stage 1: Metadata Loading (启动时)      │
│  • 仅加载 Skills 名称 + 简短描述          │
│  • 消耗: ~100 Token/Skill                │
│  • 作用: 让 AI 知道"有哪些能力可用"      │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  Stage 2: Instruction Loading (触发时)   │
│  • 用户请求匹配到具体 Skill              │
│  • 加载 SKILL.md 完整指令                │
│  • 消耗: ~1-3K Token (按需)              │
│  • 作用: 让 AI 知道"这个能力怎么用"      │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│  Stage 3: Runtime Resources (执行时)     │
│  • 加载 reference/scripts/assets         │
│  • 脚本代码不进入 Prompt，直接执行        │
│  • 消耗: 0 Token (脚本) + 执行结果        │
│  • 作用: 让 AI"执行具体任务"             │
└─────────────────────────────────────────┘
```


## 3. 快速开始

### 3.1 环境准备

```bash
# 1. JDK 版本要求
java -version  # 推荐 Java 17+ (兼容 Java 8)

# 2. 配置 API Key (以 GiteeAI 为例)
export GITEE_APIKEY="your-api-key-here"

# 3. Maven 依赖
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.0.1</version>
</dependency>
```

### 3.2 最小可运行示例

```java
package com.agentsflex.skills.demo;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.llm.openai.OpenAIChatConfig;
import com.agentsflex.llm.openai.OpenAIChatModel;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.tool.commons.CommonTools;

public class SkillsQuickStart {

    public static void main(String[] args) throws InterruptedException {
        // 1. 配置聊天模型
        OpenAIChatModel chatModel = OpenAIChatConfig.builder()
            .provider("GiteeAI")
            .endpoint("https://ai.gitee.com")
            .requestPath("/v1/chat/completions")
            .apiKey(System.getenv("GITEE_APIKEY"))
            .model("Qwen3.5-35B-A3B")
            .thinkingEnabled(false)
            .buildModel();

        // 2. 创建提示词上下文
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage(
            "Always use the available skills to assist the user in their requests."
        );

        // 3. 创建用户消息并注册工具
        UserMessage userMessage = new UserMessage(
            "帮我把 'Hello world' 这个内容生成一个 PDF 文件。"
        );
        prompt.addMessage(userMessage);

        // 注册 SkillsTool (加载自定义技能)
        Tool skillsTool = SkillsTool.builder()
            .addSkillsDirectory("/path/to/.claude/skills")
            .build();
        prompt.addTool(skillsTool);

        // 注册内置通用工具
        prompt.addTools(CommonTools.getAllCommonsTools());


        // 4. 发起流式对话
        chatModel.chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                String content = StringUtil.hasText(response.getMessage().getContent())
                    ? response.getMessage().getContent()
                    : response.getMessage().getReasoningContent();

                System.out.println(">>>>> " + content);

                // 处理工具调用
                if (response.getMessage().isFinalDelta()
                        && response.getMessage().getToolCalls() != null) {

                    System.out.println("----------");
                    prompt.addMessage(response.getMessage());

                    for (ToolCall toolCall : response.getMessage().getToolCalls()) {
                        System.out.println(">>>>> " + toolCall.getName()
                            + ": " + toolCall.getArguments());

                        // 执行工具并获取结果
                        List<ToolMessage> toolMessages =
                            response.executeToolCallsAndGetToolMessages();
                        prompt.addMessages(toolMessages);
                    }
                    // 递归继续对话
                    chatModel.chatStream(prompt, this);

                } else if (response.getMessage().isFinalDelta()
                        && !response.getMessage().hasToolCalls()) {
                    System.out.println(">>>>>>> 结束 <<<<<<<<<");
                }
            }
        });

        // 等待异步执行完成
        Thread.sleep(200000L);
    }
}
```

### 3.3 运行结果

```
>>>>> 我将帮您创建一个 PDF 文件，首先确认 pandoc 工具是否可用...
----------
>>>>> Bash: which pandoc
>>>>> /usr/local/bin/pandoc
----------
>>>>> 工具可用，现在执行转换命令...
>>>>> Bash: echo "Hello world" | pandoc -o output.pdf
>>>>> 文件已创建成功！output.pdf (12KB)
>>>>>>> 结束 <<<<<<<<<
```


## 4. 内置工具参考

### 4.1 ShellTools - 系统命令执行

#### 4.1.1 Bash 命令

```java
@ToolDef(name = "Bash", description = "Executes a given bash command...")
public String bash(
    @ToolParam(name = "command") String command,           // 必需：要执行的命令
    @ToolParam(name = "timeout", required = false)
        Long timeout,                                       // 可选：超时(ms)，最大600000
    @ToolParam(name = "description", required = false)
        String description,                                 // 可选：5-10字简短描述
    @ToolParam(name = "runInBackground", required = false)
        Boolean runInBackground                             // 可选：是否后台执行
)
```

**使用示例：**

```java
// 前台执行（默认）
Bash(command = "git status", description = "Check git status")

// 带超时控制
Bash(command = "npm install", timeout = 300000L, description = "Install dependencies")

// 后台执行 + 后续获取输出
String result = Bash(command = "python server.py",
                     runInBackground = true,
                     description = "Start web server");
// 返回: bash_id: shell_123456...
// 后续用 BashOutput(bash_id="shell_123456") 获取输出
```

**安全规范：**

| ✅ 推荐做法 | ❌ 禁止做法 |
|------------|----------|
| 路径含空格时用双引号包裹 | 直接拼接用户输入到命令 |
| 使用专用工具替代 find/grep/cat | 使用 `find`/`grep`/`cat` 等命令 |
| 创建文件前先 `ls` 验证父目录 | 执行 `rm -rf`/`git push --force` 等破坏性命令 |
| 设置合理 timeout 防止阻塞 | 执行交互式命令（如 `vim`/`git rebase -i`） |

#### 4.1.2 BashOutput - 获取后台输出

```java
@ToolDef(name = "BashOutput")
public String bashOutput(
    @ToolParam(name = "bash_id") String bash_id,    // 必需：后台进程ID
    @ToolParam(name = "filter", required = false)
        String filter                               // 可选：正则过滤输出行
)
```

#### 4.1.3 KillShell - 终止后台进程

```java
@ToolDef(name = "KillShell")
public String killShell(
    @ToolParam(name = "bash_id") String bash_id     // 必需：要终止的进程ID
)
```


### 4.2 GrepTool - 内容搜索

#### 4.2.1 基本用法

```java
@ToolDef(name = "Grep", description = "Pure Java grep implementation...")
public String grep(
    @ToolParam(name = "pattern") String pattern,                    // 必需：正则表达式
    @ToolParam(name = "path", required = false) String path,        // 可选：搜索路径
    @ToolParam(name = "glob", required = false) String glob,        // 可选：文件过滤模式
    @ToolParam(name = "type", required = false) String type,        // 可选：文件类型(java/js/py...)
    @ToolParam(name = "outputMode", required = false)
        OutputMode outputMode,                                      // 可选：输出模式
    @ToolParam(name = "context", required = false) Integer context, // 可选：上下文行数
    @ToolParam(name = "caseInsensitive", required = false)
        Boolean caseInsensitive,                                    // 可选：忽略大小写
    @ToolParam(name = "headLimit", required = false)
        Integer headLimit                                           // 可选：结果数量限制
)
```

#### 4.2.2 输出模式

```java
public enum OutputMode {
    files_with_matches,  // 默认：只显示匹配文件路径
    count,               // 显示每个文件的匹配数量
    content              // 显示匹配内容及上下文
}
```

#### 4.2.3 使用示例

```java
// 搜索 Java 文件中的错误日志
Grep(pattern = "log.*Error", type = "java", outputMode = OutputMode.content)

// 在指定目录搜索 TODO 标记
Grep(pattern = "TODO", path = "/src", glob = "*.java")

// 不区分大小写 + 限制结果数
Grep(pattern = "exception", caseInsensitive = true, headLimit = 50)

// 显示匹配行及前后3行上下文
Grep(pattern = "NullPointerException", context = 3, showLineNumbers = true)
```

#### 4.2.4 支持的文件类型

```
java, js, ts, py, rust, go, cpp, c, rb, php, cs,
xml, json, yaml, md, txt, sh, html, css, less, scss
```


### 4.3 GlobTool - 文件路径匹配

#### 4.3.1 基本用法

```java
@ToolDef(name = "Glob", description = "Fast file pattern matching...")
public String glob(
    @ToolParam(name = "pattern") String pattern,  // 必需：Glob 模式
    @ToolParam(name = "path", required = false)
        String path                               // 可选：搜索根目录
)
```

#### 4.3.2 使用示例

```java
// 查找所有 JavaScript 文件
Glob(pattern = "**/*.js")

// 在 src 目录查找 TypeScript 测试文件
Glob(pattern = "**/*.test.ts", path = "/src")

// 查找特定命名模式的配置文件
Glob(pattern = "**/config*.yaml")
```

#### 4.3.3 特性

- ✅ 结果按修改时间降序排列（最新优先）
- ✅ 自动忽略 `.git/`, `node_modules/`, `target/`, `build/` 等目录
- ✅ 支持 `**` 递归匹配和 `*`/`?` 通配符


### 4.4 FileSystemTools - 文件读写编辑

#### 4.4.1 Read - 读取文件

```java
@ToolDef(name = "Read")
public String read(
    @ToolParam(name = "filePath") String filePath,  // 必需：绝对路径
    @ToolParam(name = "offset", required = false)
        Integer offset,                             // 可选：起始行号(默认1)
    @ToolParam(name = "limit", required = false)
        Integer limit                               // 可选：读取行数(默认2000)
)
```

**特性：**
- 行号格式输出：`     1→public class Main {`
- 长行自动截断（>2000字符）
- 支持 PDF/图片/Jupyter Notebook 等多模态文件

#### 4.4.2 Write - 写入文件

```java
@ToolDef(name = "Write")
public String write(
    @ToolParam(name = "filePath") String filePath,  // 必需：绝对路径
    @ToolParam(name = "content") String content     // 必需：文件内容
)
```

**注意事项：**
- ⚠️ 写入已存在文件前**必须先 Read**（安全校验）
- ⚠️ 自动创建父目录（如果不存在）
- ⚠️ 不主动创建文档文件（除非用户明确要求）

#### 4.4.3 Edit - 精确编辑文件

```java
@ToolDef(name = "Edit")
public String edit(
    @ToolParam(name = "filePath") String filePath,      // 必需：绝对路径
    @ToolParam(name = "old_string") String old_string,  // 必需：要替换的原文
    @ToolParam(name = "new_string") String new_string,  // 必需：替换后的内容
    @ToolParam(name = "replace_all", required = false)
        Boolean replace_all                             // 可选：替换所有匹配项
)
```

**注意事项：**
- ⚠️ 编辑前**必须先 Read**（确保内容最新）
- ⚠️ `old_string` 必须在文件中**唯一**（或使用 `replace_all=true`）
- ⚠️ 保持缩进格式一致（Tab/空格）


## 5. 自定义 Skills 开发

### 5.1 Skill 目录结构

```
.clause/skills/
├── pdf_generator/              # Skill 名称（唯一标识）
│   ├── SKILL.md               # 核心指令文件（必需）
│   ├── reference/             # 参考文档（可选）
│   │   ├── analysis.md
│   │   └── creation.md
│   ├── scripts/               # 可执行脚本（可选）
│   │   └── generate.py
│   └── assets/                # 静态资源（可选）
│       └── template.html
└── code_review/
    ├── SKILL.md
    └── scripts/
        └── checkstyle.xml
```

### 5.2 SKILL.md 编写规范

```markdown
---
name: pdf_generator
description: Generate PDF files from text content using pandoc.
             Trigger when user asks to create/export/save as PDF.
version: 1.0
author: Your Name
---

## 目标
将用户提供的文本内容转换为 PDF 格式文件，支持基础样式定制。

## 使用步骤
1. 确认用户想要生成的 PDF 内容、文件名和输出路径
2. 检查 pandoc 工具是否可用: `which pandoc`
3. 如果内容包含 Markdown 语法，直接使用 pandoc 转换
4. 如果内容是纯文本，先包装为 Markdown 再转换
5. 执行转换命令: `pandoc -o {output_path} <(echo "{content}")`
6. 验证文件生成成功并返回文件路径

## 注意事项
- ❌ 不要直接执行 rm/delete 等删除命令
- ❌ 不要覆盖用户未授权的文件
- ⚠️ 如果 pandoc 不可用，提示用户安装或建议使用在线转换工具
- ⚠️ 文件名包含空格时必须用双引号包裹
- ✅ 生成成功后，建议用户用 Read 工具预览内容

## 参数说明
| 参数 | 类型 | 必需 | 说明 |
|------|------|------|------|
| content | string | ✅ | 要转换的文本内容 |
| output_path | string | ✅ | PDF 输出路径（绝对路径） |
| title | string | ❌ | PDF 标题（可选） |
```

### 5.3 加载自定义 Skills

```java
// 方式1: 单个技能目录
Tool skillsTool = SkillsTool.builder()
    .addSkillsDirectory("/your-path/skills")
    .build();

// 方式2: 多个技能目录
Tool skillsTool = SkillsTool.builder()
    .addSkillsDirectory("/project/skills")
    .addSkillsDirectory("/shared/team-skills")
    .build();

// 注册到 UserMessage
UserMessage userMessage = new UserMessage("用户请求...");
userMessage.addTool(skillsTool);
userMessage.addTools(CommonTools.getAllCommonsTools());
```

## 6. Skills 配置与管理

### 6.1 工具参数配置

| 工具 | 可配置参数 | 默认值 | 建议值 |
|------|-----------|--------|--------|
| GrepTool | maxOutputLength, maxDepth, maxLineLength, workingDirectory | 100000, 100, 10000, null | 根据项目规模调整 |
| GlobTool | maxDepth, maxResults, workingDirectory | 100, 1000, null | maxResults≤500 避免过载 |
| ShellTools | timeout(内部) | 120000ms | 长任务显式设置 timeout |
| FileSystemTools | - | - | - |

### 6.2 工作目录沙箱配置

```java
// 推荐：为工具设置工作目录，限制操作范围
Path workspace = Paths.get("/safe/project/root");

GrepTool grepTool = GrepTool.builder()
    .workingDirectory(workspace)
    .maxDepth(50)           // 限制遍历深度
    .maxOutputLength(50000) // 限制输出长度
    .build();

GlobTool globTool = GlobTool.builder()
    .workingDirectory(workspace)
    .maxResults(500)        // 限制结果数量
    .build();

// ShellTools 通过命令中的路径隐式控制，建议在 Prompt 中强调使用绝对路径
```

### 6.3 工具组合策略

```java
// 策略1: 全量加载（适合简单场景）
List<Tool> tools = CommonTools.getAllCommonsTools();

// 策略2: 按需加载（推荐，节省 Token）
List<Tool> tools = new ArrayList<>();
if (needFileSearch) {
    tools.addAll(ToolScanner.scan(GrepTool.builder().build()));
    tools.addAll(ToolScanner.scan(GlobTool.builder().build()));
}
if (needFileEdit) {
    tools.addAll(ToolScanner.scan(FileSystemTools.builder().build()));
}
if (needSystemCommand) {
    tools.addAll(ToolScanner.scan(ShellTools.builder().build()));
}

// 策略3: 自定义工具 + 内置工具混合
tools.add(SkillsTool.builder().addSkillsDirectory("/skills").build());
tools.addAll(CommonTools.getAllCommonsTools());
```


## 7. 安全与最佳实践

### 7.1 安全防护清单

| 风险类型 | 防护措施 | 代码示例 |
|---------|---------|---------|
| 路径遍历攻击 | 使用 `workingDirectory` + 绝对路径校验 | `Paths.get(path).normalize().startsWith(workspace)` |
| 命令注入 | 避免字符串拼接，使用参数化命令 | `ProcessBuilder(commandParts)` 而非 `Runtime.exec(cmd)` |
| 资源耗尽 | 设置 timeout/maxDepth/maxResults | `GrepTool.builder().maxDepth(50).build()` |
| 敏感文件泄露 | 忽略 `.git/`/`.env`/`credentials*` 等 | `isIgnoredPath()` 内置白名单 |
| 破坏性操作 | Prompt 中明确禁止 + 工具层校验 | Bash 工具描述中声明安全规范 |

### 7.2 性能优化建议

```java
// ✅ 并行执行独立任务（单次消息多 ToolCall）
// 用户请求: "检查 git 状态并查看最近提交"
// AI 可并行调用:
Bash(command = "git status", description = "Check status")
Bash(command = "git log -3", description = "Show recent commits")

// ✅ 串行执行依赖任务（命令链）
// 用户请求: "提交代码并推送"
Bash(command = "git add . && git commit -m 'msg' && git push",
     description = "Commit and push changes")

// ✅ 精准搜索减少扫描范围
// ❌ 低效: Grep(pattern="TODO", path="/")  // 全文件系统
// ✅ 高效: Grep(pattern="TODO", path="/src", type="java", headLimit=100)

// ✅ 大文件分块读取
Read(filePath = "/large.log", offset = 1000, limit = 500)  // 读取第1000-1499行
```


### 7.3 错误处理与日志

```java
StreamResponseListener listener = new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        try {
            String content = StringUtil.hasText(response.getMessage().getContent())
                ? response.getMessage().getContent()
                : response.getMessage().getReasoningContent();

            // 输出 AI 响应
            System.out.println(">>>>> " + content);

            // 处理工具调用
            if (response.getMessage().isFinalDelta()
                    && response.getMessage().getToolCalls() != null) {

                System.out.println("----------");
                prompt.addMessage(response.getMessage());

                for (ToolCall toolCall : response.getMessage().getToolCalls()) {
                    System.out.println(">>>>> " + toolCall.getName()
                        + ": " + toolCall.getArguments());

                    // 执行工具调用（内置异常处理）
                    List<ToolMessage> toolMessages =
                        response.executeToolCallsAndGetToolMessages();

                    // 记录工具执行结果（可用于审计）
                    for (ToolMessage msg : toolMessages) {
                        log.debug("Tool result: {}", msg.getContent());
                    }

                    prompt.addMessages(toolMessages);
                }
                // 递归继续对话
                chatModel.chatStream(prompt, this);

            } else if (response.getMessage().isFinalDelta()
                    && !response.getMessage().hasToolCalls()) {
                System.out.println(">>>>>>> 结束 <<<<<<<<<");
            }

        } catch (Exception e) {
            // 统一异常处理，避免中断对话流
            log.error("Stream processing error", e);
            System.out.println("Error: " + e.getMessage());
        }
    }
};
```


## 8. 常见问题

### 8.1 FAQ

**Q1: Skills 工具调用后没有响应？**
```
排查步骤:
1. 确认 SkillsTool 已正确添加到 UserMessage: userMessage.addTool(skillsTool)
2. 检查技能目录路径是否正确且 SKILL.md 存在
3. 确认 StreamResponseListener 已正确实现 onMessage 方法
4. 查看日志: OpenAIChatConfig.builder().logEnabled(true) 开启详细日志
5. 验证 API Key 有效且模型支持 function calling
```

**Q2: 如何限制工具的操作范围？**
```java
// 方案1: 使用 workingDirectory 限制文件系统操作
GrepTool.builder()
    .workingDirectory("/project/src")  // 仅允许在此目录下搜索
    .maxDepth(20)                       // 限制递归深度
    .build();

// 方案2: 在 SystemMessage 中声明约束
prompt.setSystemMessage(
    "You can only operate files under /project directory. " +
    "Never access /etc, /home, or other system paths."
);

// 方案3: 自定义 Tool 中增加路径校验
if (!path.startsWith("/project")) {
    return "Error: Access denied for path: " + path;
}
```

**Q3: 后台任务如何监控和管理？**
```
流程:
1. 启动后台任务:
   Bash(command="python train.py", runInBackground=true)
   → 返回: bash_id: shell_1709123456

2. 定期获取输出:
   BashOutput(bash_id="shell_1709123456")
   → 返回: 新增的 stdout/stderr 内容

3. 按需终止任务:
   KillShell(bash_id="shell_1709123456")

💡 建议: 在 Prompt 中提醒 AI 对长任务设置 timeout，并定期用 BashOutput 检查进度
```

**Q4: 如何处理大文件或大量搜索结果？**
```java
// 方案1: 使用 offset/limit 分页读取
Read(filePath="/large.log", offset=1, limit=2000)      // 第1页
Read(filePath="/large.log", offset=2001, limit=2000)   // 第2页

// 方案2: 使用 headLimit 限制搜索结果
Grep(pattern="ERROR", headLimit=100)  // 最多返回100个匹配

// 方案3: 组合过滤缩小范围
Grep(pattern="ERROR", path="/logs", glob="*.log.2024-*", type="txt")
```

**Q5: 自定义 Skill 不生效？**
```
检查清单:
□ 技能目录是否添加到 SkillsTool: .addSkillsDirectory("/path")
□ SKILL.md 是否包含正确的 YAML frontmatter (name/description)
□ description 是否足够具体，能被用户请求匹配到
□ 技能目录权限是否可读 (chmod -R 755)
□ 如果使用 scripts，脚本是否有执行权限 (chmod +x script.py)
```

### 8.2 调试技巧

```java
// 1. 开启框架日志
OpenAIChatConfig.builder()
    .logEnabled(true)   // 输出请求/响应详情
    .buildModel();

// 2. 打印工具调用链
for (ToolCall toolCall : response.getMessage().getToolCalls()) {
    System.out.println("Tool: " + toolCall.getName());
    System.out.println("Args: " + toolCall.getArguments());
    System.out.println("Result: " +
        response.executeToolCallsAndGetToolMessages().get(0).getContent());
}

// 3. 验证 Skills 加载
SkillsTool skillsTool = SkillsTool.builder()
    .addSkillsDirectory("/path/to/skills")
    .build();
// 可在 SkillsTool 内部添加日志: log.info("Loaded {} skills", skillList.size())

// 4. 单元测试自定义 Tool
@Test
public void testPdfGenerator() {
    PdfGeneratorTool tool = PdfGeneratorTool.builder().build();
    String result = tool.generate("Hello", "/tmp/test.pdf", null);
    assertTrue(result.contains("created successfully"));
}
```

### 8.3 版本兼容性

| 组件 | 最低版本 | 推荐版本 | 说明 |
|------|----------|----------|------|
| JDK | 8 | 17+ | Java 8 兼容，但推荐 17+ 使用新特性 |
| agents-flex-core | 2.0.0 | 2.x latest | Skills 功能从 2.0 开始支持 |
| OpenAI API | v1 | v1 | 需支持 function calling 的模型 |
| 模型推荐 | - | Qwen3.5-35B / GPT-4o | 复杂工具调用建议用大参数模型 |


## 9. 附录


### 工具参数速查表

| 工具 | 方法 | 必需参数 | 关键可选参数 |
|------|------|----------|-------------|
| ShellTools | bash | command | timeout, description, runInBackground |
| ShellTools | bashOutput | bash_id | filter |
| ShellTools | killShell | bash_id | - |
| GrepTool | grep | pattern | path, glob, type, outputMode, context, headLimit |
| GlobTool | glob | pattern | path |
| FileSystemTools | read | filePath | offset, limit |
| FileSystemTools | write | filePath, content | - |
| FileSystemTools | edit | filePath, old_string, new_string | replace_all |

