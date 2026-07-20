# Agents-Flex Skills Artifact Tencent COS

`agents-flex-skills-artifact-tencent-cos` 使用腾讯云 COS Java SDK 实现 `SkillArtifactStore`。
COS 持久化 ZIP Skill 包，通用 Artifact Store 负责 SHA-256 校验、安全解压和当前节点缓存。

## 配置

```java
try (TencentCosSkillArtifactStore store = TencentCosSkillArtifactStore.builder()
    .region("ap-guangzhou")
    .bucket("my-skill-artifacts-1250000000")
    .keyPrefix("agents-flex/skills")
    .cacheDirectory(Paths.get("/var/cache/agents-flex/skills"))
    .credentials(accessKeyId, accessKeySecret)
    .build()) {

    SkillArtifact installed = store.install(new SkillInstallRequest(
        new SkillArtifact("pdf", "1.0.0", null, null),
        new PathSkillPackage(Paths.get("/tmp/pdf.zip"))
    ));
    Path localDirectory = store.materialize(installed);
}
```

STS 临时凭证使用 `credentials(accessKeyId, accessKeySecret, securityToken)`。也可以传入
`COSCredentialsProvider`，或者通过构造器注入由应用统一管理的 `COSClient`。
静态凭证不能与 Provider 同时配置。Bucket 名称需要包含腾讯云 APPID 后缀。

生产环境应使用只允许目标 Bucket 前缀执行上传、下载和删除对象操作的最小权限子账号或角色。
`SKILL.md` 必须位于 ZIP 根目录。
