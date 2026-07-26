<div v-pre>

# Skill Artifact Store

## 概述

开发阶段通常可以直接从本地目录加载 Skill，但进入生产环境后，应用往往还需要回答这些问题：

- 如何把一个 Skill 安装包发布给多个应用节点；
- 如何确保每次执行使用的是同一个确定版本，而不是一份随时会变化的目录；
- 新版本出现问题时，如何快速切回旧版本；
- 对象存储中的 ZIP 如何安全地下载、校验、解压和缓存；
- 一个版本不再使用时，如何删除它。

`SkillArtifactStore` 就是 Agents-Flex 提供的 Skill 安装包存储与本地物化抽象。它管理的是 **Skill
自身的安装包**，例如包含 `SKILL.md`、`scripts/` 和 `assets/` 的 ZIP，而不是 Skill 执行后生成的
PDF、PPTX、图片等文件。执行产物的交付请参阅 [Skill 产物](./files)。

一次典型的生产链路如下：

```text
开发并校验 Skill
        ↓ 打包为 ZIP
安装服务调用 install()
        ↓
文件系统或对象存储保存确定版本
        ↓ Catalog 记录当前启用的 Artifact
执行节点调用 materialize()
        ↓ 下载、校验、解压或命中本地缓存
SkillsTool 加载 Skill → SkillRuntime 准备并执行
```

Artifact Store 只负责图中的“保存、物化、删除”。版本审批、启用状态、租户授权和引用关系应由应用自己的
Skill Catalog 或数据库管理。

## 适用场景

### 单体应用管理多个 Skill 版本

应用只有一个节点，但希望通过管理后台上传 Skill，并保留 `1.0.0`、`1.1.0` 等多个版本。使用
`FileSystemSkillArtifactStore` 后，每个版本安装到独立目录，业务只需切换 Catalog 中的当前版本即可回滚。

### 多节点共享同一套 Skill

执行节点会弹性扩缩容，不能依赖某一台机器上的固定目录。可以把 Skill ZIP 保存在 OSS、COS、OBS、TOS、
S3 或 S3-compatible 存储中。节点第一次使用某个版本时下载并按摘要缓存，后续请求直接使用本地缓存。

### Skill 市场或企业内部能力中心

平台允许团队上传合同审查、周报生成、数据分析等 Skill。Catalog 保存名称、版本、状态和权限，Artifact
Store 保存实际安装包。控制面与安装包存储分离后，可以独立实现审批、灰度发布和审计。

### 多租户固定版本与灰度发布

租户 A 可以继续使用 `report@1.4.0`，租户 B 先试用 `report@1.5.0`。请求进入时根据租户解析出确定的
`SkillArtifact`，再交给 `SkillsTool`，无需覆盖公共目录或在运行时修改 Skill 内容。

### 不需要 Artifact Store 的情况

如果 Skill 随应用代码一起发布、不会在线更新，并且只有一个固定目录，直接使用
`addSkillsDirectory(...)` 更简单。Artifact Store 不是使用 Skills 的必选组件，也不会创建 Sandbox；
执行隔离仍由 [Skill Runtime](./runtime) 负责。

## 快速开始：文件系统存储

先用文件系统实现完成最短链路。它适合本地开发、单节点服务，或安装目录位于可靠共享文件系统的场景。

### 1. 添加依赖

`FileSystemSkillArtifactStore` 位于 Skills 核心模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 准备 Skill ZIP

ZIP 根目录必须直接包含 `SKILL.md`，不能在外面再套一层目录：

```text
pdf.zip
├── SKILL.md
├── scripts/
│   └── render.py
└── assets/
    └── template.html
```

其中 `SKILL.md` 的 front matter 名称应与 Artifact 名称一致：

```markdown
---
name: pdf
description: Create and validate PDF documents.
---
```

### 3. 安装并加载 Skill

下面的示例把 `pdf.zip` 安装为 `pdf@1.2.0`，然后注册到 `SkillsTool`：

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.SkillsTool;
import com.agentsflex.skill.artifact.FileSystemSkillArtifactStore;
import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillArtifactStore;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import com.agentsflex.skill.local.LocalSkillRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

Path installRoot = Paths.get("/var/lib/agents-flex/skills");
Files.createDirectories(installRoot);

SkillArtifactStore artifactStore = new FileSystemSkillArtifactStore(installRoot);
SkillArtifact artifact = new SkillArtifact(
    "pdf",              // Skill 名称
    "1.2.0",            // 业务版本
    null,               // 文件系统实现当前不校验 digest
    "pdf/1.2.0"         // installRoot 下的相对目录
);

