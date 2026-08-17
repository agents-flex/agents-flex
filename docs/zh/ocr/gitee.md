# Gitee AI OCR

`agents-flex-ocr-gitee` 适配 Gitee AI 模力方舟异步文档解析接口，支持多种 OCR 和文档解析模型。

供应商接口使用 multipart 上传文件。适配器既接受本地文件，也接受远程 URL；URL 输入会先下载到当前进程
临时目录，上传结束后自动清理。它适合需要在同一平台切换多种文档解析模型，并希望统一读取 Markdown 和
下载资源的场景。

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 配置并识别

```java
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;
import com.agentsflex.ocr.gitee.GiteeOcrConfig;
import com.agentsflex.ocr.gitee.GiteeOcrModel;
import com.agentsflex.ocr.gitee.GiteeOcrModels;

import java.io.File;

GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
config.setModel(GiteeOcrModels.UNLIMITED_OCR);

GiteeOcrModel model = new GiteeOcrModel(config);
OcrResponse response = model.recognizeAndWait(
    OcrRequest.ofFile(new File("input.pdf"))
);
```

本地文件使用 multipart 上传。任务成功后，适配器会按供应商返回顺序合并文本片段到 `OcrResponse.markdown`，并保留结果资源。

### 3. 检查结果

```java
import com.agentsflex.core.model.ocr.OcrResource;

if (response == null || response.isError()) {
    throw new IllegalStateException(
        response == null ? "empty response" : response.getErrorMessage()
    );
}

System.out.println(response.getMarkdown());
for (OcrResource resource : response.getResources()) {
    System.out.println(resource.getType() + ": " + resource.getUrl());
}
```

## 模型常量

| 常量 | 模型名 |
| --- | --- |
| `UNLIMITED_OCR` | `Unlimited-OCR` |
| `PDF_EXTRACT_KIT_1_0` | `PDF-Extract-Kit-1.0` |
| `MINERU_2_5` | `MinerU2.5` |
| `MINERU_2_5_PRO` | `MinerU2.5-Pro` |
| `DEEPSEEK_OCR` | `DeepSeek-OCR` |
| `PADDLE_OCR_VL_1_5` | `PaddleOCR-VL-1.5` |
| `PADDLE_OCR_VL` | `PaddleOCR-VL` |

也可以对单次请求覆盖模型：

```java
OcrRequest request = OcrRequest.ofFile(new File("input.pdf"));
request.setModel(GiteeOcrModels.PDF_EXTRACT_KIT_1_0);
```

## 分离提交和查询

```java
OcrResponse submitted = model.recognize(request);
String taskId = submitted.getTaskId();

OcrResponse latest = model.getResult(taskId);
String markdown = latest.getMarkdown();
```

任务成功时，`getResult()` 会统一处理内联 Markdown、Markdown URL 或 ZIP，并填充 `markdown`。结果 URL
通常只有短期有效期，仍应及时转存。大量任务推荐交给 [异步任务模块](../async-task/overview) 自动查询。

## 常见问题

### 可以传入远程 URL 吗？

可以。适配器会下载 URL 内容并通过 multipart 上传。使用 Async Task 时必须选择 URL 输入；URL 需要能被
Worker 访问，并且有效期覆盖本地排队和下载时间。

下载期间会短暂占用 Worker 的网络带宽、堆内存和临时目录空间。大文件应同时限制来源、响应大小、下载
超时和并发数，并确保临时目录容量充足；不要把无法信任的任意 URL 直接暴露给外部调用方，以免产生 SSRF
或内网资源访问风险。

### 如何选择模型？

不同模型在版面、表格、公式、速度和费用方面能力不同。先用真实业务样本进行评测，再固定默认模型；单次特殊任务可通过 `request.setModel()` 覆盖。

对于需要保留正文结构和图片的 PDF，优先评估 `PDF_EXTRACT_KIT_1_0`。`UNLIMITED_OCR` 更偏向版面元素
识别，可能只返回图片区域标注而不返回实际图片 URL。若配置 `DocumentImagePublisher`，框架会将模型返回的
临时图片 URL 下载后交给 Publisher，并把 Markdown 图片引用替换为 Publisher 返回的持久 URL。

### 为什么任务成功但 Markdown 为空？

如果供应商提供了内联 Markdown、Markdown URL、包含 Markdown 的 ZIP 或纯文本，`getResult()` 都会填充
`markdown`。仍为空通常表示供应商只返回了无法通用转换的 JSON 或其他未知格式，请检查 `resources` 和
供应商原始元数据。

## 下一步

- [OCR 快速开始](./getting-started)
- [OCR 核心概念](./overview)
- [Async Task 持久化跟踪](../async-task/getting-started)
