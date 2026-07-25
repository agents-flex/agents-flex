<div v-pre>

# Sandbox 会话隔离实战

我们将从一个真实业务问题出发，像完成一门实验课一样，依次回答四个问题：

1. **为什么 AI 应用需要会话隔离？**
2. **Local、OpenSandbox 和 AIO 到底隔离了什么？**
3. **怎样用真实环境证明隔离有效，而不是“看起来应该有效”？**
4. **实验通过以后，距离生产安全还差什么？**

读完以后，你不仅应该能够照着命令操作，还应该能够向同事解释每一个组件为什么存在、每一个测试证明了什么，以及什么结论不能从测试中推出。

本文面向刚毕业、容器经验为零的 Java 开发者。我们会从 Docker 安装开始，最终在真实 OpenSandbox 和真实 AIO Sandbox 上执行单会话恢复、跨会话隔离和多会话并发实验。整个过程不使用 Mock Server。

建议第一次阅读时先理解第 1、2、6 节，不要急着复制命令。工程实践中，知道“为什么”以后，安装失败只是一个可定位的问题；如果只记住命令，换一个端口、镜像或操作系统就容易失去方向。

## 1. 场景：一个 AI 报告生成服务为什么会串数据

假设你加入了一家教育科技公司，负责开发“课程报告助手”。学生在网页中输入要求，AI 会调用 `bash`、`write`、`read` 等工具，最后生成 PPTX 或 PDF。

下午 14:00，系统同时收到两个请求：

| 时间 | 用户 | 请求 | 程序准备写入的文件 |
| --- | --- | --- | --- |
| 14:00:00 | 学生 Alice | 生成《操作系统课程报告》 | `output/report.pptx` |
| 14:00:01 | 学生 Bob | 生成《数据库课程报告》 | `output/report.pptx` |

如果两个请求共享同一个工作目录，可能出现这样的执行顺序：

```text
14:00:02 Alice 创建 output/report.pptx，内容是操作系统报告
14:00:03 Bob   覆盖 output/report.pptx，内容变成数据库报告
14:00:04 Alice 下载 output/report.pptx，却拿到了 Bob 的文件
```

这不是普通的“文件名冲突”。它同时意味着：

- **正确性事故**：Alice 得到错误结果；
- **隐私事故**：Alice 看到了 Bob 的数据；
- **并发事故**：单用户测试正常，上线后才随机出现；
- **合规事故**：系统无法证明租户数据被正确分隔；
- **运维事故**：问题具有时序性，日志中很难复现。

只给文件名加随机数并不能完整解决问题。AI 任务通常不只生成一个文件，它还可能产生中间图片、缓存、下载的附件、安装的依赖和修改后的 Skill。我们真正需要隔离的是一个任务的**完整运行状态**。

### 1.1 聊天会话和运行时会话不是一回事

这是初学者最容易忽略的地方。

聊天系统保存的是消息：

```text
用户问了什么 -> 模型回答了什么
```

Runtime 保存的是执行状态：

```text
命令在哪个目录执行 -> 写了哪些文件 -> 安装了什么 -> 下轮还要不要继续使用
```

即使聊天历史已经按用户分开，如果所有工具仍在 `/workspace` 中运行，文件照样会串。反过来，如果每轮都创建全新的目录，聊天历史虽然还在，上一轮生成的草稿却找不到了。

因此一个稳定的 `conversationId` 承担两项职责：

1. **分隔**：不同业务会话进入不同工作区；
2. **定位**：同一业务会话的下一轮能够回到原来的工作区。

可以把它理解为酒店房卡：不同客人的房卡不能打开同一个房间；同一位客人续住时，又必须能够回到原房间。

### 1.2 一个合格的 `conversationId` 应该怎样设计

`conversationId` 应由业务服务生成，而不是让大模型自由生成。它需要：

- 在隔离范围内唯一；
- 同一会话的多轮请求保持稳定；
- 不包含密码、手机号、身份证号等敏感明文；
- 能够表达租户边界，避免两个租户恰好使用同一个内部会话号；
- 符合 Agents-Flex 格式：1-128 个字符，首字符为字母或数字，只包含字母、数字、点、下划线和连字符。

例如：

```text
tenantA_user42_chat_20260725_001
```

不要使用带冒号的 `tenant:user:chat`，因为冒号不在允许字符集合中。也不要只使用递增数字 `1`、`2`、`3`，这很容易在多租户系统中碰撞。

### 1.3 没有隔离、目录隔离和容器隔离

隔离不是“有”或“没有”两个档位，而是逐层增强：

| 层次 | 做法 | 能解决的问题 | 仍然存在的问题 |
| --- | --- | --- | --- |
| 无隔离 | 所有任务使用同一目录 | 几乎什么也解决不了 | 同名文件覆盖、数据泄露、状态污染 |
| 目录隔离 | 每个会话使用不同目录 | 正常文件操作不再串会话 | 恶意 Shell、符号链接、系统权限仍可越界 |
| 进程/用户隔离 | 不同系统用户或受限进程 | 降低跨目录访问能力 | 仍共享内核和部分宿主资源 |
| 容器隔离 | 不同会话运行在不同容器 | 文件系统、进程和部分网络边界更清晰 | 容器不是虚拟机，仍需限制权限和资源 |
| 独立节点/虚拟机 | 高敏感任务使用独立主机边界 | 更强的租户隔离 | 成本和启动延迟更高 |

`conversationId` 主要解决“身份到工作区的稳定映射”，它本身不会自动创造最强安全边界。真正的隔离强度取决于 Runtime 把这个 ID 映射到了目录、容器，还是独立节点。

### 1.4 三种 Runtime 分别适合什么场景

| Runtime | 典型场景 | 状态如何保存 | 隔离结论 |
| --- | --- | --- | --- |
| Local Runtime | 开发者本机调试、可信内部脚本、快速定位问题 | 宿主机目录 | 方便但不安全，不运行不可信命令 |
| AIO Sandbox Runtime | 团队已有常驻工具容器、内部可信用户、希望快速复用浏览器/Jupyter/文件能力 | 同一容器的不同目录 | 能避免正常操作串目录，但不同租户不应依赖它做强隔离 |
| OpenSandbox Runtime | 多用户生产任务、按需创建环境、需要会话级容器生命周期 | Conversation Store 记录 Sandbox ID | 每个会话可使用独立 Sandbox，适合更强隔离需求 |