artifactStore.install(new SkillInstallRequest(
    artifact,
    new PathSkillPackage(Paths.get("/tmp/pdf.zip"))
));

try (LocalSkillRuntime runtime = new LocalSkillRuntime()) {
    List<Tool> tools = SkillsTool.builder()
        .addSkillArtifact(artifactStore, artifact)
        .runtime(runtime)
        .buildTools();

    prompt.addTools(tools);
    // 把 prompt 交给支持 Tool Calling 的 ChatModel。
}
```

`addSkillArtifact(...)` 内部会先调用 `materialize()` 得到本机目录，再检查其中是否存在指定名称的 Skill，
最后交给 Runtime 准备。安装完成后，目录结构为：

```text
/var/lib/agents-flex/skills/
└── pdf/
    └── 1.2.0/
        ├── SKILL.md
        ├── scripts/
        └── assets/
```

安装根目录必须预先存在。相同 `storageKey` 已存在时，安装会失败，避免一个已发布版本被静默覆盖。

### 4. 删除不再使用的版本

确认 Catalog 中已经没有请求引用该版本后再删除：

```java
artifactStore.delete(artifact);
```

`delete()` 是幂等操作，目标不存在时也视为成功。不要在仍有请求使用该目录时删除它。

## 核心概念

### SkillArtifact

`SkillArtifact` 是一个已安装 Skill 的确定版本引用：

| 字段 | 含义 | 文件系统实现 | 对象存储实现 |
| --- | --- | --- | --- |
| `name` | Skill 名称，必须与 `SKILL.md` 一致 | 必填 | 必填 |
| `version` | 业务版本，例如 `1.2.0` | 建议填写 | 建议填写 |
| `digest` | ZIP 内容的 SHA-256 摘要 | 当前不校验 | 安装时计算并校验，物化时必填 |
| `storageKey` | 存储位置 | 安装根目录下的相对目录 | Bucket 中的对象 Key |
| `size` | ZIP 字节数 | 不使用 | 用于下载完整性校验 |

对象存储的 `install()` 会返回补全了 `digest`、`storageKey` 和 `size` 的新 `SkillArtifact`。应用必须持久化
并在后续加载时使用这个返回值，不能继续使用安装前字段为空的对象。

### 三个核心操作

| 操作 | 调用时机 | 结果 |
| --- | --- | --- |
| `install(request)` | 上传或发布新版本时 | 校验并持久化 ZIP，返回已安装 Artifact |
| `materialize(artifact)` | 构建本次请求所需工具时 | 返回当前应用节点可读、包含 `SKILL.md` 的稳定目录 |
| `delete(artifact)` | 版本已停用且无引用时 | 删除持久化内容；远程实现同时删除当前节点对应缓存 |

“物化”并不等于执行。它只是让远程 Artifact 变成当前 Java 应用节点可读取的目录。之后
`SkillRuntime.prepare()` 可能还会把这个目录复制或上传到本地会话、容器或远程 Sandbox。

### Artifact Store、Catalog 与 Runtime 的职责

| 组件 | 负责 | 不负责 |
| --- | --- | --- |
| Artifact Store | ZIP 的安装、存储、本地物化、删除 | 版本审批、启用状态、租户权限 |
| Skill Catalog | 名称与版本索引、当前版本、灰度规则、引用关系 | 解压、安全校验、执行命令 |
| Skill Runtime | 准备执行目录、Shell 与文件工具、Sandbox 生命周期 | 保存 Skill 的历史版本 |

Agents-Flex 当前提供 Artifact Store API，但不内置 Skill Catalog。简单应用可以用数据库表保存
`name`、`version`、`digest`、`storageKey`、`size` 和 `status`；复杂平台可以在此基础上增加租户、审批人与
发布时间等字段。

## 选择存储实现

| 部署方式 | 推荐实现 | 特点 |
| --- | --- | --- |
| 本地开发、单节点 | `FileSystemSkillArtifactStore` | 配置最少，直接使用安装目录 |
| 多节点、云上部署 | 对应云厂商 Artifact Store | 共享存储，自动摘要校验与节点缓存 |
| MinIO、RustFS、Ceph RGW 等 | `S3SkillArtifactStore` | 通过自定义 Endpoint 接入 S3-compatible API |
| 已有自研对象存储 SDK | `ObjectStorageSkillArtifactStore` | 只需适配 `put`、`get`、`delete` |

对象存储实现都把原始 ZIP 保存在 Bucket 中，并在当前节点按内容摘要建立缓存。首次物化需要下载，后续
使用相同摘要时直接命中缓存。JVM 锁和文件锁会避免同一缓存目录中的并发重复下载。

## 快速开始：S3 与 S3-compatible 存储

### 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-s3</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块使用 AWS SDK for Java V2，可连接 AWS S3、MinIO、RustFS、Ceph RGW 等提供基础 S3 API 的存储。

### AWS S3 示例

未显式配置凭证时使用 AWS SDK 默认凭证链：

```java
import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import com.agentsflex.skill.artifact.s3.S3SkillArtifactStore;

