<div v-pre>

# AIO Sandbox 安装与配置

AIO Sandbox 是 All-in-One Sandbox。官方镜像同时提供 Shell、文件、浏览器、Jupyter、VNC、终端和
Code Server 等服务。Agents-Flex 当前 Skills Runtime 使用其中的 Shell 与文件 API。

官方文档：

- [AIO Sandbox 快速开始](https://sandbox.agent-infra.com/zh/guide/start/quick-start)
- [Shell 终端](https://sandbox.agent-infra.com/zh/guide/basic/shell)
- [文件操作](https://sandbox.agent-infra.com/zh/guide/basic/file)
- [鉴权](https://sandbox.agent-infra.com/zh/guide/basic/authentication)
- [安全](https://sandbox.agent-infra.com/zh/guide/advanced/security)

### 前置条件

- 已安装并启动 Docker；
- 至少 2 GiB 可用内存；
- 宿主机 8080 端口可用，或选择其他本机端口。

### 官方镜像启动

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

中国大陆镜像：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:latest
```

生产环境应固定版本，避免 `latest` 在未验证的情况下变化。官方文档给出的版本格式示例：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:1.11.0
```

如果 8080 被占用：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:3000:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

此时 Java 的 `baseUrl` 应配置为 `http://localhost:3000`。

### 为什么绑定 127.0.0.1

`-p 127.0.0.1:8080:8080` 只允许宿主机访问。不要为了方便直接使用 `-p 8080:8080` 把未鉴权的
Sandbox 暴露到所有网卡。云主机部署应保持 8080 为私有端口，通过带 TLS、鉴权和访问控制的反向
代理或 Ingress 发布。

### 验证 AIO

```bash
docker ps --filter name=agentsflex-aio-sandbox
docker logs agentsflex-aio-sandbox
```

浏览器访问：

- 仪表板：`http://localhost:8080/index.html`
- OpenAPI：`http://localhost:8080/v1/docs`
- 终端：`http://localhost:8080/terminal`
- VNC：`http://localhost:8080/vnc/index.html?autoconnect=true`

### Java 配置

```java
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.aiosandbox.AioSandboxSkillRuntime;

import java.util.concurrent.TimeUnit;

SkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .remoteRoot("/home/gem/workspace/skills")
    .httpTimeoutMillis((int) TimeUnit.MINUTES.toMillis(11))
    .build();
```

多个会话共享同一个 AIO 容器时，可以使用与 Local、OpenSandbox 相同的会话目录配置，减少文件误读和误写：

```java
SkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .conversationId(conversationId)
    .conversationsRoot("/home/gem/workspace/conversations")
    .build();
```

此时默认工作目录为 `/home/gem/workspace/conversations/<conversationId>`，Skill 也会上传到该目录的
`skills` 子目录。Runtime 文件 API 会拒绝访问会话目录外的路径，相对路径会基于会话目录解析。

目录限制不能替代容器隔离。Shell 命令仍可显式引用其他绝对路径，符号链接也可能越过词法路径边界；
不可信租户应继续使用独立 AIO 容器或 OpenSandbox 实例。

如果 Skill 需要安装 CLI 或持续使用环境变量，可以在 `SkillsTool` 上配置，而不修改标准
`SKILL.md`：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("KDOCS_CLI_DIR", "/home/gem/.local/bin")
    .environment("PATH", "/home/gem/.local/bin:/usr/local/bin:/usr/bin:/bin")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "kdocs-skill")
    .skillRuntimeConfig("kdocs-skill", config)
    .runtime(runtime)
    .buildTools());
```

AIO 是长期运行的共享服务。Runtime 会在当前 Java 对象中缓存已上传 Skill 和环境配置；`close()` 会清理
这些本地状态，但不会删除远端安装结果或停止容器。不同租户需要强隔离时，不应共用同一个 AIO 容器。

`close()` 不会停止 AIO 容器。容器由部署系统或操作者管理：

```bash
docker stop agentsflex-aio-sandbox
```

### JWT 鉴权

AIO 支持使用 Base64 编码的 RSA 公钥启用 JWT 鉴权。生成密钥：

```bash
openssl genrsa -out private_key.pem 2048
openssl rsa -in private_key.pem -pubout -out public_key.pem

# macOS/Linux 的 base64 参数可能不同；关键是得到不带换行的 Base64 公钥。
export JWT_PUBLIC_KEY=$(base64 < public_key.pem | tr -d '\n')
```

把公钥作为容器环境变量传入：

```bash
docker run --security-opt seccomp=unconfined --rm -it \
  --name agentsflex-aio-sandbox \
  -e JWT_PUBLIC_KEY="$JWT_PUBLIC_KEY" \
  -p 127.0.0.1:8080:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

业务服务使用私钥签发 RS256 JWT，然后配置到 Runtime：

```java
SkillRuntime runtime = AioSandboxSkillRuntime.builder()
    .baseUrl("http://localhost:8080")
    .bearerToken(jwt)
    .build();
```

适配器会发送：

```text
Authorization: Bearer <JWT>
```

私钥只能保存在业务服务或密钥管理系统中，不能放进 Skill 目录、容器镜像或 Git 仓库。

</div>
