# OCR 快速开始

本节以 Gitee AI 和本地 PDF 为例完成一次文档识别。三家供应商共享 `OcrModel`、`OcrRequest` 和 `OcrResponse`，但支持的输入形式、选项和结果资源不同，切换实现时仍应阅读对应供应商文档。

## 第一步：添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

其他实现对应：

| 供应商 | artifactId |
| --- | --- |
| 百度智能云 | `agents-flex-ocr-baidu` |
| Gitee AI | `agents-flex-ocr-gitee` |
| MinerU | `agents-flex-ocr-mineru` |

## 第二步：配置模型

```java
import com.agentsflex.ocr.gitee.GiteeOcrConfig;
import com.agentsflex.ocr.gitee.GiteeOcrModel;
import com.agentsflex.ocr.gitee.GiteeOcrModels;

GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
config.setModel(GiteeOcrModels.UNLIMITED_OCR);
config.setTimeoutMillis(10 * 60_000L);
config.setPollIntervalMillis(3_000L);

GiteeOcrModel model = new GiteeOcrModel(config);
```

API Key 应从环境变量或密钥管理服务读取，不要写入源码、配置模板和日志。

## 第三步：创建请求

提交本地文件：

```java
import com.agentsflex.core.model.ocr.OcrRequest;

import java.io.File;

OcrRequest request = OcrRequest.ofFile(new File("input/report.pdf"));
```

三种内置适配器都支持远程文件：百度和 MinerU 把 URL 交给供应商，Gitee 会先由当前进程下载，再通过
multipart 上传：

```java
OcrRequest request = OcrRequest.ofUrl("https://example.com/report.pdf");
```

使用持久化 Async Task 时必须选择 URL 输入。这个 URL 至少要在任务完成本地排队并开始提交前保持有效，
且能够被实际执行任务的 Worker 访问。

如果 URL 不能推导出有效文件名，可以显式指定：

```java
OcrRequest request = OcrRequest.ofUrl("https://example.com/download?id=42");
request.setFileName("report.pdf");
```

## 第四步：识别并等待结果

```java
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;

OcrResponse response = model.recognizeAndWait(request);

if (response == null) {
    throw new IllegalStateException("OCR provider returned no response");
} else if (response.getStatus() == OcrTaskStatus.SUCCEEDED) {
    System.out.println(response.getMarkdown());
} else {
    System.err.println(response.getErrorCode() + ": " + response.getErrorMessage());
}
```

无参数的 `recognizeAndWait(request)` 使用 Config 中的 `timeoutMillis` 和 `pollIntervalMillis`。也可以为单次调用指定：

```java
OcrResponse response = model.recognizeAndWait(
    request,
    15 * 60_000L,
    5_000L
);
```

## 第五步：处理结果资源

部分供应商不把完整内容直接放入响应，而是返回 Markdown、JSON 或 ZIP 下载地址：

```java
import com.agentsflex.core.model.ocr.OcrResource;

for (OcrResource resource : response.getResources()) {
    System.out.println(resource.getType() + ": " + resource.getUrl());
}
```

这些 URL 通常有有效期。生产系统应在任务成功后尽快下载并转存到自己的对象存储。

通常不需要自行判断这些资源。内置 model 的 `getResult()` 会依次读取内联 Markdown、Markdown URL、
ZIP 中的 `full.md`，最后使用纯文本作为降级结果，并把内容写回响应。`recognizeAndWait()` 通过循环调用
`getResult()` 获得相同结果：

```java
OcrResponse response = model.recognizeAndWait(request);
String markdown = response.getMarkdown();
```

等待过程可能包含网络下载和 ZIP 解压，是阻塞操作。响应失败、资源下载失败或没有可用内容时会抛出
`OcrMarkdownResolveException`。

`getResult()` 在任务尚未成功时只返回当前状态；成功时会下载并物化 Markdown 资源。

### 将 Markdown 图片保存为 URL

OCR 结果中的图片可能来自 ZIP 相对路径、Base64 Data URI 或供应商的临时远程 URL。可以为 model 设置图片
处理器，将图片上传到对象存储，并用返回 URL 重写 Markdown。该处理器使用与 Doc Extractor 相同的
`com.agentsflex.core.document.ExtractedImageHandler` 接口：

