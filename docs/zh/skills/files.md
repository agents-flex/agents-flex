<div v-pre>

# Skill 产物

Skill 执行过程中可能生成文本报告、JSON、图片、PPTX、PDF、压缩包等文件。这些文件位于
`SkillRuntime` 的文件系统中：Local Runtime 对应宿主机文件，远程 Runtime 则对应 Sandbox 内的文件。

Agents-Flex 提供三种常见的产物交付方式：

- 使用 `SkillRuntimeFileSystem` 读取文本或小型二进制文件；
- 下载到应用节点，或者流式传输到对象存储和第三方文件中心；
- 配置 `FilePublisher`，让模型通过 `PublishFile` 主动发布最终文件并返回 URL。

无论采用哪一种方式，传给文件 API 的源路径都是 Runtime 内路径，不一定是 Java 应用所在主机能够
直接访问的路径。

## 读取 Runtime 产物

### 文本 API

```java
SkillRuntimeFileSystem fileSystem = runtime.getFileSystem();

String text = fileSystem.readText("/runtime/path/report.txt", 1024 * 1024);
fileSystem.writeText("/runtime/path/result.json", "{\"ok\":true}");
SkillFileInfo info = fileSystem.stat("/runtime/path/result.json");
List<SkillFileInfo> entries = fileSystem.listFiles("/runtime/path", 10, 1000);
```

### 读取小型二进制文件

```java
byte[] bytes = fileSystem.readBytes("/runtime/path/image.png", 5 * 1024 * 1024);
```

`maxBytes` 是强制内存上限。大型文件不要使用 `readBytes`。

## 下载产物

### 下载到本地文件

```java
import java.nio.file.Path;
import java.nio.file.Paths;

Path localFile = fileSystem.download(
    "/runtime/output/report.pptx",
    Paths.get("output/report.pptx")
);

System.out.println(localFile.toAbsolutePath());
```

下载先写同目录 `.part` 临时文件，完成后再替换最终文件，避免失败时留下半个目标文件。

### 流式传输到第三方存储

`download(String, OutputStream)` 不会关闭调用方传入的目标流，因此可以直接接对象存储 SDK：

```java
try (OutputStream objectStorage = objectStorageClient.openUpload(
        "reports/runtime-report.pptx")) {
    fileSystem.download("/runtime/output/report.pptx", objectStorage);
}
```

也可以直接读取流：

```java
try (InputStream input = fileSystem.openInputStream("/runtime/output/report.pptx")) {
    thirdPartyFileCenter.upload("runtime-report.pptx", input);
}
```

调用方必须关闭 `openInputStream` 返回的流。对于远程 Runtime，关闭动作还会释放 HTTP 连接。

## 通过 FilePublisher 发布 URL

业务代码主动调用 `download()` 适合固定工作流。如果希望模型在生成并验证文件后主动完成交付，可以
配置 `FilePublisher`。配置后，`SkillsTool.buildTools()` 会增加一个 `PublishFile` Tool；
未配置时不会暴露该工具。

```java
import com.agentsflex.skill.file.FilePublishRequest;
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;

FilePublisher publisher = new FilePublisher() {
    @Override
    public PublishedFile publish(FilePublishRequest request) {
        String objectKey = attachmentCenter.upload(
                request.getFileName(),
                request.getContentType(),
                request.getContentLength(),
                request.getInputStream()
        );

        return PublishedFile.builder()
                .url(attachmentCenter.createDownloadUrl(objectKey))
                .storageKey(objectKey)
                .fileName(request.getFileName())
                .contentType(request.getContentType())
                .contentLength(request.getContentLength())
                .expiresAt(System.currentTimeMillis() + 60 * 60_000L)
                .build();
    }
};

prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory)
    .runtime(runtime)
    .filePublisher(publisher)
    .buildTools());
```

### S3-compatible 发布示例

下面的示例使用 AWS SDK for Java V2，把 Runtime 产物流式上传到 S3-compatible 服务，再返回一小时
有效的预签名下载 URL。AWS S3、RustFS、MinIO、Ceph RGW 等提供 S3 API 的服务都可以采用这种方式。

`agents-flex-skills-artifact-s3` 保存的是 Skill 安装包，不会自动发布 Skill 执行产生的文件。实现
`FilePublisher` 时应直接使用应用管理的 S3 Client；如果项目尚未引入 AWS SDK，可以添加：

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.48.4</version>
</dependency>
```

先创建上传客户端和 URL 签名器。凭证由 AWS SDK 默认凭证链读取，例如环境变量
`AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY` 和可选的 `AWS_SESSION_TOKEN`：

```java
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

AwsCredentialsProvider credentials = DefaultCredentialsProvider.create();
Region region = Region.of(System.getenv().getOrDefault("S3_REGION", "us-east-1"));
URI endpoint = URI.create(System.getenv("S3_ENDPOINT"));

S3Configuration s3Configuration = S3Configuration.builder()
    .pathStyleAccessEnabled(true)
    .build();

