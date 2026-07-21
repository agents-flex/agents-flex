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
    public List<Skill> prepare(List<Skill> skills) {
        // 1. 上传每个 Skill 目录
        // 2. 返回数量和顺序一致、basePath 已改为远端路径的新列表
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

</div>