这里没有“永远最好的 Runtime”。正确选择取决于风险和成本：

- 开发阶段优先效率，可以从 Local 开始；
- 内部工具且用户可信，可以评估 AIO；
- 外部用户能够影响命令或文件内容，应优先 OpenSandbox；
- 金融、医疗等高敏感跨租户任务，还要叠加独立节点、网络策略和审计。

### 1.5 本文要证明的三个命题

我们不会用“代码看起来没问题”作为结论，而是提出三个可验证命题：

**命题一：同一会话可恢复。**

Runtime 关闭后，用相同 `conversationId` 重建 Runtime，应能读取上一轮文件。这证明 ID 不只是创建目录，还能稳定定位状态。

**命题二：不同会话不串数据。**

两个会话同时使用相同相对文件名，一个写 `alpha`，另一个写 `beta`，最后应各自读回自己的内容。

**命题三：并发不会破坏隔离。**

多个不同 `conversationId` 同时执行多轮写入和读取，不能出现随机覆盖、目录混用或错误复用。

请注意，我们没有提出“同一个 `conversationId` 可以被多个请求任意并发修改”。同一会话中的两个请求同时编辑同一个 PPTX，本质上仍然是共享状态并发写问题，应由业务层串行调度或加锁。

### 1.6 课堂小结：先用自己的话回答

继续安装之前，尝试回答：

1. 为什么聊天消息已经隔离，文件仍可能串？
2. 为什么给输出文件加随机后缀仍不等于完整会话隔离？
3. AIO 和 OpenSandbox 都叫 Sandbox，为什么安全结论不同？
4. `conversationId` 为什么既要“不同会话不同”，又要“同一会话稳定”？
5. 为什么同一个 `conversationId` 的并发写需要业务层协调？

如果你能回答这五个问题，后面的 Docker 和 Maven 命令就不再是孤立步骤。

## 2. 把测试设计成一组可信实验

### 2.1 为什么不能只做单元测试

Mock 测试能够验证“代码准备发送什么路径”，但不能证明真实系统接受这个请求，更不能发现：

- OpenSandbox Server 对 metadata 长度的真实限制；
- Docker 镜像是否兼容；
- Sandbox 创建、连接、续期和删除是否真正成功；
- AIO HTTP API 与客户端理解是否一致；
- 并发请求在真实文件系统中是否互相覆盖。

本次真实测试就发现过一个典型问题：SHA-256 会话哈希有 64 个字符，而 OpenSandbox Server 的 metadata value 最长 63 个字符。模拟测试全部通过，真实 Server 却返回 HTTP 400。只有端到端实验才能暴露这类“每一层单独看都合理，组合起来却失败”的问题。

单元测试仍然重要。合理的证据结构是：

```text
单元测试：快速验证路径规则和异常分支
    +
真实集成测试：验证 Java SDK、HTTP 服务、Docker 和文件系统协同工作
    +
生产前压力/安全测试：验证容量、恶意输入和故障恢复
```

三者不是替代关系。

### 2.2 实验中的自变量、控制变量和观测值

像做实验一样设计测试，可以避免“跑了很多命令，但不知道证明了什么”。

| 类型 | 本文如何设置 |
| --- | --- |
| 自变量 | `conversationId` 不同，Runtime 类型不同 |
| 控制变量 | 文件名都叫 `marker.txt`，命令结构相同，读写轮数固定 |
| 观测值 | 每轮读回内容、命令退出码、最终文件、Server HTTP 状态、容器数量 |
| 失败判据 | 内容不匹配、越界请求未被拒绝、超时、Sandbox 未清理 |

为什么故意使用同名文件？因为如果每个会话使用不同文件名，即使所有请求错误地进入同一个目录，测试也可能通过。同名文件是主动制造冲突条件，让隔离错误更容易暴露。

为什么使用启动闩锁？如果线程一个接一个完成，所谓“并发测试”实际上仍然是串行测试。闩锁让多个工作线程先就位，再一起开始，增大时序交叉的机会。

### 2.3 实验拓扑

一台电脑上不能让两个服务同时监听同一个端口。本文固定使用：

| 服务 | 宿主机地址 | 在实验中的角色 |
| --- | --- | --- |
| OpenSandbox Server | `http://127.0.0.1:8080` | 接收 Java SDK 请求，按会话管理独立 Sandbox |
| AIO Sandbox | `http://127.0.0.1:3000` | 提供共享容器中的 Shell 和文件 API |

```text
                       conversation A --> python Sandbox A
JUnit --> OpenSandbox Server
                       conversation B --> python Sandbox B

JUnit --> AIO Sandbox --> conversations/A/
                     `-> conversations/B/