```java
model.setExtractedImageHandler((imageBytes, mimeType, fileName) -> {
    String objectKey = imageKeyGenerator.fromContent(imageBytes, fileName);
    return objectStorage.upload(objectKey, imageBytes, mimeType);
});

OcrResponse response = model.recognizeAndWait(request);
String markdown = response.getMarkdown();
```

Handler 返回 `null` 或空字符串时，对应图片会从 Markdown 中移除。没有配置 Handler 时，供应商原有的
远程图片 URL 会保留，Markdown 资源中的相对图片 URL 会转换为绝对 URL；ZIP 内的相对图片路径无法自动
获得外部地址，因此生产环境应配置 Handler。配置 Handler 后，框架也会下载 Markdown 中的 HTTP/HTTPS
图片并交给 Handler，适合将供应商有时效的签名 URL 转存到自己的对象存储。图片来源必须可信，并应配合
网络访问策略，避免服务端请求不受信任的地址。

## 完整示例

```java
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResource;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.core.model.ocr.OcrTaskStatus;
import com.agentsflex.ocr.gitee.GiteeOcrConfig;
import com.agentsflex.ocr.gitee.GiteeOcrModel;
import com.agentsflex.ocr.gitee.GiteeOcrModels;

import java.io.File;

public class OcrQuickStart {
    public static void main(String[] args) {
        GiteeOcrConfig config = new GiteeOcrConfig();
        config.setApiKey(System.getenv("GITEE_API_KEY"));
        config.setModel(GiteeOcrModels.UNLIMITED_OCR);
        config.setTimeoutMillis(10 * 60_000L);
        config.setPollIntervalMillis(3_000L);

        GiteeOcrModel model = new GiteeOcrModel(config);
        OcrRequest request = OcrRequest.ofFile(new File("input/report.pdf"));
        OcrResponse response = model.recognizeAndWait(request);

        if (response == null) {
            throw new IllegalStateException("OCR provider returned no response");
        }
        if (response.getStatus() != OcrTaskStatus.SUCCEEDED) {
            throw new IllegalStateException(
                response.getErrorCode() + ": " + response.getErrorMessage()
            );
        }

        System.out.println(response.getMarkdown());
        for (OcrResource resource : response.getResources()) {
            System.out.println(resource.getType() + ": " + resource.getUrl());
        }
    }
}
```

## 非阻塞调用

```java
OcrResponse submitted = model.recognize(request);
if (submitted == null || submitted.isError()) {
    throw new IllegalStateException(
        submitted == null ? "empty response" : submitted.getErrorMessage()
    );
}
String taskId = submitted.getTaskId();

// 稍后由后台任务查询一次
OcrResponse latest = model.getResult(taskId);
```

单纯保存 `taskId` 适合简单系统。如果还需要跨重启恢复、自动重试、租约、防止多 Worker 重复查询，以及 QPS、配额和优先级控制，请使用 [异步任务模块](../async-task/overview)。

## 常见问题

### `TIMED_OUT` 是否表示供应商失败？

不是。它表示本地等待达到上限，供应商任务可能仍在运行。请保存 `taskId` 并继续查询。

### 为什么成功响应没有 `markdown`？

内置 model 的 `getResult()` 会在成功时自动填充 `markdown`，因此 `recognizeAndWait()` 和手动查询的结果一致。
只有 JSON 资源时仍需要按供应商结构自行转换。

### `recognize()` 返回后可以立即读取结果吗？

不一定。异步供应商通常先返回 `SUBMITTED` 和 `taskId`。只有状态进入 `SUCCEEDED` 后，文本和结果资源才完整可用。

### 供应商返回的 URL 可以永久保存吗？

通常不可以。它们可能是短期签名 URL，任务成功后应尽快下载并转存。

### 可以同时设置本地文件和 URL 吗？

不可以。一次请求必须只选择一种输入来源。

## 下一步

- [OCR 核心概念](./overview)
- [百度智能云](./baidu)
- [Gitee AI](./gitee)
- [MinerU](./mineru)
