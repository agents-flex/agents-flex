<div v-pre>

# 自定义 SkillRuntime

接入其他 Sandbox 时实现 `SkillRuntime` 和 `SkillRuntimeFileSystem`：

```java
public final class MySandboxRuntime implements SkillRuntime {
    @Override
    public String getName() {
        return "my-sandbox";
    }

    @Override
    public List<Skill> prepare(SkillPreparationRequest request) {
        // 1. 合并 request 中每个 SkillRuntimeConfig 的 environment
        // 2. 上传未命中缓存的 Skill
        // 3. 在远端 Skill 根目录执行 bootstrapCommands
        // 4. bootstrap 成功后再记录缓存并返回新 Skill
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return "/workspace/skills";
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        // 映射 command、workingDirectory、environment 和 timeoutMillis
    }

    @Override
    public void close() {
        // 只释放当前 Runtime 真正拥有的资源
    }
}
```

实现检查清单：

- `prepare` 支持多个 Skill，并保持数量和顺序；
- 同一个 Skill 在同一 Runtime 内重复准备时应幂等；
- Runtime 配置的环境变量必须注入 bootstrap 和后续每次 `execute`，不能依赖一次性 `export`；
- bootstrap 仅在实际上传后执行，Local Runtime 则在首次准备时执行；
- bootstrap 非零退出或超时时不能写入成功缓存；
- 自定义 Runtime 必须处理 `SkillPreparationRequest` 中的配置，不能静默忽略；
- 远程路径必须使用目标系统格式；
- Shell 参数和路径必须正确转义，不能拼接未验证输入；
- 超时后主动终止远端命令；
- stdout/stderr 和退出码映射清晰；
- 文本读取有大小上限；
- 二进制下载使用流，不把大型文件全部加载到内存；
- 下载失败不留下伪装成完整文件的本地产物；
- `close` 幂等，并明确是否拥有远端实例生命周期；
- 上传默认排除敏感文件且不跟随符号链接；
- 为网络失败、404、非零退出码、超时和二进制文件编写测试。

`SkillRuntimeConfig` 通过 Skill 名称绑定：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("TOOL_HOME", "/workspace/.tools")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

List<Tool> tools = SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "demo-skill")
    .skillRuntimeConfig("demo-skill", config)
    .runtime(new MySandboxRuntime())
    .buildTools();
```

配置引用不存在的 Skill 名称会在构建工具时抛出 `IllegalArgumentException`。命令的工作目录固定为准备后
Skill 的 `basePath`，所以配置中应使用相对于 Skill 根目录的脚本路径。

</div>