```

这个拓扑刻意对比两种隔离方式：OpenSandbox 的会话落在不同容器；AIO 的会话落在同一容器的不同目录。

所有端口都绑定到 `127.0.0.1`，只允许本机访问。这样做不是为了“命令更好看”，而是避免把一个能够执行 Shell 的实验服务暴露给局域网或公网。

### 2.4 像研究者一样记录实验

不要只保留一张 `BUILD SUCCESS` 截图。建议建立实验记录：

```text
日期：
操作系统与 CPU 架构：
Docker 版本：
Java / Maven 版本：
OpenSandbox Server 版本：
execd 镜像标签：
AIO 镜像标签或 digest：
OpenSandbox 并发会话数与轮数：
AIO 并发会话数与轮数：
测试结果：
Server 异常日志：
测试后残留容器：
```

这样做有两个价值：第一，半年后可以复现；第二，升级镜像后出现回归时，能够比较环境差异。没有环境信息的“我这里通过了”不是高质量工程证据。

## 3. 准备电脑

这一阶段的目标不是“把软件都装上”，而是建立一条可工作的执行链：Java 测试通过 HTTP 调用 Sandbox 服务，Sandbox 服务再通过 Docker 创建真实执行环境。链条中任何一环缺失，后面的错误都可能表现为简单的 `Connection refused`。

完成本节后先停下来检查，不要带着未解决的安装错误继续向后走。

### 3.1 建议配置

最低建议：

- 64 位 CPU；
- 8 GiB 内存，推荐 16 GiB；
- 至少 15 GiB 可用磁盘；
- 稳定网络，首次需要下载镜像和 Maven 依赖；
- macOS、Linux，或启用了 WSL2 的 Windows 10/11。

AIO 镜像比较大。下载后的磁盘占用可能超过 6 GiB。OpenSandbox 并发测试还会同时启动 4 个 Python 容器，因此不要只给 Docker 分配 2 GiB 内存。

### 3.2 什么是终端

终端是输入命令的窗口：

- macOS：打开“终端”或 iTerm2；
- Windows：推荐打开 WSL2 中的 Ubuntu 终端；
- Linux：打开 Terminal。

常用命令：

```bash
pwd          # 显示当前目录
ls           # 显示当前目录中的文件
cd path      # 进入指定目录
cd ..        # 返回上一级目录
```

命令前面的 `$` 如果出现在其他教程中，通常只是提示符，不要把 `$` 一起输入。

## 4. 安装 Docker

Docker 包含两个重要部分：

- Docker CLI：你输入的 `docker` 命令；
- Docker Engine：真正创建和运行容器的后台服务。

只安装命令行而没有启动 Engine，`docker ps` 仍然会失败。

为什么本文必须使用 Docker？因为我们要验证的不是 Java 字符串如何拼接路径，而是命令进入真实文件系统后是否仍然隔离。OpenSandbox 还需要让不同会话实际落入不同容器，没有 Docker 就无法验证这一层边界。

### 4.1 macOS 安装 Docker Desktop

1. 打开 [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)；
2. 根据芯片选择 Apple Silicon 或 Intel 版本；
3. 安装并启动 Docker Desktop；
4. 等待菜单栏中的 Docker 图标显示 Engine 已运行；
5. 在 Docker Desktop 设置中为虚拟机分配至少 6 GiB 内存，推荐 8 GiB。

### 4.2 Windows 安装 Docker Desktop

推荐使用 WSL2：

1. 以管理员身份打开 PowerShell；
2. 执行 `wsl --install`；
3. 重启电脑；
4. 安装 [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/)；
5. 在 Docker Desktop 中启用 WSL2 backend；
6. 在 Resources -> WSL Integration 中启用你的 Ubuntu；
7. 后续命令尽量都在同一个 WSL2 Ubuntu 终端中执行。

### 4.3 Ubuntu 安装 Docker Engine

生产环境应按照 [Docker Engine Ubuntu 官方文档](https://docs.docker.com/engine/install/ubuntu/) 添加官方软件源。安装完成后启动服务：

```bash
sudo systemctl enable --now docker
sudo docker version
```

如果希望不用 `sudo` 执行 Docker，可以把用户加入 `docker` 组：

```bash
sudo usermod -aG docker "$USER"
```

然后退出当前登录会话并重新登录。注意：`docker` 组基本等同于宿主机 root 权限，只应授予可信用户。

### 4.4 验证 Docker

执行：

```bash
docker version
docker ps
docker run --rm hello-world
```

正确结果：

- `docker version` 同时显示 Client 和 Server；
- `docker ps` 显示表头，不一定有容器；
- `hello-world` 输出欢迎信息后自动删除测试容器。

如果看到 `Cannot connect to the Docker daemon`，说明 Docker Desktop/Engine 没启动。

如果看到 `permission denied ... docker.sock`，Linux 用户需要使用 `sudo`，或正确配置 `docker` 组。不要执行来源不明的 `chmod 777 /var/run/docker.sock`。

## 5. 安装 Git、Java、Maven 和 uv

这些工具各自承担不同职责：

| 工具 | 为什么需要 |
| --- | --- |
| Git | 获取和查看 Agents-Flex 源码 |
| Java | 编译并运行 Runtime 与测试代码 |
| Maven | 解析 Java 依赖、编译模块、启动 JUnit |
| uv/uvx | 隔离安装并运行 OpenSandbox Python Server |
| Docker | 运行 AIO，并为 OpenSandbox 创建执行容器 |

把职责分清以后，排错会容易很多：`mvn` 失败先看 Java/Maven，`uvx` 失败先看 Python 工具环境，Sandbox 创建失败再看 Docker 和镜像。

### 5.1 检查工具

```bash
git --version
java -version
mvn -version
uv --version
uvx --version
```

建议使用 JDK 8 或更高版本。初学者可以选择 JDK 17。`mvn -version` 输出中应显示它实际使用的 Java 路径和版本。

### 5.2 macOS 常用安装方式

安装 Homebrew 后可以执行：

```bash
brew install git maven uv
brew install openjdk@17
```

Homebrew 会在安装结束时提示如何把 JDK 加入系统路径，请按终端中的提示执行。

### 5.3 Ubuntu 常用安装方式

```bash
sudo apt update
sudo apt install -y git maven openjdk-17-jdk curl
curl -LsSf https://astral.sh/uv/install.sh | sh
```

安装 `uv` 后重新打开终端，再执行 `uv --version`。

### 5.4 获取 Agents-Flex 源码

如果本机还没有仓库：

```bash
git clone https://github.com/agents-flex/agents-flex.git
cd agents-flex
```

如果已经有仓库，只需要进入仓库根目录。下面命令应该能看到顶层 `pom.xml`：

```bash
pwd
ls pom.xml
```

后续所有 Maven 命令都应在这个仓库根目录运行。

## 6. Sandbox 安全基础

在安全工程中，最危险的误解是把“用了 Docker”或“配置了 `conversationId`”理解成“已经安全”。安全不是一个开关，而是一组相互补充的控制措施。

先回答三个问题：

1. **保护什么资产？** 用户上传文件、生成产物、业务凭据、宿主机和内网服务；
2. **防谁？** 无意写错路径的正常程序、恶意提示词、被篡改的 Skill、外部攻击者；
3. **攻击者能做什么？** 只能调用文件 API，还是能够影响完整 Shell 命令？

对“程序不小心写错目录”和“攻击者可以执行任意 Shell”使用同一套防护，是不严谨的。前者可以通过工作区路径约束大幅降低风险；后者必须依靠进程、容器、权限、网络和资源限制共同防御。

### 6.1 七层防护模型

可以把 Sandbox 安全理解成七层：

| 层 | 核心问题 | 本文涉及的控制 |
| --- | --- | --- |
| 身份层 | 这个请求属于谁 | 业务生成唯一 `conversationId`，包含租户边界 |
| 路径层 | 文件默认落在哪里 | 独立会话工作目录，文件 API 拒绝词法越界 |
| 进程层 | 命令与谁共享进程空间 | OpenSandbox 使用不同容器；AIO 共享容器 |
| 权限层 | 进程能做哪些系统操作 | 非 root、capabilities、seccomp、宿主目录挂载策略 |
| 资源层 | 是否能耗尽机器 | CPU、内存、PID、磁盘和超时限制 |
| 网络层 | 能访问哪些外部系统 | 本机绑定、API 鉴权、出站网络白名单 |
| 生命周期层 | 状态何时创建和销毁 | Store、续期、过期清理、审计和显式 destroy |

任意一层都不能替代其他层。例如 API Key 能阻止陌生客户端调用 Server，却不能阻止一个已获授权的 Sandbox 读取错误目录；容器能隔离文件系统，却不能自动限制它向公网发送数据。

### 6.2 目录隔离防止误操作，不防任意 Shell

Agents-Flex 会把相对路径限制在当前会话目录，并拒绝明显的 `../other-conversation/file.txt`。这对模型调用 `read`、`write`、`edit` 等受控工具非常有价值。

但以下命令直接使用绝对路径：

```bash
cat /home/gem/workspace/conversations/another-conversation/marker.txt
```

如果它在共享 AIO 容器中执行，而操作系统权限允许，目录字符串检查无法保护你。因此：

- AIO 适合可信内部任务或已经经过约束的命令；
- 不可信租户不应共享同一个 AIO 容器；
- OpenSandbox 的独立容器边界更适合外部用户；
- 即使使用 OpenSandbox，也要避免危险挂载和过高权限。

### 6.3 容器比目录强，但容器不是虚拟机

容器提供独立的文件系统视图、进程命名空间和网络配置，但多个容器仍共享宿主机内核。错误配置可能削弱甚至绕过隔离，例如：

- 使用 `--privileged`；
- 挂载宿主机根目录；
- 把 Docker Socket 挂进执行容器；
- 添加不必要的 `SYS_ADMIN` 等 capability；
- 使用 root 用户并开放所有系统调用；
- 不限制内存、PID 和磁盘。

所以“每会话一个容器”是重要边界，但不是安全工作的终点。

### 6.4 只绑定本机地址

下面两种端口映射含义不同：

```text
127.0.0.1:3000:8080  只有本机可以访问
0.0.0.0:3000:8080    局域网甚至公网可能访问
```

本文统一使用 `127.0.0.1`。一个没有认证、能够执行 Shell 的服务如果暴露到公网，攻击者可能直接把你的电脑当成远程执行节点。

`127.0.0.1` 解决的是“其他机器能否连接”，不解决“本机其他进程能否连接”。生产环境仍然必须鉴权。

### 6.5 不要把 Docker Socket 暴露给不可信容器

OpenSandbox Server 需要管理 Docker，因此它本身是高权限组件。能够调用 Docker API 的进程通常可以创建特权容器、挂载宿主机目录，权限接近 root。

因此：

- OpenSandbox Server 只监听内网或本机；
- 必须配置 API Key；
- 不要让普通用户直接访问 Docker Socket；
- 不要把 `/var/run/docker.sock` 随意挂载给第三方容器；
- Server 和执行 Sandbox 应使用专用机器或受控节点。

### 6.6 固定镜像版本与供应链风险

镜像不仅包含操作系统，还包含大量第三方软件。拉取镜像等于信任镜像发布者和其中的依赖。

`latest` 会随时间变化。今天测试通过，不代表下个月拉取的新 `latest` 仍然兼容。实验阶段可以使用 `latest`，生产环境必须固定经过验证的版本或镜像摘要，并进行漏洞扫描和来源校验。

### 6.7 密钥不能写进 Git 或 Skill

不要把 API Key、JWT 私钥或 Token 写入：

- Java 源码；
- `SKILL.md`；
- Dockerfile；
- Git 跟踪的 `.env`；
- 截图、测试日志和问题报告。

使用环境变量或专业密钥管理系统注入。还要注意：把密钥放在 Maven `-Dkey=...` 命令行中，可能被本机进程列表记录。因此本文的真实 IT 支持直接读取环境变量。

### 6.8 限制资源：可用性也是安全

生产环境至少需要限制：

- CPU；
- 内存；
- PID 数量；
- 磁盘空间；
- 单次命令超时；
- Sandbox 总生命周期；
- 出站网络访问范围。

默认拒绝出站网络，仅开放任务真正需要的域名，比默认允许所有网络更安全。

资源限制不是性能优化，而是防止一个失控或恶意任务拖垮其他会话。例如无限创建子进程是 PID 耗尽攻击，无限写日志是磁盘耗尽攻击，无限循环会长期占用 CPU。

### 6.9 生命周期和审计

会话隔离还必须回答“什么时候销毁”：

- 销毁太早：用户下一轮找不到上一轮文件；
- 永不销毁：磁盘持续增长，旧凭据和用户数据长期残留；
- 只关闭客户端：远端 Sandbox 可能仍在运行；
- 只删除 Store：可能留下无法管理的孤儿容器。

OpenSandbox 配置 `conversationId` 后，普通 `close()` 只释放当前 Java 客户端资源，不代表结束业务会话。真正结束时要调用 `destroyConversationSandbox()`。生产系统还需要定时扫描过期记录、孤儿容器和残留目录，并记录谁在何时创建、连接和销毁了 Sandbox。

## 7. 拉取实验镜像

镜像是容器的只读模板。提前拉取镜像有两个教学目的：

1. 把“网络下载慢”和“Runtime 逻辑错误”分开；
2. 在并发测试前确认所有线程使用同一份已知镜像，而不是边测试边下载。

OpenSandbox 和 AIO 的镜像角色不同。OpenSandbox 的 Python 镜像是每个任务真正运行命令的环境；`execd` 是命令执行代理；AIO 镜像则把 Shell、文件、浏览器等服务打包在一个长期容器中。

### 7.1 OpenSandbox 使用的镜像

OpenSandbox Server 需要：

1. 执行 Sandbox 镜像，例如 `python:3.11`；
2. `execd` 镜像，用于向 Sandbox 注入命令执行服务。

先拉取 Python 镜像：

```bash
docker pull python:3.11
```

`execd` 的准确版本由下一节生成的配置文件决定。不要猜版本，先生成配置，再查看 `[runtime]` 下的 `execd_image`，然后拉取完全相同的标签。

例如配置中写的是：

```toml
[runtime]
type = "docker"
execd_image = "opensandbox/execd:v1.0.21"
```

就执行：

```bash
docker pull opensandbox/execd:v1.0.21
```

### 7.2 AIO Sandbox 镜像

网络能够访问 GitHub Container Registry 时：

```bash
docker pull ghcr.io/agent-infra/sandbox:latest
```

中国大陆网络也可以使用镜像：

```bash
docker pull enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:latest
```

查看本机已有镜像：

```bash
docker images
```

## 8. 配置并启动 OpenSandbox

先建立请求生命周期：

```text
Java Runtime
  -> OpenSandbox Server 验证 API Key
  -> Server 请求 Docker 创建 python:3.11 容器
  -> 注入 execd
  -> Java SDK 连接 execd 执行命令和文件操作
  -> Conversation Store 保存 conversationId 到 sandboxId 的映射
