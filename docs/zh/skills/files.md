<div v-pre>

# Skill 产物

## 概述

Skill 执行后经常会生成文件，例如数据分析报告、JSON、图片、PPTX、PDF、Excel 或压缩包。这些文件最初都
位于 `SkillRuntime` 的文件系统中：

- Local Runtime 的文件位于 Java 应用所在的宿主机；
- OpenSandbox Runtime 的文件位于临时 Sandbox；
- AIO Runtime 的文件位于远程 AIO 服务或容器。

模型可能知道一个 Runtime 内部路径，例如：

```text
/home/gem/workspace/conversations/c-1024/output/report.pptx
```

但这个路径通常不能被最终用户访问，也不一定存在于 Java 应用节点。应用需要把 Runtime 文件读取、下载或
发布到用户能够访问的位置，这个过程就是 **产物交付**。

Agents-Flex 提供三种交付方式：

| 方式 | 适合场景 | 结果 |
| --- | --- | --- |
| `readText()` / `readBytes()` | 业务代码需要消费小型内容 | Java 字符串或字节数组 |
| `download()` / `openInputStream()` | 固定工作流主动回收文件 | 本地文件或上传流 |
| `FilePublisher` + `publish_file` | 让模型在完成任务后主动交付 | 用户可访问 URL |

```text
Skill 在 Runtime 中生成文件
        ↓
确认文件存在并完成校验
        ↓
 ┌──────┴───────────┐
业务代码主动处理    模型调用 publish_file
 ↓                   ↓
读取 / 下载 / 上传   FilePublisher 保存文件
 ↓                   ↓
进入后续业务流程      返回用户可访问 URL
```

本章管理的是 **Skill 执行输出**。如果需要保存、分发和版本化 Skill 自身的 ZIP 安装包，请使用
[Skill Artifact Store](./artifact-store)。二者可以使用同一个对象存储服务，但用途和生命周期不同。

## 适用场景

### 固定报表工作流

每天定时运行一个周报 Skill，业务系统知道最终文件固定为 `output/weekly-report.xlsx`。模型完成后，Java 代码
调用 `download()` 把文件保存到归档目录，再由邮件服务发送。这类流程路径和后续动作都很确定，适合业务代码
主动回收。

### 对话中生成附件

用户在聊天中要求“生成一份 PPTX 发给我”。文件名和格式由本次任务决定，应用难以提前知道最终路径。配置
`FilePublisher` 后，模型可以在生成并验证文件后调用 `publish_file`，得到下载 URL 并直接交付给用户。

### 远程 Sandbox 生成大型文件

视频、PPTX 或数据包位于远程 Sandbox，文件可能有数百 MiB。此时应使用 `openInputStream()` 或
`download(..., OutputStream)` 流式传输到对象存储，避免先把整个文件读入 Java 堆。

### 后端继续处理结构化结果

Skill 生成一个小型 `result.json`，后端需要解析状态、写入数据库或触发下一步任务。可以使用带大小上限的
`readText()`，而不需要先发布 URL。

### 临时分享敏感文件

合同、财务报表等文件不适合永久公开。`FilePublisher` 可以保存私有对象，并返回短期有效的签名 URL；应用还
可以在发布前执行租户鉴权、病毒扫描、类型检查和审计。

### 不需要交付的中间文件

渲染图片、临时 JSON、解压目录和校验日志只是任务过程的一部分，不应默认发布。只交付最终文件可以减少信息
泄露，也能避免用户收到多个含义不明的下载链接。

## 快速开始：下载到应用节点

先从最直接的固定路径下载开始。以下示例适用于 Local、OpenSandbox、AIO Sandbox 和自定义 Runtime。

### 1. 添加依赖

文件 API 位于 Skills 核心模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-skills</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 在 Runtime 关闭前下载文件

假设 Skill 已经生成 `output/report.pptx`：

```java
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.nio.file.Path;
import java.nio.file.Paths;

try (SkillRuntime runtime = createRuntime()) {
    prompt.addTools(SkillsTool.builder()
        .addSkillsDirectory(skillsDirectory, "report-generator")
        .runtime(runtime)
        .buildTools());

    // 执行模型和工具调用循环，并取得 Runtime 中的实际产物路径。
    String runtimePath = runAgentAndGetOutputPath(prompt);

    SkillRuntimeFileSystem files = runtime.getFileSystem();
    SkillFileInfo info = files.stat(runtimePath);
    if (info == null || info.isDirectory()) {
        throw new IllegalStateException("Expected report was not generated");
    }

    Path localFile = files.download(
        runtimePath,
        Paths.get("archive/report.pptx")
    );
    System.out.println(localFile);
}
```

