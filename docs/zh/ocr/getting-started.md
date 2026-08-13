# OCR 文档识别

OCR 模块参考视频生成模块，将公共异步任务协议放在 `agents-flex-core`，供应商实现拆分为独立依赖。目前支持百度智能云、Gitee AI 模力方舟和 MinerU。

## 公共接口

所有供应商都实现 `OcrModel`：

```java
OcrResponse submitted = model.recognize(request);
OcrResponse latest = model.getResult(submitted.getTaskId());
OcrResponse result = model.recognizeAndWait(request, 10 * 60_000L, 3_000L);
```

`OcrResponse` 统一提供任务状态、纯文本、Markdown 和结果资源列表。供应商尚未统一的原始字段保存在 metadata 中；供应商请求参数可通过 `OcrRequest.putOption()` 透传。

## 百度智能云 PaddleOCR-VL

依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-baidu</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

配置中的 `apiKey` 可填写 `bce-v3/` 开头的新版 API Key，也可填写通过百度 API Key
和 Secret Key 换取的旧版 OAuth Access Token。适配器会自动选择 Bearer 请求头或
`access_token` 查询参数：

```java
BaiduOcrConfig config = new BaiduOcrConfig();
config.setApiKey(System.getenv("BAIDU_OCR_ACCESS_TOKEN"));

BaiduOcrModel model = new BaiduOcrModel(config);
OcrRequest request = OcrRequest.ofFile(new File("input.pdf"));
request.putOption("analysis_chart", true);
request.putOption("merge_tables", true);
request.putOption("recognize_seal", true);
OcrResponse result = model.recognizeAndWait(request);
```

远程 URL 也可直接提交。URL 最后一个路径段能作为文件名时无需额外配置，否则必须设置 `fileName`：

```java
OcrRequest request = OcrRequest.ofUrl("https://example.com/download?id=123");
request.setFileName("input.pdf");
OcrResponse result = model.recognizeAndWait(request);
```

本地文件会转为 Base64 `file_data`，远程文件使用 `file_url`。成功结果中的 `markdown_url` 和 `parse_result_url` 分别映射为 `markdown`、`json` 类型资源，供应商链接有效期为 30 天。

## Gitee AI

依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-gitee</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

Gitee 文档解析接口使用 multipart 上传本地文件：

```java
GiteeOcrConfig config = new GiteeOcrConfig();
config.setApiKey(System.getenv("GITEE_API_KEY"));
config.setModel(GiteeOcrModels.UNLIMITED_OCR);

GiteeOcrModel model = new GiteeOcrModel(config);
OcrResponse result = model.recognizeAndWait(OcrRequest.ofFile(new File("input.pdf")));
```

可选模型常量包括 `Unlimited-OCR`、`PDF-Extract-Kit-1.0`、`MinerU2.5`、`DeepSeek-OCR`、`MinerU2.5-Pro` 和 PaddleOCR-VL 系列。结果 URL 仅短期有效，应及时下载。

## MinerU

依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-mineru</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

使用远程文件 URL：

```java
MineruOcrConfig config = new MineruOcrConfig();
config.setApiKey(System.getenv("MINERU_API_KEY"));
config.setModel(MineruOcrModels.VLM);

MineruOcrModel model = new MineruOcrModel(config);
OcrRequest request = OcrRequest.ofUrl("https://example.com/input.pdf");
request.putOption("is_ocr", true);
request.putOption("page_ranges", "1-20");
OcrResponse result = model.recognizeAndWait(request);
```

本地文件也可直接传入：

```java
OcrResponse result = model.recognizeAndWait(OcrRequest.ofFile(new File("input.pdf")));
```

适配器会自动申请预签名上传地址、PUT 上传文件并查询 batch 结果。MinerU 完成后通常返回包含 `full.md`、JSON 和中间结果的 `full_zip_url`，统一映射为 `archive` 类型资源。

应用重启后如需用已保存的 batch ID 恢复本地文件任务查询，可调用 `model.getBatchResult(batchId)`。

常用 MinerU 参数包括 `is_ocr`、`language`、`page_ranges`、`extra_formats`、`formula_enable` 和 `table_enable`。具体限制以供应商文档为准。
