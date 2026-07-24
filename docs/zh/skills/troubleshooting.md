<div v-pre>

# 故障排查与生产建议

### Skill not found

检查：

1. 根目录是否真实存在；
2. 文件名是否严格为 `SKILL.md`；
3. front matter 是否包含 `name`；
4. 模型调用名称是否与 `name` 完全一致；
5. `SkillsTool` 是否添加了正确目录。

### 远程路径仍然是本机路径

这通常说明只加载了 `Skills.loadDirectory()`，但没有使用远程 Runtime 执行
`prepare(SkillPreparationRequest)`。推荐统一通过
`SkillsTool.builder().runtime(runtime).buildTools()` 构建工具。

### OpenSandbox 无法连接

```bash
curl http://127.0.0.1:8080/health
```

然后检查：

- `OPEN_SANDBOX_DOMAIN` 是否只包含 SDK 需要的 domain/port；
- API Key 是否与 Server 配置一致；
- Docker daemon 是否运行；
- Sandbox 镜像能否拉取；
- `readyTimeout` 是否过短；
- Server 日志是否显示资源或网络策略错误。

### AIO 返回 Connection refused

```bash
docker ps --filter name=agentsflex-aio-sandbox
docker logs agentsflex-aio-sandbox
curl -I http://127.0.0.1:8080/v1/docs
```

如果映射到了 3000，`AIO_SANDBOX_BASE_URL` 也必须使用 3000。

### AIO 返回 401

- 服务启用了 `JWT_PUBLIC_KEY`，但 Runtime 没配置 `bearerToken`；
- JWT 不是 RS256 或签名私钥不匹配；
- JWT 已过期；
- Token 字符串错误地包含了 `Bearer ` 前缀，Runtime 会自动添加该前缀。

### 命令成功但找不到产物

- 模型输出路径和下载路径是否完全一致；
- 产物是否写入了当前会话目录或准备后的 Skill 目录；
- `SKILLS_OUTPUT_FILE` 是否是 Runtime 内绝对路径；
- 自定义 Prompt 是否设置了 `SKILLS_OUTPUT_FILE`；
- 生成程序是否在非零退出前留下了不完整文件。

### 依赖安装失败

Sandbox 可能没有公网访问权限，或者网络策略禁止访问包仓库。生产环境推荐把 Python、Node、LibreOffice、
字体和所需库预装进固定镜像，而不是运行时执行 `pip install` 或 `npm install`。

### Maven exec:java 完成后仍不退出

如果业务输出和文件下载都已完成，但 Maven 仍等待，检查模型 SDK、OkHttp 或 OpenTelemetry 是否留下
非 daemon 后台线程。应用服务应统一管理这些客户端生命周期；Demo 调试时也可以先关闭可观测导出。

## 生产环境建议

1. 默认选择远程 Sandbox，只有可信内部 Skill 才允许 Local Runtime；
2. 为每个任务或租户建立清晰的 Sandbox 生命周期；
3. 固定镜像版本和依赖版本，禁止未经验证的 `latest` 自动升级；
4. 使用非 root 用户、只读基础文件系统和最小 Linux capabilities；
5. 配置 CPU、内存、PID、磁盘和执行时间限制；
6. 默认拒绝出站网络，只开放任务需要的域名；
7. Server 端口只放在私网，通过 TLS 网关暴露；
8. OpenSandbox 启用 API Key，AIO 启用 JWT；
9. 密钥使用短期令牌或凭据系统注入，不写入 Skill；
10. 对上传内容、执行命令、退出码、耗时和下载产物建立审计日志；
11. 对产物进行类型、大小、恶意内容和压缩炸弹检查后再交付用户；
12. 定期清理过期 Sandbox、上传目录、临时文件和本地下载缓存。

## 相关资源

- [Skills Demo README](https://github.com/agents-flex/agents-flex/tree/main/demos/skills-demo)
- [OpenSandbox 官方文档](https://open-sandbox.ai/getting-started/)
- [AIO Sandbox 官方文档](https://sandbox.agent-infra.com/zh/guide/start/quick-start)
- [Tool 工具调用](../chat/tool)
- [Tool 构建](../chat/tool-build)

</div>