第一个参数始终是 **Runtime 内路径**，第二个参数才是 Java 应用节点上的目标路径。`download()` 会先写入
目标目录中的 `.part` 临时文件，成功后再替换最终文件，避免下载中断时留下看似完整的产物。

文件必须在 Runtime 关闭前读取或下载。尤其是非会话模式的 OpenSandbox Runtime，`close()` 会销毁本次
Sandbox，之后无法再取回其中的文件。

### 3. 根据业务需要继续处理

下载后的本地路径可以进入确定的后续流程：

```java
emailService.sendAttachment(userEmail, localFile);
archiveService.record(taskId, localFile);
```

如果业务并不知道最终文件路径，或者希望模型自主决定何时交付，则使用后文的 `FilePublisher`。

## 选择交付方式

| 需求 | 推荐方式 | 原因 |
| --- | --- | --- |
| 读取小型 Markdown、CSV、JSON | `readText()` | 直接进入业务逻辑，并强制限制大小 |
| 读取缩略图、小型二进制 | `readBytes()` | 使用方便，但必须设置内存上限 |
| 保存到应用节点 | `download(path, Path)` | 自动使用临时文件，适合归档和附件 |
| 上传到对象存储或文件中心 | `download(path, OutputStream)` | 全程流式传输，不占用等同文件大小的堆内存 |
| 需要自行控制源流 | `openInputStream()` | 可接自定义 SDK；调用方负责关闭 |
| 最终路径和文件名由 Agent 决定 | `FilePublisher` | 模型可通过 `publish_file` 主动交付 URL |

固定工作流优先由业务代码主动处理，结果更容易测试和审计。对话式、开放式任务中，最终文件可能由模型根据
用户要求动态生成，此时 `publish_file` 更自然。

## 读取和检查 Runtime 文件

### 读取文本

```java
SkillRuntimeFileSystem files = runtime.getFileSystem();

String json = files.readText("output/result.json", 1024 * 1024);
files.writeText("output/status.json", "{\"status\":\"accepted\"}");
```

`maxBytes` 是强制上限，不是初始缓冲区大小。文件超过限制时读取会失败，防止大型文件占满 Java 堆或模型上下文。

### 查询元数据和列出文件

不要通过捕获下载异常来判断文件是否存在，先使用 `stat()`：

```java
SkillFileInfo info = files.stat("output/report.pdf");
if (info != null && !info.isDirectory()) {
    System.out.println(info.getSize());
}

List<SkillFileInfo> entries = files.listDirectory("output", 2, 1000);
```

目录深度和最大结果数都应保持有界。包含大量中间文件的目录不应无限递归，否则会消耗内存和模型上下文。

### 读取小型二进制

```java
byte[] thumbnail = files.readBytes("output/preview.png", 5 * 1024 * 1024);
```

`readBytes()` 只适合确实较小的图片、签名或压缩数据。大型二进制文件应使用流式 API。

## 流式传输大型产物

### 写入第三方 OutputStream

`download(String, OutputStream)` 会关闭 Runtime 的源输入流，但不会关闭调用方传入的目标流：

```java
try (OutputStream destination = objectStorageClient.openUpload(
        "reports/runtime-report.pptx")) {
    files.download("output/report.pptx", destination);
}
```

这适合对象存储 SDK、HTTP 响应、企业文件中心和加密流。调用方负责刷新并关闭目标流。

### 直接打开输入流

需要把源流交给已有上传 API 时，可以直接调用：

```java
try (InputStream input = files.openInputStream("output/report.pptx")) {
    thirdPartyFileCenter.upload("runtime-report.pptx", input);
}
```

调用方必须关闭 `openInputStream()` 返回的流。对于远程 Runtime，关闭动作还会释放 HTTP 连接；遗漏关闭可能
耗尽连接池或文件句柄。

## 快速开始：让模型发布下载 URL

业务代码主动下载适合固定流程。聊天场景中，如果希望模型在生成并验证最终文件后自己完成交付，可以实现
`FilePublisher`。

### 1. 实现 FilePublisher