```

因此 OpenSandbox Server 不是执行任务的 Python 容器，它是控制面；真正执行用户命令的是它创建的数据面 Sandbox。把控制面和执行环境分开，才能按会话创建、续期和销毁容器。

### 8.1 生成 Docker Runtime 配置

执行：

```bash
uvx opensandbox-server init-config ~/.sandbox.toml --example docker
```

该命令会通过 `uvx` 准备隔离的 Python 工具环境，并生成 `~/.sandbox.toml`。

首次运行可能下载较多 Python 包。看到下载信息后耐心等待，不要重复启动多个相同命令。

打开配置文件，确认至少包含：

```toml
[server]
host = "127.0.0.1"
port = 8080
max_sandbox_timeout_seconds = 86400

[runtime]
type = "docker"
execd_image = "opensandbox/execd:v1.0.21"

[docker]
network_mode = "bridge"
port_range_min = 40000
port_range_max = 60000
```

如果本机只有另一个 `execd` 标签，可以拉取配置指定的版本。只有在你明确了解兼容性时，才修改配置去使用已有版本。

### 8.2 配置 API Key

生成随机密钥：

```bash
openssl rand -hex 32
```

把输出保存到密码管理器，并写入 `~/.sandbox.toml`：

```toml
[server]
host = "127.0.0.1"
port = 8080
api_key = "替换为刚才生成的随机密钥"
```

在当前终端设置客户端环境变量：

```bash
export OPEN_SANDBOX_DOMAIN="localhost:8080"
export OPEN_SANDBOX_API_KEY="替换为同一个密钥"
```

Server 和客户端必须使用相同的值。

仅在本机临时实验、确认端口绑定为 `127.0.0.1`，并且明确接受风险时，才可以不配置 API Key。无鉴权的非交互启动还需要：

```bash
export OPENSANDBOX_INSECURE_SERVER=YES
```

生产环境绝对不要使用这个无鉴权模式。

### 8.3 启动 Server

新开一个终端窗口，执行：

```bash
uvx opensandbox-server --config ~/.sandbox.toml
```

这个终端需要保持打开。看到类似下面的信息表示启动成功：

```text
Application startup complete.
Uvicorn running on http://127.0.0.1:8080
```

### 8.4 健康检查

再开一个终端：

```bash
curl http://127.0.0.1:8080/health
```

预期：

```json
{"status":"healthy"}
```

浏览器也可以打开：

- `http://127.0.0.1:8080/docs`
- `http://127.0.0.1:8080/redoc`

