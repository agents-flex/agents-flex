# Agents-Flex Skills Artifact Aliyun OSS

`agents-flex-skills-artifact-aliyun-oss` 使用阿里云 OSS Java SDK V2 实现 `SkillArtifactStore`。
它继承通用的 `ObjectStorageSkillArtifactStore`，本模块只保留 OSSClient 适配和 Builder。
OSS 保存 ZIP Skill 包，当前应用节点按 SHA-256 缓存并安全解压 Skill 目录。

## 配置

默认 Builder 从 `OSS_ACCESS_KEY_ID` 和 `OSS_ACCESS_KEY_SECRET` 环境变量读取凭证：

```java
try (AliyunOssSkillArtifactStore store = AliyunOssSkillArtifactStore.builder()
    .region("cn-hangzhou")
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

也可以直接配置长期 AccessKey：

```java
.credentials(accessKeyId, accessKeySecret)
```

或者配置 STS 临时凭证：

```java
.credentials(accessKeyId, accessKeySecret, securityToken)
```

等价的 `accessKeyId(...)`、`accessKeySecret(...)` 和 `securityToken(...)` 方法可用于配置属性绑定。
静态凭证不能与 `credentialsProvider(...)` 同时配置。

生产环境应使用只允许目标 Bucket 前缀执行 `PutObject`、`GetObject` 和 `DeleteObject` 的 RAM 身份，
不要使用阿里云主账号 AccessKey。`SKILL.md` 必须位于 ZIP 根目录。
