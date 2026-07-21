<div v-pre>

# Skills 快速开始

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
## 下一步

- [从已安装的 Artifact 加载](./artifact-store)
- [配置 OpenSandbox](./open-sandbox)
- [配置 AIO Sandbox](./aio-sandbox)

</div>

