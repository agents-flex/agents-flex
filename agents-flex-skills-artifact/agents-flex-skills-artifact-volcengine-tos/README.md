# Agents-Flex Skills Artifact Volcengine TOS

`agents-flex-skills-artifact-volcengine-tos` 使用火山引擎 TOS Java SDK 实现 `SkillArtifactStore`。
TOS 持久化 ZIP Skill 包，通用 Artifact Store 负责 SHA-256 校验、安全解压和当前节点缓存。

## 配置

```java
try (VolcengineTosSkillArtifactStore store = VolcengineTosSkillArtifactStore.builder()
    .region("cn-beijing")
    .endpoint("https://tos-cn-beijing.volces.com")
    .bucket("my-skill-artifacts")
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

未配置凭证时，Builder 使用 `EnvCredentialsProvider`。STS 临时凭证使用
`credentials(accessKeyId, accessKeySecret, securityToken)`。也可以传入 `CredentialsProvider`，
或者通过构造器注入由应用统一管理的 `TOSV2` Client。静态凭证不能与 Provider 同时配置。

生产环境应使用只允许目标 Bucket 前缀执行上传、下载和删除对象操作的最小权限 IAM 身份。
`SKILL.md` 必须位于 ZIP 根目录。
