<div v-pre>

# Skill Runtime Config

## 概述

Agents-Flex 使用 `SkillRuntimeConfig` 配置一个 Skill 在 Runtime 中需要的环境变量和初始化命令。它解决的
是“Skill 被加载后如何准备执行环境”，例如注入 API 地址、扩展 `PATH`、安装依赖或生成本地缓存。

Skill Runtime Config 属于 Agents-Flex 执行层，不属于 [Agent Skills 规范](https://agentskills.io/specification)。
配置不会写入或修改 `SKILL.md`，也不会被当作 Skill 内容提供给模型。

三类配置的职责如下：

| 配置 | 负责内容 | 配置位置 |
| --- | --- | --- |
| `SKILL.md` front matter | Skill 的 `name`、`description` 等可发现元数据 | Skill 目录内 |
| `SkillRuntimeConfig` | 环境变量、bootstrap 初始化命令 | Java 应用代码 |
| Runtime Builder | Workspace、Sandbox 地址、认证和会话生命周期 | Java 应用代码 |

把运行凭证和环境差异留在应用侧，可以让同一份标准 Skill 在开发、测试和生产环境复用。

## 快速开始

创建 `SkillRuntimeConfig`，再按 `SKILL.md` 中声明的 Skill 名称绑定到 `SkillsTool.Builder`：

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.runtime.SkillRuntimeConfig;

import java.util.List;

SkillRuntimeConfig reportConfig = SkillRuntimeConfig.builder()
    .environment("REPORT_API_URL", "https://api.example.com")
    .environment("REPORT_API_TOKEN", reportApiToken)
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory("/opt/my-app/skills", "report-generator")
    .skillRuntimeConfig("report-generator", reportConfig)
    .runtime(runtime)
    .buildTools();

prompt.addTools(tools);
```

`build()` 和 `buildTools()` 都会在返回工具前调用 `runtime.prepare()`。因此，Skill 上传、环境合并和
bootstrap 都发生在构建工具阶段；配置或初始化失败时，工具不会以半准备状态注册给模型。

## 配置环境变量

可以逐项添加环境变量，也可以一次添加一个 Map：

```java
Map<String, String> environment = new LinkedHashMap<>();
environment.put("PYTHONUNBUFFERED", "1");
environment.put("HTTP_PROXY", proxyUrl);

SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment(environment)
    .environment("APP_ENV", "production")
    .build();
```

变量名必须匹配 `[A-Za-z_][A-Za-z0-9_]*`，变量值不能为 `null`。Builder 中后设置的同名变量覆盖之前的值。
构建后的 Map 不可修改。

Runtime 会先合并当前准备批次中所有 Skill Config 的环境变量，再执行任何 bootstrap。后续每次命令执行也会
重新注入合并后的环境变量，不依赖 `.bashrc`、`.profile` 或前一次 Shell 进程中的 `export`。

环境变量的优先级从低到高为：

1. Runtime 所在进程或 Sandbox 的基础环境；
2. `SkillRuntimeConfig.environment`；
3. 单次 `SkillExecutionRequest.environment`。

当多个 Skill Config 声明同名变量时，按照传给 `prepare()` 的 Skill 顺序合并，后面的 Skill 覆盖前面的值。
这些变量保存于整个 Runtime，而不是只在对应 Skill 的命令中生效。因此，共享一个 Runtime 的多个 Skill 应
避免使用含义不同但名称相同的变量。

## 配置 bootstrap

bootstrap 是 Skill 准备完成后、对模型开放工具前执行的初始化命令。它常用于：

- 安装或校验 Skill 所需依赖；
- 编译 Skill 自带的脚本；
- 创建缓存、模板索引或初始配置；
- 检查 Runtime 镜像中是否存在所需命令。

可以按顺序配置多条命令：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .bootstrapCommand("python --version")
    .bootstrapCommand("python -m pip install -r requirements.txt", 300_000L)
    .bootstrapCommand("python scripts/check.py", 30_000L)
    .build();
```

不指定超时时，单条 bootstrap 默认最多执行 120 秒。命令不能为空，超时时间必须大于 0。每条命令都以
Runtime 中准备后的 `Skill.basePath` 作为工作目录，并继承已合并的 Runtime 环境变量。

bootstrap 严格按配置顺序执行。任意命令超时或返回非零退出码，`prepare()` 会立即失败，后续命令不再执行，
当前 Skill 也不会写入准备成功缓存。修正环境后再次构建工具，Runtime 可以重新尝试准备。

如果多条命令必须共享 Shell 变量、当前目录切换或其他进程内状态，应把它们写进同一个脚本，再用一条
bootstrap 调用。每条 bootstrap 都是独立的命令执行请求。

## 为多个 Skill 配置

每个 Skill 按名称绑定自己的配置：

```java
SkillRuntimeConfig pdfConfig = SkillRuntimeConfig.builder()
    .environment("FONT_DIR", "/opt/fonts")
    .bootstrapCommand("bash scripts/check-fonts.sh")
    .build();

SkillRuntimeConfig sheetConfig = SkillRuntimeConfig.builder()
    .environment("CALC_LOCALE", "zh_CN")
    .bootstrapCommand("python scripts/warmup.py")
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory("/opt/my-app/skills", "pdf", "xlsx")
    .skillRuntimeConfig("pdf", pdfConfig)
    .skillRuntimeConfig("xlsx", sheetConfig)
    .runtime(runtime)
    .buildTools();
```

Skill 名称匹配区分大小写。名称会去除首尾空白，但必须与已加载 Skill 的 `name` 完全一致。配置引用未知
Skill、空名称或 `null` 配置时，构建会抛出 `IllegalArgumentException`。

同一个名称多次调用 `skillRuntimeConfig()` 时，后一次配置替换前一次配置，不会自动合并。需要组合配置时，
应先在同一个 `SkillRuntimeConfig.Builder` 中完成合并。

## 不同 Runtime 中的行为

`SkillRuntimeConfig` 的 API 对所有内置 Runtime 一致，但准备位置不同：

| Runtime | bootstrap 工作目录 | 环境变量注入位置 |
| --- | --- | --- |
| 无 Workspace 的 Local | 原始 Skill 目录 | 当前宿主机子进程 |
| Workspace 模式的 Local | 会话中的 Skill 副本 | 当前宿主机子进程 |
| OpenSandbox | 上传后的远端 Skill 目录 | OpenSandbox 命令进程 |
| AIO Sandbox | 上传后的远端 Skill 目录 | AIO Sandbox 命令进程 |

无 Workspace 的 Local Runtime 会直接在原始 Skill 目录执行 bootstrap，初始化产生的文件也会留在原目录。
Workspace 或远程 Runtime 则在复制、上传后的目录执行，不修改源 Skill。

同一个 Runtime 对象内，已成功准备的 Skill 不会重复执行 bootstrap。跨 Runtime 对象或恢复会话时的具体复用
语义取决于 Runtime，详见 [Skill Runtime](./runtime) 与 [Skill Runtime Workspace](./skill-runtime-workspace)。

## Secret 与配置管理

`SkillRuntimeConfig` 不区分普通环境变量与 Secret，也不负责加密、轮换或脱敏。Token、API Key 等敏感值应由
应用在运行时从 Vault、KMS、环境注入或短期凭证服务取得，再传入 Builder。

生产环境中应遵守以下约束：

- 不把 Secret 写入 `SKILL.md`、Skill 脚本、代码仓库或 Artifact；
- 不记录完整的 `SkillRuntimeConfig`、环境变量 Map 或命令展开结果；
- 避免在 bootstrap 中使用会把 Secret 输出到 stdout/stderr 的调试选项；
- 优先使用短期、最小权限凭证，并确保 Sandbox 的日志和进程查看权限受控；
- 不要依赖 Shell `export` 持久化凭证，后续命令由 Runtime 统一注入环境变量。

环境变量会进入 Runtime 的后续命令，直到 Runtime `close()` 清理内存配置。若不同租户不能共享凭证，就不能
共享同一个 Runtime 实例。

## 常见问题

### 配置了环境变量，但脚本中读取不到

确认命令由 `SkillsTool` 提供的 Runtime 工具执行，而不是另一个直接操作宿主机的 Shell Tool；同时确认变量名
合法，且配置绑定的是实际加载的 Skill 名称。

### 修改配置后 bootstrap 没有重新执行

已成功准备的 Skill 会在当前 Runtime 对象内缓存。配置发生变化时，应创建新的 Runtime，或在上层使用新的
会话和 Skill 版本策略，不要依赖重复调用 `buildTools()` 强制初始化。

### 能否把 bootstrap 写进 SKILL.md

可以在 Skill 指令中告诉模型运行某个脚本，但这与 bootstrap 语义不同：模型可能不选择该 Skill，也可能不按
预期时机执行。执行环境必须具备的初始化步骤应使用 `SkillRuntimeConfig`，并在工具暴露给模型之前完成。

## 下一步

- [Skill Runtime](./runtime)
- [Skills Workspace](./skill-runtime-workspace)
- [Local Runtime](./local-runtime)
- [故障排查与生产建议](./troubleshooting)

</div>