S3Client s3Client = S3Client.builder()
    .region(region)
    .endpointOverride(endpoint)
    .credentialsProvider(credentials)
    .serviceConfiguration(s3Configuration)
    .build();

S3Presigner s3Presigner = S3Presigner.builder()
    .region(region)
    .endpointOverride(endpoint)
    .credentialsProvider(credentials)
    .serviceConfiguration(s3Configuration)
    .build();
```

AWS S3 通常不需要 `endpointOverride(...)`，并且可以关闭 Path-Style。S3-compatible 服务一般需要使用
厂商提供的 Endpoint；该地址还必须能被最终下载文件的用户访问，否则生成的 URL 只在内网有效。

然后实现 `FilePublisher`：

```java
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.util.UUID;

String bucket = System.getenv("S3_BUCKET");
String keyPrefix = "agents-flex/outputs/";
Duration urlTtl = Duration.ofHours(1);

FilePublisher s3Publisher = request -> {
    long contentLength = request.getContentLength();
    if (contentLength < 0) {
        throw new IllegalArgumentException("S3 upload requires a known content length");
    }

    // 不使用模型提供的文件名构造对象 Key，避免覆盖和路径注入。
    String objectKey = keyPrefix + UUID.randomUUID();

    PutObjectRequest.Builder putObject = PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .contentLength(contentLength);
    if (request.getContentType() != null) {
        putObject.contentType(request.getContentType());
    }

    s3Client.putObject(
        putObject.build(),
        RequestBody.fromInputStream(request.getInputStream(), contentLength)
    );

    GetObjectRequest getObject = GetObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .build();
    PresignedGetObjectRequest signed = s3Presigner.presignGetObject(
        GetObjectPresignRequest.builder()
            .signatureDuration(urlTtl)
            .getObjectRequest(getObject)
            .build()
    );

    return PublishedFile.builder()
        .url(signed.url().toString())
        .storageKey(objectKey)
        .fileName(request.getFileName())
        .contentType(request.getContentType())
        .contentLength(contentLength)
        .expiresAt(System.currentTimeMillis() + urlTtl.toMillis())
        .build();
};

prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory)
    .runtime(runtime)
    .filePublisher(s3Publisher)
    .buildTools());
```

`PublishFile` 通常能够通过 Runtime 的文件元数据取得准确长度。如果自定义 Runtime 返回
`contentLength = -1`，不要为了计算长度把大型文件整体读入内存；应先同步落入受控临时文件，或改用
S3 multipart upload。`S3Client` 和 `S3Presigner` 应由应用统一管理，并在应用关闭时调用 `close()`。

模型完成文件后可以调用：

```json
{
  "filePath": "/home/gem/workspace/skills/output/report.pptx",
  "fileName": "runtime-report.pptx",
  "contentType": "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}
```

Tool 会先通过 Runtime 文件系统确认路径存在且不是目录，再打开二进制流并构造
`FilePublishRequest`。请求包含：

| 字段 | 说明 |
| --- | --- |
| `inputStream` | Runtime 文件流，只能同步消费一次 |
| `fileName` | 用户看到的文件名，Tool 会去掉目录部分 |
| `contentType` | 模型指定或根据扩展名推断的 MIME 类型 |
| `contentLength` | Runtime 报告的文件大小，未知时可以是 `-1` |
| `sourcePath` | Runtime 内原始路径，用于审计和策略判断 |
| `runtimeName` | `local`、`open-sandbox`、`aio-sandbox` 或自定义名称 |
| `checksum` | 可选内容校验值 |
| `metadata` | 租户、会话和业务分类等扩展信息 |

流的生命周期约定：

1. Publisher 必须在 `publish()` 返回前完整消费 InputStream；
2. Publisher 不得关闭 InputStream；
3. Publisher 不得缓存该流或交给异步线程继续读取；
4. Tool 在 `publish()` 返回或抛出异常后自动关闭流；
5. 异步上传实现应先同步落盘或改用独立的可重复打开内容源，而不是持有当前流。

### 发布安全边界

`FilePublisher` 是最终的发布安全边界。应用实现至少应根据 `sourcePath`、租户和文件信息限制
允许发布的目录、类型与大小；重新生成对象存储 Key；设置 URL 有效期；执行病毒或恶意文件扫描；
不得直接信任模型提供的文件名。对于本机发布，实现可以先把流保存到受控静态目录，再根据应用配置的
HTTP `baseUrl` 返回地址，但不应返回只有宿主机能够访问的 `file://` URL。

## Skill 目录上传注意事项

远程 Runtime 准备 Skill 时会上传完整目录，而不只是 `SKILL.md`。默认会忽略 `.git`、`.env`、
`.env.*` 和名称以 `credentials` 开头的文件，不跟随符号链接，并尽可能保留脚本执行权限。

这些规则只能避免部分常见误操作，不能识别任意名称的密钥或业务数据。上传前仍应审计 Skill 目录，
通过 Runtime 环境变量或凭据服务注入密钥，并为 Sandbox 设置最小文件权限、资源限制和出站网络策略。

</div>
