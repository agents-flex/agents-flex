<div v-pre>

# OpenSandbox 安装与配置

OpenSandbox 官方架构由 OpenSandbox Server 和执行 Runtime 组成。本地快速开始使用 Docker Engine
作为执行 Runtime：`opensandbox-server` 连接本机 Docker daemon，并按请求创建隔离容器。

> OpenSandbox 官方当前推荐通过 PyPI/uvx 运行 Server，再由 Server 管理 Docker 容器。不要把 AIO 的
> 单容器启动命令套用到 OpenSandbox。

官方文档：

- [OpenSandbox Quick Start](https://open-sandbox.ai/getting-started/)
- [OpenSandbox Installation](https://open-sandbox.ai/getting-started/installation)
- [OpenSandbox Configuration](https://open-sandbox.ai/getting-started/configuration)
- [OpenSandbox Kotlin/Java SDK](https://open-sandbox.ai/sdks/kotlin)

### 前置条件

- Docker Engine 20.10+；
- Python 3.10+；
- `uv`，推荐使用；也可以使用 `pip`；
- Linux、macOS，或 Windows WSL2。

### uv、uvx 和 pip 是什么

`opensandbox-server` 是一个发布到 Python Package Index（PyPI）的 Python 命令行程序，因此需要通过
Python 包管理工具安装或运行。这里出现的几个命令作用不同：

| 工具 | 作用 | 在本文中的用法 |
| --- | --- | --- |
| `pip` | Python 官方生态中最常见的包安装器，把包安装到当前 Python 环境 | `python3 -m pip install opensandbox-server` |
| `uv` | 使用 Rust 编写的 Python 包和项目管理工具，提供更快的依赖解析与安装能力 | 安装和管理 Python 工具，也提供 `uv pip` 兼容命令 |
| `uv pip` | `uv` 提供的 pip 兼容安装界面，并不是另一个独立工具 | `uv pip install opensandbox-server` |
| `uvx` | 随 `uv` 提供的工具运行器，类似 `pipx run`；为命令创建隔离环境并直接运行 | `uvx opensandbox-server` |

简单选择即可：

- 只想快速启动并避免污染全局 Python 环境，使用 `uvx`；
- 希望固定安装后反复运行，使用项目虚拟环境中的 `uv pip install` 或 `python3 -m pip install`；
- 不要同时用多套工具向同一个 Python 环境重复安装。

可以先检查本机已有的工具：

```bash
uv --version
uvx --version
python3 -m pip --version
```

`uvx` 不是 OpenSandbox 的组成部分，它只负责取得并运行 `opensandbox-server`。无论使用哪种安装方式，
最终启动的都是同一个 OpenSandbox Server。

先确认 Docker daemon 可用：

```bash
docker version
docker run --rm hello-world
```

### 使用 uvx 启动 OpenSandbox Server

```bash
# 生成使用 Docker Runtime 的配置文件
uvx opensandbox-server init-config ~/.sandbox.toml --example docker

# 启动 Server，默认读取 ~/.sandbox.toml
uvx opensandbox-server
```

也可以在已经激活的 Python 虚拟环境中先安装再执行。使用 `uv`：

```bash
uv pip install opensandbox-server
opensandbox-server init-config ~/.sandbox.toml --example docker
opensandbox-server
```

或者使用 `pip`：

```bash
python3 -m pip install opensandbox-server
opensandbox-server init-config ~/.sandbox.toml --example docker
opensandbox-server
```

指定其他配置路径：

```bash
export SANDBOX_CONFIG_PATH=/opt/opensandbox/sandbox.toml
opensandbox-server

# 或者
opensandbox-server --config /opt/opensandbox/sandbox.toml
```

### 验证服务

```bash
curl http://127.0.0.1:8080/health
# 预期：{"status":"healthy"}
```

服务启动后还可以访问：

- Swagger UI：`http://localhost:8080/docs`
- ReDoc：`http://localhost:8080/redoc`

### API Key

编辑 `~/.sandbox.toml` 的 `[server]` 配置，为 `api_key` 设置随机高强度密钥。配置 API Key 后，除
`/health`、`/docs` 和 `/redoc` 外的 API 都要求请求头：

```toml
[server]
api_key = "replace-with-a-random-secret"
```

```text
OPEN-SANDBOX-API-KEY: your-secret-api-key
```

验证鉴权：

```bash
curl \
  -H "OPEN-SANDBOX-API-KEY: your-secret-api-key" \
  http://localhost:8080/v1/sandboxes
```

生产环境必须启用 API Key。如果在 Docker/Kubernetes/CI 等非交互环境中明确选择无鉴权模式，
OpenSandbox 要求设置 `OPENSANDBOX_INSECURE_SERVER=YES` 来确认风险；该模式不适合暴露到公网。

Server 和 Java 客户端必须使用同一个值。推荐把客户端密钥放入环境变量：

```bash
export OPEN_SANDBOX_API_KEY="replace-with-a-random-secret"
```

### Java 配置

API Key 配置在 OpenSandbox SDK 的 `ConnectionConfig` 上，而不是
`OpenSandboxSkillRuntime.Builder` 上。`OpenSandboxSkillRuntime` 使用这个连接配置创建 SDK 客户端，SDK
随后会把 Key 写入每个受保护请求的 `OPEN-SANDBOX-API-KEY` 请求头。

```java
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.opensandbox.OpenSandboxSkillRuntime;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;

import java.time.Duration;

SkillRuntime runtime = OpenSandboxSkillRuntime.builder()
    .connectionConfig(connection -> connection
        .domain("localhost:8080")
        .apiKey(System.getenv("OPEN_SANDBOX_API_KEY")))
    .image("python:3.11")
    .remoteRoot("/workspace/skills")
    .sandboxTimeout(Duration.ofMinutes(10))
    .readyTimeout(Duration.ofSeconds(30))
    .networkPolicy(policy -> policy
        .defaultAction(NetworkPolicy.DefaultAction.DENY))
    .build();
```

`networkPolicy(...)` 同样支持直接传入已经构建的 `NetworkPolicy`，也支持通过回调配置 SDK Builder。
上例默认拒绝所有出站网络；如果 Skill 需要访问包仓库或业务 API，应通过 `addEgress(...)` 或
`egress(...)` 只添加必要规则。网络策略的实际执行能力取决于 OpenSandbox Server 使用的 Runtime。

对应关系如下：

| 位置 | 配置 | 含义 |
| --- | --- | --- |
| OpenSandbox Server | `~/.sandbox.toml` 中的 `server.api_key` | 服务端用于校验请求的密钥 |
| 业务服务环境 | `OPEN_SANDBOX_API_KEY` | 保存同一个密钥，避免写入 Java 源码 |
| Java SDK | `ConnectionConfig.builder().apiKey(...)` | 将密钥加入 OpenSandbox API 请求 |
| Skills Runtime | `.connectionConfig(connection -> ...)` | 使用 SDK Builder 配置 Runtime 连接 |

如果 Server 没有配置 `api_key`，Java 客户端可以不调用 `.apiKey(...)`；但生产环境不建议关闭鉴权。
如果 Server 已配置而 Java 端没有传入或传入不同的值，创建、查询和销毁 Sandbox 等请求会返回
`401 Unauthorized`。

如果应用已经统一创建和管理 OpenSandbox SDK 配置，原有写法仍然可用：

```java
ConnectionConfig connection = ConnectionConfig.builder()
    .domain("localhost:8080")
    .apiKey(System.getenv("OPEN_SANDBOX_API_KEY"))
    .build();

SkillRuntime runtime = OpenSandboxSkillRuntime.builder()
    .connectionConfig(connection)
    .build();
```

完整使用时必须关闭 Runtime：

```java
try (SkillRuntime runtime = createOpenSandboxRuntime()) {
    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory)
        .runtime(runtime)
        .buildTools());
    // 执行模型工具循环
} // 自动 kill 并关闭本次创建的 Sandbox
```

### OpenSandbox 配置项

| 配置 | 作用 |
| --- | --- |
| `connectionConfig` | Server 域名、协议与 API Key |
| `image` | 每个 Sandbox 使用的镜像，必须包含 Skill 所需运行时 |
| `remoteRoot` | Skill 上传根目录，必须是非根绝对路径 |
| `sandboxTimeout` | Sandbox 最长存活时间 |
| `readyTimeout` | 等待实例启动就绪的时间 |
| `resources` | 传递 CPU、内存等资源限制 |
| `environment` | 创建 Sandbox 时注入环境变量 |
| `networkPolicy` | 出站网络策略；可以传入 `NetworkPolicy` 或配置 `NetworkPolicy.Builder` |

Skill 自身的运行环境和上传后初始化通过 `SkillRuntimeConfig` 配置：

```java
SkillRuntimeConfig config = SkillRuntimeConfig.builder()
    .environment("TOOL_HOME", "/workspace/.tools")
    .bootstrapCommand("bash scripts/setup.sh", 120_000L)
    .build();

prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory, "demo-skill")
    .skillRuntimeConfig("demo-skill", config)
    .runtime(runtime)
    .buildTools());
```

Builder 的 `environment` 是 Sandbox 基础环境；`SkillRuntimeConfig.environment` 在 Skill 准备期间合并，
并在 bootstrap 和后续每次命令执行时注入。单次执行请求中的同名环境变量优先级最高。

镜像必须自行包含任务需要的程序，或允许任务安装依赖。例如 PPTX Demo 使用 `python3` 和
`python-pptx`。生产环境推荐构建固定版本的专用镜像，不要在每次任务中临时从公网安装依赖。

</div>