import java.nio.file.Paths;

try (S3SkillArtifactStore store = S3SkillArtifactStore.builder()
    .region("ap-southeast-1")
    .bucket("my-skill-artifacts")
    .keyPrefix("agents-flex/skills")
    .cacheDirectory(Paths.get("/var/cache/agents-flex/skills"))
    .build()) {

    SkillArtifact installed = store.install(new SkillInstallRequest(
        new SkillArtifact("pdf", "1.2.0", null, null),
        new PathSkillPackage(Paths.get("/tmp/pdf.zip"))
    ));

    // 必须把 installed 的 digest、storageKey 和 size 持久化到 Catalog。
    skillCatalog.save(installed);

    List<Tool> tools = SkillsTool.builder()
        .addSkillArtifact(store, installed)
        .runtime(runtime)
        .buildTools();
}
```

如果安装时没有指定 `storageKey`，Store 会生成类似下面的对象 Key：

```text
agents-flex/skills/pdf/1.2.0/<sha256>.zip
```

### MinIO 等兼容存储

S3-compatible 存储通常还需要指定 Endpoint，并启用 Path-Style：

```java
S3SkillArtifactStore store = S3SkillArtifactStore.builder()
    .region("us-east-1")
    .endpoint("http://127.0.0.1:9000")
    .forcePathStyle(true)
    .bucket("skills")
    .credentials(accessKeyId, accessKeySecret)
    .cacheDirectory(Paths.get("/var/cache/agents-flex/skills"))
    .build();
```

不同兼容产品对 Region、Path-Style、签名和 STS Token 的支持并不完全相同，上线前应针对真实 Bucket 执行
上传、下载和删除集成测试。

## 其他对象存储实现

各模块都提供 Builder，并复用相同的摘要校验、安全解压与节点缓存逻辑：

| 存储 | Maven artifactId | Builder 必要配置 | 凭证方式 |
| --- | --- | --- | --- |
| 阿里云 OSS | `agents-flex-skills-artifact-aliyun-oss` | `region`、`bucket` | 环境变量、静态 AK/SK/Token 或 Provider |
| 腾讯云 COS | `agents-flex-skills-artifact-tencent-cos` | `region`、含 APPID 的 `bucket` | 静态密钥、STS 或 `COSCredentialsProvider` |
| 华为云 OBS | `agents-flex-skills-artifact-huawei-obs` | `endpoint`、`bucket` | 环境变量、静态 AK/SK/Token 或 Provider |
| 火山引擎 TOS | `agents-flex-skills-artifact-volcengine-tos` | `region`、`endpoint`、`bucket` | 环境变量、静态 AK/SK/Token 或 Provider |
| AWS S3 / S3-compatible | `agents-flex-skills-artifact-s3` | `region`、`bucket`，兼容存储通常还需 `endpoint` | 默认凭证链、静态凭证、STS 或 Provider |

下面以阿里云 OSS 为例：

```java
try (AliyunOssSkillArtifactStore store = AliyunOssSkillArtifactStore.builder()
    .region("cn-hangzhou")
    .bucket("my-skill-artifacts")
    .keyPrefix("agents-flex/skills")
    .cacheDirectory(Paths.get("/var/cache/agents-flex/skills"))
    .build()) {

    SkillArtifact installed = store.install(new SkillInstallRequest(
        new SkillArtifact("pdf", "1.2.0", null, null),
        new PathSkillPackage(Paths.get("/tmp/pdf.zip"))
    ));
}
```

阿里云实现默认读取 `OSS_ACCESS_KEY_ID` 和 `OSS_ACCESS_KEY_SECRET`。生产环境应为所有对象存储实现配置只
允许目标 Bucket 前缀执行上传、下载和删除的最小权限身份，不要把长期密钥写入代码。

## 接入自定义对象存储

已有内部文件中心或尚未提供适配器的对象存储时，添加 `agents-flex-skills-artifact-core`：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

实现最小的 `ObjectStorageOperations` 接口：

```java
public class CustomObjectStorageOperations implements ObjectStorageOperations {

    @Override
    public void put(String bucket, String key, InputStream input, long contentLength) {
        // 流式上传；返回时对象必须可读。
    }

    @Override
    public InputStream get(String bucket, String key) {
        // 返回下载流，调用方负责关闭。
    }