```java
import com.agentsflex.skill.file.FilePublisher;
import com.agentsflex.skill.file.PublishedFile;

FilePublisher publisher = request -> {
    String objectKey = attachmentCenter.upload(
        request.getFileName(),
        request.getContentType(),
        request.getContentLength(),
        request.getInputStream()
    );

    long expiresAt = System.currentTimeMillis() + 60 * 60_000L;
    return PublishedFile.builder()
        .url(attachmentCenter.createDownloadUrl(objectKey))
        .storageKey(objectKey)
        .fileName(request.getFileName())
        .contentType(request.getContentType())
        .contentLength(request.getContentLength())
        .expiresAt(expiresAt)
        .build();
};
```

`FilePublisher` 可以接对象存储、应用附件中心、企业网盘或受控静态文件目录。它必须返回一个用户实际能够访问
的 URL，不能返回 Runtime 路径或只有服务器本机可访问的 `file://` 地址。

### 2. 注册 publish_file

```java
prompt.addTools(SkillsTool.builder()
    .addSkillsDirectory(skillsDirectory)
    .runtime(runtime)
    .filePublisher(publisher)
    .buildTools());
```

只有配置 `.filePublisher(...)` 时，`buildTools()` 才会增加 `publish_file` 工具。模型生成最终文件后，可以
发起类似调用：

```json
{
  "filePath": "/home/gem/workspace/output/report.pptx",
  "fileName": "季度经营报告.pptx",
  "contentType": "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}
```

工具会依次完成：

1. 通过当前 Runtime 检查路径存在且不是目录；
2. 去掉展示文件名中的路径部分；
3. 使用指定 MIME 类型，或根据文件扩展名推断；
4. 打开 Runtime 二进制流并构造 `FilePublishRequest`；
5. 同步调用应用提供的 `FilePublisher`；
6. 自动关闭输入流，并把 URL、文件名、类型、大小和有效期返回给模型。

模型应把返回 URL 放在最终回复中。Runtime 内部路径不是交付结果；发布失败时也不能声称文件已经发送。

### 3. 哪些文件应该发布

配置 `publish_file` 后，默认策略是：

- 任务生成了需要交付给用户的最终文件时，发布最终文件；
- 用户要求“发给我”“作为附件”“给下载链接”“打开或分享文件”时，发布文件；
- 临时文件和中间文件不发布，除非用户明确要求；
- 用户明确要求不要上传或发布时，不调用该工具；
- 文件发布失败时，先修复问题并重试，不能伪造 URL。

## FilePublisher 请求与生命周期

`publish_file` 构造的 `FilePublishRequest` 包含：

| 字段 | 说明 |
| --- | --- |
| `inputStream` | Runtime 文件流，只能同步消费一次 |
| `fileName` | 用户看到的文件名，工具会去掉目录部分 |
| `contentType` | 模型指定、扩展名推断或 `application/octet-stream` |
| `contentLength` | Runtime 报告的文件大小；未知时可以是 `-1` |
| `sourcePath` | Runtime 内原始路径，用于审计和策略判断 |
| `runtimeName` | `local`、`open-sandbox`、`aio-sandbox` 或自定义名称 |
| `checksum` | 可选校验值；内置 `publish_file` 当前为 `null` |
| `metadata` | 扩展元数据；内置 `publish_file` 当前为空 Map |

输入流的生命周期约定非常重要：

1. Publisher 必须在 `publish()` 返回前完整消费输入流；
2. Publisher 不得关闭输入流；
3. Publisher 不得缓存输入流或交给异步线程继续读取；
4. Tool 会在 `publish()` 返回或抛出异常后关闭输入流；
5. 异步上传应先同步落入受控临时文件，或使用独立、可重复打开的内容源。

内置 Tool 当前不会自动传入租户或会话 metadata。Publisher 需要租户信息时，应从应用明确管理的请求上下文
取得，或由业务代码直接构造 `FilePublishRequest`。不要假设文件名、`sourcePath` 或空 metadata 已经完成权限
校验。

## S3-compatible 发布示例

下面的示例把 Runtime 文件流式上传到 AWS S3、MinIO、RustFS、Ceph RGW 等 S3-compatible 存储，并返回
一小时有效的预签名 URL。

> `agents-flex-skills-artifact-s3` 保存的是 Skill ZIP 安装包，不会自动发布 Skill 执行产物。
> `FilePublisher` 应使用应用管理的 S3 Client。

### 添加 AWS SDK

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.48.4</version>
</dependency>
```

### 创建 Client 与 Presigner

凭证由 AWS SDK 默认凭证链读取：

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

AWS S3 通常不需要 `endpointOverride(...)`，也可以关闭 Path-Style。兼容存储一般需要厂商提供的 Endpoint；
该地址还必须能被最终用户访问，否则预签名 URL 只在应用内网有效。

### 实现上传与签名

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
```

