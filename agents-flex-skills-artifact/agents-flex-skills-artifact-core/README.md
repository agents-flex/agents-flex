# Agents-Flex Skills Artifact Core

`agents-flex-skills-artifact-core` 提供对象存储无关的 Skill Artifact 安装、SHA-256 校验、
节点缓存、安全解压和并发控制。

接入新的对象存储只需实现三个以 `bucket` 为容器参数的操作：

```java
public class CustomObjectStorageOperations implements ObjectStorageOperations {

    public void put(String bucket, String key, InputStream input, long contentLength) {
        // upload
    }

    public InputStream get(String bucket, String key) {
        // download; the caller closes the returned stream
    }

    public void delete(String bucket, String key) {
        // idempotent delete
    }

    public void close() {
        // release the provider client when owned
    }
}
```

然后构造通用 Store：

```java
SkillArtifactStore store = new ObjectStorageSkillArtifactStore(
    operations,
    "skill-bucket",
    "agents-flex/skills",
    Paths.get("/var/cache/agents-flex/skills")
);
```