    @Override
    public void delete(String bucket, String key) {
        // 删除不存在的对象也应视为成功。
    }

    @Override
    public void close() {
        // 仅在当前实现拥有客户端时释放资源。
    }
}
```

再交给通用 Store：

```java
SkillArtifactStore store = new ObjectStorageSkillArtifactStore(
    new CustomObjectStorageOperations(),
    "skill-bucket",
    "agents-flex/skills",
    Paths.get("/var/cache/agents-flex/skills")
);
```

SHA-256 计算与校验、包大小限制、安全解压、原子发布、节点缓存和并发锁都由通用 Store 处理。

## 生产发布与回滚

推荐把“上传新版本”和“启用新版本”拆成两个动作：

1. 校验 `SKILL.md`、脚本和依赖，并把目录打包为 ZIP。
2. 调用 `install()`，把返回的完整 `SkillArtifact` 写入 Catalog，状态记为 `STAGED`。
3. 在测试请求中调用 `materialize()` 并执行验收任务。
4. 把部分租户或流量指向新 Artifact，观察执行成功率和产物质量。
5. 验证通过后将其设为默认版本；旧版本继续保留一段时间。
6. 出现问题时只需让 Catalog 重新返回旧 Artifact，不必覆盖或重新上传文件。
7. 保留期结束且确认无引用后，再调用 `delete()` 清理旧版本。

Artifact 应按内容和版本保持不可变。不要让 `pdf@1.2.0` 的 `storageKey` 后来指向另一份 ZIP；否则节点缓存、
审计记录和回滚结果都会失去确定性。

批量加载多个已安装 Skill 时，可以使用：

```java
List<SkillArtifact> enabledArtifacts = skillCatalog.findEnabled(tenantId);

List<Tool> tools = SkillsTool.builder()
    .addSkillArtifacts(artifactStore, enabledArtifacts)
    .runtime(runtime)
    .buildTools();
```

Catalog 应先完成租户权限和版本选择，只把本次请求真正需要的 Artifact 传给 `SkillsTool`。

## 安全与限制

### 安装包校验

文件系统与对象存储实现都要求输入为 ZIP，并进行以下保护：

- `SKILL.md` 必须位于 ZIP 根目录；
- 拒绝 Zip Slip、绝对路径和逃逸安装根目录的 `..` 路径；
- 最多解压 10000 个条目，解压后总大小最多 512 MiB；
- 使用临时目录和移动操作，避免并发读取者看到半安装内容；
- 拒绝覆盖已经存在的文件系统 Artifact。

对象存储实现还会限制 ZIP 包大小，安装时计算 SHA-256，物化时重新校验摘要和可选的字节数。文件系统实现
当前不使用 `digest` 校验 ZIP 内容，因此上传入口或 Catalog 仍需校验来源和摘要。

### 生命周期注意事项

- `materialize()` 返回的目录在本次 Skill 使用期间必须保持存在；不要让清理任务删除正在使用的缓存。
- 对象存储 Store 实现了 `AutoCloseable`。它拥有底层客户端时，应在应用关闭时调用 `close()`。
- `delete()` 不检查 Catalog 引用，也不参与数据库事务；先处理引用关系，再删除对象。
- Artifact Store 不校验 Skill 脚本是否可信。第三方 Skill 应配合受限身份和远程 Sandbox 执行。
- 多节点各自维护本地缓存；删除远程 Artifact 不会主动广播清理其他仍在运行节点的缓存。

## 常见问题

### 每次请求都会从对象存储下载吗？

不会。对象存储实现按 SHA-256 摘要缓存，节点第一次物化时下载，后续相同内容直接使用本地目录。

### 能否只把一个现有目录交给 Artifact Store？

安装 API 接收 Skill ZIP。开发阶段若已经有目录且不需要版本管理，可以直接使用
`addSkillsDirectory(...)`，无需先打包。

### Artifact Store 会自动选择最新版本吗？

不会。应用或 Catalog 必须先选出确定的 `SkillArtifact`。这使灰度、回滚和请求复现都不会受到“最新版本”
变化的影响。

### 它和 FilePublisher 有什么区别？

Artifact Store 保存 Agent 要使用的 Skill 安装包；`FilePublisher` 发布 Agent 执行后生成的文件。前者是
运行输入，后者是交付输出，二者可以使用同一个对象存储服务，但生命周期和权限应分别管理。

## 下一步

- [了解 Skill 目录与打包方式](./skill-package)
- [选择 Skill Runtime](./runtime)
- [配置 Runtime Workspace](./skill-runtime-workspace)
- [交付 Skill 生成的文件](./files)
- [排查 Skills 常见问题](./troubleshooting)

</div>
