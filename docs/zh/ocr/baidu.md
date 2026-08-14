# 百度智能云 OCR

`agents-flex-ocr-baidu` 适配百度智能云 PaddleOCR-VL 文档解析异步 API，支持本地文件和远程 URL。

适合希望直接使用 PaddleOCR-VL，并需要 Markdown 与解析 JSON 下载资源的场景。本地文件会整体读取并转换为 Base64，选择输入方式时需要同时考虑文件大小和 JVM 内存。

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-baidu</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 配置鉴权

```java
import com.agentsflex.ocr.baidu.BaiduOcrConfig;
import com.agentsflex.ocr.baidu.BaiduOcrModel;

BaiduOcrConfig config = new BaiduOcrConfig();
config.setApiKey(System.getenv("BAIDU_OCR_API_KEY"));

BaiduOcrModel model = new BaiduOcrModel(config);
```

`apiKey` 支持两种凭证：

| 凭证 | 请求方式 |
| --- | --- |
| `bce-v3/` 开头的新版 API Key | `Authorization: Bearer ...` |
| 旧版 OAuth Access Token | URL 中的 `access_token` 参数 |

适配器会自动判断。建议优先使用新版 API Key，并通过环境变量或密钥管理服务注入。

### 3. 提交本地文件

```java
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;

import java.io.File;

OcrRequest request = OcrRequest.ofFile(new File("input.pdf"));
request.putOption("analysis_chart", true);
request.putOption("merge_tables", true);
request.putOption("recognize_seal", true);

OcrResponse response = model.recognizeAndWait(request);
```

本地文件会编码为 Base64，并通过 `file_data` 提交。

### 4. 检查结果

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

## 远程 URL

```java
OcrRequest request = OcrRequest.ofUrl("https://example.com/input.pdf");
OcrResponse response = model.recognizeAndWait(request);
```

URL 无法推导文件名时应设置：

```java
request.setFileName("input.pdf");
```

## 模型和结果

默认模型常量为：

```java
config.setModel(BaiduOcrModels.PADDLE_OCR_VL_1_6);
```

成功响应可能包含：

| 百度字段 | `OcrResponse` 映射 |
| --- | --- |
| 任务编号 | `taskId` |
| `markdown_url` | `resources` 中 `markdown` 类型 |
| `parse_result_url` | `resources` 中 `json` 类型 |

任务成功时，`getResult()` 会自动下载 `markdown_url` 并写入 `response.markdown`；
`recognizeAndWait()` 轮询该方法，因此同样可以直接调用 `response.getMarkdown()`。原始资源仍会保留在
`resources` 中，供应商结果链接存在有效期，应及时转存。

## 自定义等待时间

百度配置默认查询间隔为 5 秒：

```java
config.setTimeoutMillis(15 * 60_000L);
config.setPollIntervalMillis(5_000L);
```

生产服务建议使用 [异步任务模块](../async-task/getting-started) 持久化任务，而不是在请求线程调用 `recognizeAndWait()`。

## 常见问题

### 为什么 URL 输入提示缺少文件名？

百度提交协议要求 `file_name`。URL 最后一个路径段无法作为文件名时，请调用 `request.setFileName("input.pdf")`。

### 为什么新版 API Key 鉴权失败？

确认传入的是完整 `bce-v3/` 凭证。适配器会自动使用 Bearer Header；不要自行把它拼到 `access_token` 查询参数中。

### Base64 上传适合超大文件吗？

本地文件会整体读入内存并编码。大文件应评估供应商限制和 JVM 内存，条件允许时优先使用供应商可访问的远程 URL。

## 下一步

- [OCR 快速开始](./getting-started)
- [OCR 核心概念](./overview)
- [Async Task 持久化跟踪](../async-task/getting-started)