不要关闭运行 Server 的终端。

## 9. 启动 AIO Sandbox

OpenSandbox 已占用宿主机 8080，因此把 AIO 的容器内 8080 映射到宿主机 3000。

AIO 的设计目标与 OpenSandbox 不同：它预先启动一个功能齐全的工具环境，Java Runtime 直接连接这个长期服务，不负责创建或销毁容器。优点是启动快、工具齐全；代价是多个会话共享同一个容器边界。

这也是我们必须测试 AIO 目录隔离的原因：容器本身不会替你区分 `conversationId`，隔离依赖 Runtime 正确设置每个请求的工作目录和文件路径。

### 9.1 使用官方镜像

```bash
docker run --security-opt seccomp=unconfined --rm -d \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:3000:8080 \
  ghcr.io/agent-infra/sandbox:latest
```

### 9.2 使用中国大陆镜像

```bash
docker run --security-opt seccomp=unconfined --rm -d \
  --name agentsflex-aio-sandbox \
  -p 127.0.0.1:3000:8080 \
  enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:latest
```

参数解释：

| 参数 | 含义 |
| --- | --- |
| `--security-opt seccomp=unconfined` | AIO 官方启动方式所需，但会放宽系统调用限制，应只用于受控环境 |
| `--rm` | 容器停止后自动删除容器记录，不删除镜像 |
| `-d` | 后台运行 |
| `--name` | 给容器一个容易识别的名字 |
| `-p 127.0.0.1:3000:8080` | 本机 3000 转发到容器 8080，且不暴露到外网 |

### 9.3 检查状态

```bash
docker ps --filter name=agentsflex-aio-sandbox
docker logs --tail 100 agentsflex-aio-sandbox
curl -I http://127.0.0.1:3000/v1/docs
```

容器刚启动时需要初始化多个服务。等待 `docker ps` 的状态变为 `healthy`，并确认 HTTP 返回 200。

还可以在浏览器打开：

- `http://127.0.0.1:3000/index.html`
- `http://127.0.0.1:3000/v1/docs`
- `http://127.0.0.1:3000/terminal`

### 9.4 AIO 鉴权说明

