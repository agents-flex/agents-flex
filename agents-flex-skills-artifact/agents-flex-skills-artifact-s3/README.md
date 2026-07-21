# Agents-Flex Skills Artifact S3

`agents-flex-skills-artifact-s3` 使用 AWS SDK for Java V2 实现 `SkillArtifactStore`，可连接
AWS S3，以及 RustFS、MinIO、Ceph RGW 等 S3-compatible 对象存储。对象存储持久化 ZIP Skill 包，
通用 Artifact Store 负责 SHA-256 校验、安全解压和当前节点缓存。

## AWS S3

未显式配置凭证时使用 AWS SDK 默认凭证链：

```java
try (S3SkillArtifactStore store = S3SkillArtifactStore.builder()
    .region("ap-southeast-1")
    .bucket("my-skill-artifacts")
    .keyPrefix("agents-flex/skills")
    .cacheDirectory(Paths.get("/var/cache/agents-flex/skills"))
    .build()) {

    SkillArtifact installed = store.install(new SkillInstallRequest(
        new SkillArtifact("pdf", "1.0.0", null, null),
        new PathSkillPackage(Paths.get("/tmp/pdf.zip"))
    ));
    Path localDirectory = store.materialize(installed);
}
```

## RustFS、MinIO 等兼容存储

兼容存储通常需要覆盖 Endpoint，并启用 Path-Style：

```java
S3SkillArtifactStore store = S3SkillArtifactStore.builder()
    .region("us-east-1")
    .endpoint("http://127.0.0.1:9000")
    .forcePathStyle(true)
    .bucket("skills")
    .credentials(accessKeyId, accessKeySecret)
    .build();
```

STS 临时凭证使用 `credentials(accessKeyId, accessKeySecret, securityToken)`。也可以传入
`AwsCredentialsProvider`，或者通过构造器注入由应用统一管理的 `S3Client`。静态凭证不能与
Provider 同时配置。

## 可支持的产品

`S3SkillArtifactStore` 只依赖 `PutObject`、`GetObject`、`DeleteObject`、Bucket、对象 Key 和
AWS Signature V4。因此，只要目标产品实现这些基础 S3 API，通常就可以通过自定义 Endpoint 接入。
下面列出的是适用范围说明，不代表 Agents-Flex 对所有产品版本进行了兼容性认证。

### 公有云和托管服务