如果自定义 Runtime 报告 `contentLength = -1`，不要为了计算大小把大型文件整体读入内存。可以先同步落入
有配额和清理策略的临时文件，或改用支持未知长度的 multipart upload。`S3Client` 和 `S3Presigner` 应由应用
统一管理，并在应用关闭时调用 `close()`。

## 发布安全边界

`FilePublisher` 是 Runtime 文件离开执行环境前的最终安全边界。应用实现至少应处理：

### 路径与权限

- 只允许发布约定输出目录中的普通文件，例如当前会话的 `output/`；
- 根据当前用户、租户、会话和任务检查 `sourcePath` 是否有权发布；
- 不允许模型通过展示文件名控制对象 Key、宿主机路径或其他租户目录。

### 文件与内容

- 限制文件大小、扩展名和 MIME 类型，并检查扩展名与实际内容是否匹配；
- 对来自不可信 Skill 或用户内容的文件执行病毒、恶意宏或压缩炸弹扫描；
- 发布前确认文件已生成完成，并按业务需要执行格式、页数或可打开性校验。

### 存储与 URL

- 使用随机或服务端生成的对象 Key，避免覆盖和路径注入；
- 默认使用私有 Bucket 与短期签名 URL，不要为了下载方便把整个 Bucket 设为公开；
- URL 必须能被目标用户访问，并设置合理有效期、下载权限和撤销机制；
- 记录 `storageKey` 便于审计与删除，但不要把它当作用户访问 URL。

### 数据与日志

- 不在日志中记录文件正文、签名 URL、Token 或敏感文件名；
- 为产物制定保留、过期、删除和合规策略；
- 发布失败时清理已经上传但尚未完成业务登记的对象，或通过补偿任务回收。

## 生命周期与清理

Runtime 关闭、会话结束、发布 URL 失效和对象删除是四个不同事件：

| 事件 | 默认影响 |
| --- | --- |
| `runtime.close()` | 释放 Runtime 资源；是否保留远端文件取决于 Runtime 和会话模式 |
| 会话结束 | 由业务决定是否销毁 Sandbox、归档或删除 Workspace |
| 签名 URL 过期 | 用户不能继续使用该 URL，但对象通常仍然存在 |
| 删除存储对象 | 文件内容被清理，旧 URL 即使未到期也应失效 |

应用不能只设置 URL 有效期而不清理底层对象，也不能假设 `close()` 会自动删除已经发布的文件。建议把任务 ID、
用户、租户、`storageKey`、创建时间和过期时间写入附件记录，再由定时任务执行清理或归档。

## 常见问题

### 为什么不能直接把 Runtime 路径返回给用户？

Runtime 路径只在宿主机或 Sandbox 内有效，用户浏览器通常无法访问；直接暴露内部路径还可能泄露目录结构、
会话 ID 或部署信息。最终交付应是应用附件、HTTP 响应或可访问 URL。

### download() 和 publish_file 应该选哪个？

业务预先知道产物路径和后续动作时使用 `download()`；开放式对话中由模型决定最终文件时使用
`publish_file`。两种方式可以并存，但同一个最终文件通常只需交付一次。

### 配置 FilePublisher 后会自动上传所有文件吗？

不会。配置只会注册 `publish_file` 工具。模型仍需在文件生成并验证后显式调用它，中间文件不会被后台自动
扫描或上传。

### publish_file 会计算文件 SHA-256 吗？

当前内置 Tool 不计算，`checksum` 为 `null`。需要内容校验或去重时，Publisher 可以在流式上传过程中计算摘要，
并把结果记录到自己的存储元数据中。

### 怎样发布未知大小的大文件？

不要调用 `readBytes()`，也不要为了取得长度把文件整体载入内存。使用支持未知长度或分片上传的 SDK；如果目标
API 必须预先知道长度，则先流式落入有配额的临时文件，取得大小后再上传，并确保异常和过期文件会被清理。

### FilePublisher 会自动处理租户隔离吗？

不会。内置 Tool 提供 Runtime 名称、源路径和文件信息，但租户身份、发布授权、对象 Key 命名和 URL 权限都由
应用实现。

## 下一步

- [了解 Skill Runtime](./runtime)
- [配置持续会话 Workspace](./skill-runtime-workspace)
- [安装和分发 Skill Artifact](./artifact-store)
- [使用 Local Runtime](./local-runtime)
- [配置 OpenSandbox](./open-sandbox)
- [配置 AIO Sandbox](./aio-sandbox)

</div>