本地实验通过 `127.0.0.1` 绑定降低暴露风险。生产环境还应启用 JWT。AIO 使用 Base64 编码的 RSA 公钥验证 RS256 Token，详细配置见 [AIO Sandbox 配置](./aio-sandbox#jwt-鉴权)。

只绑定本机不能替代生产鉴权，因为同一台机器上的其他进程仍可能访问该端口。

## 10. 确认两个服务同时运行

这是第一个实验检查点。健康检查只能证明“服务能够响应”，还不能证明会话隔离正确。但如果健康检查都失败，继续运行隔离测试只会得到噪声很大的连接异常。

执行：

```bash
curl http://127.0.0.1:8080/health
curl -I http://127.0.0.1:3000/v1/docs
docker ps
```

此时应该看到：

- OpenSandbox 返回 `healthy`；
- AIO 文档接口返回 HTTP 200；
- `docker ps` 中存在 `agentsflex-aio-sandbox`；
- OpenSandbox 创建的任务容器只有在测试执行期间才会出现。

## 11. 带着假设理解真实集成测试

仓库提供两组需要显式指定真实服务地址的测试：

- `OpenSandboxSkillRuntimeIT`；
- `AioSandboxSkillRuntimeIT`。

它们使用 `IT` 后缀，普通单元测试不会自动连接你的本地 Sandbox。只有显式执行 `-Dtest=...IT` 才会运行。

每组 IT 包含两个场景。

| 测试动作 | 它要证明什么 |
| --- | --- |
| 同一 ID 关闭后重建 | 会话身份能够稳定定位旧状态 |
| 两个 ID 写同名文件 | 工作区映射不会把不同会话放到一起 |
| 读取另一个会话绝对路径 | Runtime 文件 API 的路径边界会主动拒绝越界 |
| 多线程同时开始 | 隔离在真实时序交叉下仍然成立 |
| 最终再次逐会话读取 | 不只验证瞬时返回，还验证落盘后的最终状态 |
| OpenSandbox `finally` destroy | 失败或成功后都尽量回收真实容器 |

### 11.1 恢复与隔离测试

测试过程：

1. 为会话 A 创建 Runtime；
2. 写入 `marker.txt = alpha`；
3. 关闭当前 Runtime；
4. 使用相同 `conversationId` 创建新 Runtime；
5. 确认仍能读取 `alpha`；
6. 为会话 B 创建 Runtime；
7. 在同名 `marker.txt` 中写入 `beta`；
8. 确认 A 仍是 `alpha`，B 是 `beta`；
9. 尝试通过绝对路径访问另一个会话，确认 Runtime 拒绝请求。

### 11.2 多会话并发测试

OpenSandbox 测试：

- 同时启动 4 个不同 `conversationId`；
- 使用线程池和启动闩锁，让 4 个请求尽量同时开始；
- 每个会话执行 3 轮 Shell 命令；
- 每轮都向相对路径 `marker.txt` 写入自己的值并立即读回；
- 共验证 12 轮并发写入和读取；
- 最后逐个会话再次核对最终文件；
- `finally` 中销毁所有测试 Sandbox。

AIO 测试：

- 同时启动 8 个不同 `conversationId`；
- 每个会话执行 5 轮；
- 共验证 40 轮并发写入和读取；
- 所有会话都使用同一个文件名 `marker.txt`；
- 最后逐个目录检查最终值。

相同文件名非常重要：如果实现错误地把所有会话映射到一个目录，测试会立刻出现内容覆盖或断言失败。

这里故意并发的是**不同** `conversationId`。同一个 ID 表示共享同一份会话状态；如果两个线程同时编辑同一个文件，正确行为应由业务语义决定，不能简单归类为“隔离失败”。生产系统通常应按同一 `conversationId` 排队执行写请求。

## 12. 运行 OpenSandbox 真实测试

### 12.1 无鉴权本机实验

确认 OpenSandbox 在 `localhost:8080` 后执行：

```bash
mvn -pl agents-flex-skills-sandbox/agents-flex-skills-open-sandbox \
  -Dtest=OpenSandboxSkillRuntimeIT \
  -Dagentsflex.it.opensandbox.domain=localhost:8080 \
  test
```

### 12.2 启用了 API Key

如果 Server 配置了 API Key，推荐通过环境变量传入，避免密钥出现在 Maven 命令行和进程列表中：

```bash
export OPEN_SANDBOX_DOMAIN="localhost:8080"
export OPEN_SANDBOX_API_KEY="替换为 Server 配置中的密钥"

mvn -pl agents-flex-skills-sandbox/agents-flex-skills-open-sandbox \
  -Dtest=OpenSandboxSkillRuntimeIT \
  test
```

命令执行期间可以在另一个终端观察：

```bash
docker ps
```

并发测试阶段应短暂出现多个基于 `python:3.11` 的 Sandbox。测试结束后这些容器应被删除。

### 12.3 成功输出

关键结果应类似：

```text
Running com.agentsflex.skill.runtime.opensandbox.OpenSandboxSkillRuntimeIT
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

OpenSandbox Server 日志还应出现：

- 多个 `POST /v1/sandboxes` 返回 202；
- 多个续期请求返回 200；
- 测试清理时多个 `DELETE /v1/sandboxes/...` 返回 204。

## 13. 运行 AIO 真实测试

确认 AIO 在 `http://localhost:3000` 后执行：

```bash
mvn -pl agents-flex-skills-sandbox/agents-flex-skills-aio-sandbox \
  -Dtest=AioSandboxSkillRuntimeIT \
  -Dagentsflex.it.aioSandbox.baseUrl=http://localhost:3000 \
  test
```

如果 AIO 启用了 JWT，先设置不含 `Bearer ` 前缀的 Token。测试会读取 `AIO_SANDBOX_BASE_URL` 和 `AIO_SANDBOX_TOKEN`：

```bash
export AIO_SANDBOX_BASE_URL="http://localhost:3000"
export AIO_SANDBOX_TOKEN="替换为有效的 RS256 JWT"

mvn -pl agents-flex-skills-sandbox/agents-flex-skills-aio-sandbox \
  -Dtest=AioSandboxSkillRuntimeIT \
  test
```

成功输出应类似：

```text
Running com.agentsflex.skill.runtime.aiosandbox.AioSandboxSkillRuntimeIT
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 14. 运行原有回归测试

真实测试通过后，再运行不依赖真实服务的回归测试。这可以确认修改没有破坏原有路径校验和协议处理。

OpenSandbox：

```bash
mvn -pl agents-flex-skills-sandbox/agents-flex-skills-open-sandbox \
  -Dtest=OpenSandboxSkillRuntimeTest \
  test
```

AIO：

```bash
mvn -pl agents-flex-skills-sandbox/agents-flex-skills-aio-sandbox \
  -Dtest=AioSandboxSkillRuntimeTest \
  test
```

Local Runtime：

```bash
mvn -pl agents-flex-skills \
  -Dtest=SkillRuntimeTest \
  test
```

## 15. 如何判断会话隔离真的通过

不要只看最后一行 `BUILD SUCCESS`，还要确认以下事实：

| 检查项 | 正确结果 |
| --- | --- |
| 同 ID 重建 Runtime | 能读到上一次写入的文件 |
| 不同 ID 使用同名文件 | 每个会话读到自己的内容 |
| 越界路径 | 在请求到达远端前被拒绝 |
| OpenSandbox 并发 | 4 个真实 Sandbox 同时创建，内容互不影响 |
| AIO 并发 | 8 个会话目录同时执行，40 轮无串写 |
| 清理 | OpenSandbox 测试容器全部被 DELETE |

测试通过意味着当前测试覆盖的正常工作流没有串会话。它不等于完成了针对恶意代码、内核漏洞、符号链接攻击和资源耗尽的完整安全审计。

### 15.1 从结果推导结论

正确的工程结论应该有边界：

| 观察到的证据 | 可以得出的结论 | 不能得出的结论 |
| --- | --- | --- |
| AIO 40 轮并发读写正确 | 不同会话的默认目录映射在本次并发规模下有效 | 任意恶意 Shell 都无法越界 |
| OpenSandbox 同时创建 4 个容器 | 不同 ID 能并发获得独立 Sandbox | 容器内核不存在漏洞 |
| 越界文件 API 被拒绝 | 受控文件工具有词法路径边界 | 符号链接和绝对 Shell 路径都安全 |
| 同 ID 重建后读到旧文件 | 状态复用链路有效 | 多 JVM 一定能复用默认内存 Store |
| 测试结束容器被删除 | 正常测试生命周期能够清理 | 进程崩溃时绝不会产生孤儿资源 |

专家式测试报告不会写“Sandbox 已绝对安全”，而会写清环境、负载、通过条件和未覆盖风险。

### 15.2 回到开篇的 Alice 和 Bob

现在可以完整回答“我们究竟解决了什么”。

业务层分别生成：

```text
tenantSchool_alice_report_001
tenantSchool_bob_report_001
```

Runtime 收到这两个 ID 后：

- 把 Alice 和 Bob 的相对路径解析到不同工作区；
- 即使两人都生成 `output/report.pptx`，也不会因为文件名相同而自然落到同一位置；
- Alice 下一轮继续修改报告时，稳定 ID 能定位之前的草稿；
- 多个不同会话同时执行时，目录或 Sandbox 映射保持独立；
- 受控文件 API 尝试读取另一个会话路径时会被拒绝；
- OpenSandbox 场景下，会话结束后可以显式销毁整个执行环境。

这套机制解决的是**运行状态的归属、恢复和正常并发隔离**。它没有自动解决同一会话的并发编辑、恶意 Shell、业务授权、网络外泄和容器逃逸。这些问题分别属于调度、权限、身份、网络和基础设施安全层。

## 16. 停止和清理

### 16.1 停止 AIO

```bash
docker stop agentsflex-aio-sandbox
```

因为启动时使用了 `--rm`，停止后容器记录会自动删除。镜像仍然保留，下次不需要重新下载。

### 16.2 停止 OpenSandbox Server

回到运行 `uvx opensandbox-server` 的终端，按：

```text
Ctrl + C
```

看到 `Application shutdown complete` 表示正常停止。

### 16.3 检查残留容器

```bash
docker ps
```

不应再看到：

- `agentsflex-aio-sandbox`；
- 测试创建的 Python Sandbox；
- `execd` 临时容器。

如果只是测试失败导致 OpenSandbox 清理逻辑未执行，先查看：

```bash
docker ps -a
```

不要看到容器就盲目批量删除。先根据容器名称、镜像、创建时间确认它确实属于本次实验，避免误删数据库等业务容器。

### 16.4 是否删除镜像

通常不需要删除镜像。它们是只读模板，下次测试可以复用。

只有磁盘空间不足且确认不再使用时，才通过 Docker Desktop 的 Images 页面删除明确选中的镜像。初学者不要使用会批量清理所有未使用资源的命令，因为其他项目可能依赖这些镜像、网络或卷。

## 17. 常见问题

### 17.1 `Cannot connect to the Docker daemon`

原因：Docker Engine 没启动。

处理：

1. 打开 Docker Desktop；
2. 等待 Engine ready；
3. 再执行 `docker version`；
4. Linux 检查 `sudo systemctl status docker`。

### 17.2 `permission denied ... docker.sock`

原因：当前用户不能访问 Docker Socket。

处理：使用 `sudo docker ...`，或者让管理员按最小权限原则配置 `docker` 组。不要对 Socket 设置全员可写。

### 17.3 `port is already allocated`

原因：8080 或 3000 已被其他程序占用。

macOS/Linux 可以检查：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
lsof -nP -iTCP:3000 -sTCP:LISTEN
```

优先停止你确认属于自己的旧测试服务。也可以选择新端口，但必须同步修改测试中的 Server 地址。

### 17.4 OpenSandbox `/health` 连接失败

检查：

1. 启动 Server 的终端是否仍在运行；
2. 配置中的 `host` 是否为 `127.0.0.1`；
3. 端口是否为 8080；
4. Server 日志是否显示 Docker 初始化失败；
5. `docker version` 是否正常。

### 17.5 OpenSandbox 返回 401

Server 和测试使用的 API Key 不一致。确认：

- `~/.sandbox.toml` 中的 `server.api_key`；
- `OPEN_SANDBOX_API_KEY` 环境变量；
- Maven 参数引用的是正确变量；
- 密钥前后没有多余空格或引号。

### 17.6 OpenSandbox 创建 Sandbox 返回 400 metadata 错误

较新的 OpenSandbox Server 要求 metadata value 最长 63 字符。Agents-Flex Runtime 已对仅用于展示和检索的 metadata 值进行长度限制，同时保留 Store 中完整的会话哈希。

如果仍出现该错误，确认你编译的是包含修复的当前源码，而不是 Maven 本地缓存中的旧版本。

### 17.7 `execd` 镜像标签不存在

生成配置的版本与本机镜像不一致。执行：

```bash
docker images | grep opensandbox/execd
grep execd_image ~/.sandbox.toml
```

推荐拉取配置指定版本。不要把 `execd` 版本和 Java SDK 版本混为一谈。

### 17.8 AIO 一直不是 healthy

查看日志：

```bash
docker logs --tail 200 agentsflex-aio-sandbox
```

常见原因：

- Docker 内存不足；
- 当前 CPU 架构不受镜像支持；
- 容器内某个服务启动失败；
- 磁盘空间不足；
- 镜像下载不完整。

### 17.9 Maven 下载依赖失败

检查网络、代理和 Maven 镜像配置。首次构建需要访问 Maven Central。不要因为下载慢而并发启动很多 Maven 进程，它们可能同时修改本地仓库元数据。

### 17.10 测试显示 `Skipped: 1`

IT 没有收到真实服务地址。确认命令包含：

```text
-Dagentsflex.it.opensandbox.domain=localhost:8080
```

或：

```text
-Dagentsflex.it.aioSandbox.baseUrl=http://localhost:3000
```

### 17.11 并发测试超时

OpenSandbox 并发创建多个容器，第一次还可能准备 `execd`。检查：

- Docker 是否至少分配 6 GiB 内存；
- 是否有足够磁盘；
- Python 和 `execd` 镜像是否已提前拉取；
- Server 日志是否卡在镜像下载；
- 安全软件是否阻止 Docker 网络或端口。

## 18. 生产环境上线前检查表

实验通过不代表可以原样上线。先通过场景判断需要哪一种边界，再检查具体安全措施。

### 18.1 场景一：个人开发机上的 Skill 调试

特征：

- 只有开发者本人使用；
- Skill 和命令都由自己编写；
- 数据是测试数据；
- 更重视调试速度。

可以使用 Local Runtime，但要清楚命令直接运行在宿主机。不要把线上密钥放进测试环境，也不要复制来源不明的 Skill 直接执行。

### 18.2 场景二：公司内部的可信效率工具

特征：

- 使用者是内部员工；
- 任务需要浏览器、Jupyter、Shell 等完整工具；
- 希望环境长期保持，减少冷启动；
- 数据敏感度中等。

可以评估 AIO。`conversationId` 能降低正常任务串目录的风险，但仍要鉴权、审计，并限制谁能构造 Shell 命令。来自不同客户或互不信任部门的任务不应只靠同一个 AIO 容器中的目录分隔。

### 18.3 场景三：面向外部客户的多租户 SaaS

特征：

- 用户输入不可完全信任；
- 不同客户之间有明确数据边界；
- 需要按任务回收环境；
- 可以接受一定容器启动成本。

优先使用 OpenSandbox，为不同业务会话建立独立 Sandbox。`conversationId` 必须包含租户边界；OpenSandbox Server 必须鉴权；Conversation Store 应持久化；任务结束要销毁 Sandbox；还要限制网络、资源和镜像权限。

### 18.4 场景四：金融、医疗或高敏感代码执行

仅有容器通常不足以形成完整合规结论。还可能需要：

- 专用执行节点或微型虚拟机；
- 租户级网络分段；
- 强制访问控制和短期凭据；
- 全链路审计与不可篡改日志；
- 数据驻留、加密和销毁证明；
- 专业渗透测试和供应链扫描。

`conversationId` 在这里仍然有用，但它只是身份和状态路由的一部分。

### 18.5 三个选型问题

无法判断时，按顺序问：

1. 用户能否影响 Shell 命令或上传可执行内容？能，则不能只用 Local 或共享目录；
2. 不同任务之间是否互不信任？是，则优先独立容器或更强边界；
3. 状态需要保留多久？这决定 Store、续期、销毁和磁盘清理策略。

### 18.6 上线检查表

生产环境至少逐项确认：

- [ ] OpenSandbox Server 启用了高强度 API Key；
- [ ] AIO 启用了 JWT，或位于有强认证的内部网关后；
- [ ] 服务端口没有直接暴露公网；
- [ ] 外部访问经过 TLS；
- [ ] 镜像使用固定版本或 digest，不使用漂移的 `latest`；
- [ ] Sandbox 使用非 root 用户；
- [ ] 删除不必要的 Linux capabilities；
- [ ] 配置 CPU、内存、PID、磁盘和超时限制；
- [ ] 默认拒绝出站网络，只允许必要目标；
- [ ] 不可信租户不共享同一个 AIO 容器；
- [ ] `conversationId` 包含租户边界，并在业务范围内唯一；
- [ ] 同一会话的写操作由上层串行调度；
- [ ] OpenSandbox 使用持久化 Conversation Store 支持多 JVM；
- [ ] 会话结束时调用 `destroyConversationSandbox()`；
- [ ] 有过期 Sandbox 和会话目录清理任务；
- [ ] 密钥来自环境变量或密钥管理服务；
- [ ] 日志不会打印 Token、API Key 和用户敏感文件；
- [ ] 对命令、文件上传、产物下载建立审计日志；
- [ ] 对下载产物进行大小、类型和恶意内容检查；
- [ ] 已在接近生产的并发规模下进行压力测试。

## 19. 术语表

| 术语 | 小白解释 |
| --- | --- |
| 宿主机 | 真正运行 Docker 的电脑或服务器 |
| 镜像 | 创建容器的只读模板，类似安装光盘 |
| 容器 | 从镜像启动的隔离进程和文件系统 |
| Sandbox | 对代码执行权限和资源进行约束的运行环境 |
| Runtime | Agents-Flex 用来执行命令和访问文件的适配层 |
| `conversationId` | 业务会话的稳定唯一标识，用来选择会话工作区 |
| 工作目录 | 相对路径和 Shell 命令默认执行的位置 |
| API Key | 客户端访问 Server 时使用的共享密钥 |
| JWT | 带签名和有效期的访问令牌 |
| Mock | 不连接真实服务、只模拟请求响应的测试替身 |
| 集成测试 | 连接多个真实组件，验证它们能共同工作的测试 |
| 并发 | 多个任务在重叠时间内同时执行 |

## 20. 最短复习路径

完成一次实验后，可以用下面的顺序复习：

1. `docker version`：确认 Docker 可用；
2. 启动 OpenSandbox Server；
3. `curl http://127.0.0.1:8080/health`；
4. 在 3000 端口启动 AIO；
5. `curl -I http://127.0.0.1:3000/v1/docs`；
6. 运行 `OpenSandboxSkillRuntimeIT`；
7. 运行 `AioSandboxSkillRuntimeIT`；
8. 确认两个测试都是 `Tests run: 2, Failures: 0, Errors: 0`；
9. `docker stop agentsflex-aio-sandbox`；
10. 在 OpenSandbox 终端按 `Ctrl + C`；
11. `docker ps` 检查无测试资源残留。

## 相关文档

- [Skill Runtime](./runtime)
- [OpenSandbox 安装与配置](./open-sandbox)
- [AIO Sandbox 安装与配置](./aio-sandbox)
- [故障排查与生产建议](./troubleshooting)
- [OpenSandbox 官方文档](https://open-sandbox.ai/getting-started/)
- [AIO Sandbox 官方文档](https://sandbox.agent-infra.com/zh/guide/start/quick-start)
- [Docker 官方安装文档](https://docs.docker.com/engine/install/)

</div>