| 产品 | 接入说明 |
| --- | --- |
| [AWS S3](https://aws.amazon.com/s3/) | 原生 S3；通常不需要配置 `endpoint` 和 Path-Style |
| [Cloudflare R2](https://developers.cloudflare.com/r2/api/s3/api/) | 提供 S3 API；Region 通常使用 `auto` |
| [Backblaze B2](https://www.backblaze.com/docs/cloud-storage-s3-compatible-api) | 使用 B2 S3-compatible Endpoint 和应用密钥 |
| [Wasabi](https://docs.wasabi.com/apidocs) | 使用区域 Endpoint，可配合 AWS SDK 使用 |
| [DigitalOcean Spaces](https://docs.digitalocean.com/products/spaces/reference/s3-compatibility/) | 使用 Spaces 区域 Endpoint |
| [Akamai Object Storage](https://techdocs.akamai.com/cloud-computing/docs/object-storage) | 提供 Amazon S3-compatible API |
| [Oracle OCI Object Storage](https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm) | 使用 Amazon S3 Compatibility API 和客户专属 Namespace |
| [IBM Cloud Object Storage](https://cloud.ibm.com/docs/cloud-object-storage?topic=cloud-object-storage-compatibility-api) | 支持 S3 API 子集，需使用 IBM COS Endpoint |
| Hetzner、Scaleway、OVHcloud、Vultr、Exoscale | 均提供 S3-compatible 对象存储，需按厂商文档配置 Region 和 Endpoint |

### GitHub 开源项目

| 项目 | 形态与注意事项 |
| --- | --- |
| [RustFS](https://github.com/rustfs/rustfs) | S3-compatible 分布式对象存储；官方 Java 示例要求启用 Path-Style |
| [SeaweedFS](https://github.com/seaweedfs/seaweedfs) | 分布式文件和对象存储，通过 S3 Endpoint 对外提供对象 API |
| [Ceph](https://github.com/ceph/ceph) | 通过 Ceph Object Gateway（RGW）提供 S3 API |
| [Garage](https://github.com/deuxfleurs-org/garage) | 面向小型、地理分布式部署的 S3-compatible 对象存储；GitHub 仓库是镜像 |
| [Apache Ozone](https://github.com/apache/ozone) | 通过 S3 Gateway 提供对象访问接口 |
| [OpenStack Swift](https://github.com/openstack/swift) | 通过 `s3api` 中间件提供 S3 兼容层 |
| [Scality CloudServer](https://github.com/scality/cloudserver) | Amazon S3 协议的 Node.js 实现，可连接多种后端存储 |
| [VersityGW](https://github.com/versity/versitygw) | 在本地或共享文件系统之上提供 S3 对象存储服务 |
| [S3Proxy](https://github.com/gaul/s3proxy) | 把其他存储后端通过 S3 API 暴露出来的兼容网关 |
| [NVIDIA AIStore](https://github.com/NVIDIA/aistore) | 面向 AI 工作负载的分布式存储，提供 S3-compatible 接口 |
| [Supabase Storage](https://github.com/supabase/storage) | 基于 PostgreSQL 元数据的 S3-compatible 对象存储服务 |
| [MinIO](https://github.com/minio/minio) | 高度兼容 S3；GitHub API 在 2026-07 显示该开源仓库已归档，现有部署接入前应确认所用版本的维护状态 |

GitHub 上还有 Alarik、ZS3、OtterIO、S2、LeoFS 等较新或规模较小的 S3 服务端项目。这类项目的
API 覆盖率和生产成熟度变化较快，接入前应确认至少支持 Put、Get、Delete、Signature V4 和流式上传。

### S3 生态客户端和文件系统

下面这些项目会连接或使用 S3，但本身通常不提供可供 `S3SkillArtifactStore` 连接的 S3 Endpoint，
因此不属于 Artifact 存储后端：

| 项目 | 角色 |
| --- | --- |
| [GeeseFS](https://github.com/yandex-cloud/geesefs) | 将已有 S3 Bucket 挂载成 FUSE 文件系统，是 S3 客户端 |
| [ZeroFS](https://github.com/Barre/ZeroFS) | 以 S3-compatible Bucket 作为后端，对外提供 POSIX、NFS、9P 和 NBD |
| [s3fs-fuse](https://github.com/s3fs-fuse/s3fs-fuse) | 将 S3 Bucket 挂载为 FUSE 文件系统 |
| [Mountpoint for Amazon S3](https://github.com/awslabs/mountpoint-s3) | AWS 提供的高吞吐 S3 文件客户端 |
| [Goofys](https://github.com/kahing/goofys) | POSIX-ish S3 FUSE 文件系统 |
| [rclone](https://github.com/rclone/rclone) | 在 S3 和其他存储之间同步、复制和挂载数据 |
| [s3cmd](https://github.com/s3tools/s3cmd) | S3-compatible 对象存储命令行客户端 |
| [s5cmd](https://github.com/peak/s5cmd) | 并行执行 S3 对象操作的命令行工具 |
| [Cyberduck](https://github.com/iterate-ch/cyberduck) | 支持 S3、WebDAV、Swift 等协议的桌面文件传输客户端 |

GitHub 的 [S3 Topic](https://github.com/topics/s3)、
[S3-compatible Topic](https://github.com/topics/s3-compatible) 和
[Object Storage Topic](https://github.com/topics/object-storage) 还包含大量 SDK、备份程序、文件管理器、
数据湖和使用 S3 保存数据的应用。判断能否作为 Artifact 后端时，应检查项目是否提供服务端 S3
Endpoint，而不能只看它是否带有 `s3` Topic。

### 开发和测试实现

以下项目适合验证 `S3SkillArtifactStore`，不应直接作为生产 Artifact 存储：

| 项目 | 用途 |
| --- | --- |
| [Adobe S3Mock](https://github.com/adobe/S3Mock) | Docker、Testcontainers 和 JUnit 场景中的 S3 API Mock |
| [Moto](https://github.com/getmoto/moto) | Python 测试中的 AWS 服务模拟器，可启动 S3 Server |
| [LocalStack](https://github.com/localstack/localstack) | 本地 AWS 服务模拟环境；GitHub API 在 2026-07 显示该仓库已归档，使用前需确认后续发行位置 |

## 兼容性限制

S3-compatible 只表示协议兼容程度，不保证不同产品在校验和、Region、寻址方式和错误码方面完全一致。
接入时重点检查：

- 是否需要 `forcePathStyle(true)`；
- Region 是否使用特殊值，例如 Cloudflare R2 的 `auto`；
- Endpoint 是否包含正确的区域、Namespace 或账户标识；
- AWS SDK 使用的校验和、流式签名和 STS Token 是否被目标产品支持；
- 删除不存在对象时是否保持幂等。

部署时应针对目标产品执行真实 Bucket 的上传、下载和删除集成测试。`SKILL.md` 必须位于 ZIP 根目录。
