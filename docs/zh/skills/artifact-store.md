<div v-pre>

# Skill Artifact Store

Artifact Store 用于安装、删除和本地物化确定版本的 Skill。单体部署可以使用文件系统实现；分布式部署可以使用对象存储实现，并在执行节点建立本地缓存。

## Maven 依赖与对象存储实现

### 通用对象存储 Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块提供 `ObjectStorageOperations` 和 `ObjectStorageSkillArtifactStore`。接入 S3、MinIO、COS 等
对象存储时，只需实现基于 `bucket`、对象 Key 的 `put`、`get`、`delete` 三个操作；SHA-256 校验、
ZIP 安全校验、节点缓存和并发锁由通用 Store 处理。

### 阿里云 OSS Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-aliyun-oss</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块使用阿里云 OSS Java SDK V2。默认从 `OSS_ACCESS_KEY_ID` 和
`OSS_ACCESS_KEY_SECRET` 环境变量读取凭证，完整配置和安装示例见模块 README。

### 腾讯云 COS Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-tencent-cos</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

Builder 需要配置 COS Region、包含 APPID 后缀的 Bucket，以及静态凭证或
`COSCredentialsProvider`。完整示例见模块 README。

### 华为云 OBS Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-huawei-obs</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

Builder 需要配置 OBS Endpoint 和 Bucket；凭证可以来自环境变量、静态 AK/SK/Token 或
`IObsCredentialsProvider`。完整示例见模块 README。

### 火山引擎 TOS Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-volcengine-tos</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

Builder 需要配置 TOS Region、Endpoint 和 Bucket；凭证可以来自环境变量、静态 AK/SK/Token 或
`CredentialsProvider`。完整示例见模块 README。

### S3 Artifact Store

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills-artifact-s3</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块使用 AWS SDK for Java V2，可连接 AWS S3、RustFS、MinIO、Ceph RGW 等 S3-compatible
对象存储。AWS S3 可以只配置 Region 和 Bucket，并使用 SDK 默认凭证链；兼容存储通常还需要配置
`endpoint(...)` 和 `forcePathStyle(true)`。完整示例见模块 README。

## 安装、加载与删除

`SkillArtifactStore` 用于把一个已安装 Skill 的确定版本转换成当前节点可读的稳定目录。单体部署可以
使用 `FileSystemSkillArtifactStore`，其中 `storageKey` 是安装根目录下的相对目录：

```java
import com.agentsflex.skill.artifact.FileSystemSkillArtifactStore;
import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillArtifactStore;
import com.agentsflex.skill.artifact.SkillInstallRequest;

import java.nio.file.Paths;

SkillArtifactStore artifactStore = new FileSystemSkillArtifactStore(
    Paths.get("/var/lib/agents-flex/skills")
);
SkillArtifact artifact = new SkillArtifact(
    "pdf",
    "1.2.0",
    "sha256-abc123",
    "pdf/1.2.0"
);

// 安装服务提供 ZIP SkillPackage，再由 Store 校验并原子持久化。
artifactStore.install(new SkillInstallRequest(
    artifact,
    new PathSkillPackage(Paths.get("/tmp/skill-upload/pdf.zip"))
));

List<Tool> tools = SkillsTool.builder()
    .addSkillArtifact(artifactStore, artifact)
    .runtime(runtime)
    .buildTools();

// 确认 Catalog 中没有版本引用后，可以幂等删除 Artifact。
artifactStore.delete(artifact);
```

安装完成后，`/var/lib/agents-flex/skills/pdf/1.2.0/SKILL.md` 存在，并且 front matter 中的名称应为
`pdf`。文件系统实现要求输入为 ZIP 且 `SKILL.md` 位于压缩包根目录，并会拒绝绝对 `storageKey`、
逃逸安装根目录的 `..` 路径以及 Zip Slip 条目。

分布式部署可以实现同一个接口，在 `materialize()` 中从 OSS、S3 或 MinIO 下载 Artifact，并解压到
当前节点的 `${cacheRoot}/{digest}` 目录。实现必须在返回前完成摘要校验，使用临时目录加原子移动避免
暴露不完整内容，并确保返回目录在本次 Skill 使用期间不会被清理。`SkillsTool` 和 `SkillRuntime` 不需要
感知 Artifact 的远程来源。远程实现的 `install()` 负责上传对象，`delete()` 负责删除对象；版本激活、
引用检查和数据库事务仍由上层 Skill Catalog 负责。

</div>
