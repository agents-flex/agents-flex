# MinerU OCR

`agents-flex-ocr-mineru` 适配 MinerU 文档解析 API。远程 URL 使用单任务接口；本地文件由适配器自动完成预签名地址申请、文件上传和批任务查询。

MinerU 的两种输入会产生不同的查询路由，这是与其他 OCR 实现最重要的差异。生产系统必须保存任务是普通任务还是批任务（batch task），才能在应用重启后选择正确查询接口。

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-mineru</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 配置模型

```java
import com.agentsflex.ocr.mineru.MineruOcrConfig;
import com.agentsflex.ocr.mineru.MineruOcrModel;
import com.agentsflex.ocr.mineru.MineruOcrModels;

MineruOcrConfig config = new MineruOcrConfig();
config.setApiKey(System.getenv("MINERU_API_KEY"));
config.setModel(MineruOcrModels.VLM);

MineruOcrModel model = new MineruOcrModel(config);
```

`apiKey` 最终作为 `Authorization: Bearer <credential>` 发送，可以直接使用 MinerU API 管理页创建的
`sk-...` API Token。

也可以直接配置 OpenXLab Access Key ID / Secret Access Key：

```java
MineruOcrConfig config = new MineruOcrConfig();
config.setAccessKeyId(System.getenv("OPENXLAB_ACCESS_KEY_ID"));
config.setSecretAccessKey(System.getenv("OPENXLAB_SECRET_ACCESS_KEY"));

MineruOcrModel model = new MineruOcrModel(config);
```

模型会自动完成 nonce 获取、HMAC 签名和 JWT 换取，并在模型实例内存中缓存 JWT。JWT 临近过期时优先
使用 refresh token 刷新，refresh token 失效后重新使用 AK/SK 登录。Token 不会写入磁盘。

`apiKey` 和 AK/SK 同时配置时优先使用 `apiKey`。`accessKeyId` 与 `secretAccessKey` 必须成对配置。

可用模型常量：

| 常量 | 值 |
| --- | --- |
| `PIPELINE` | `pipeline`，默认值 |
| `VLM` | `vlm` |
| `MINERU_HTML` | `MinerU-HTML` |

### 3. 识别远程文件

```java
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;

OcrRequest request = OcrRequest.ofUrl("https://example.com/input.pdf");
request.putOption("is_ocr", true);
request.putOption("language", "ch");
request.putOption("page_ranges", "1-20");

OcrResponse response = model.recognizeAndWait(request);
```

### 4. 识别本地文件

```java
import java.io.File;

OcrRequest request = OcrRequest.ofFile(new File("input.pdf"));
OcrResponse response = model.recognizeAndWait(request);
```

适配器自动执行：

```text
申请预签名上传地址
    ↓
PUT 上传本地文件
    ↓
使用 batch ID 查询解析结果
```

本地文件任务在应用重启后，可以使用已保存的 batch ID 恢复查询：

```java
OcrResponse latest = model.getBatchResult(batchId);
String markdown = latest.getMarkdown();
```

普通远程 URL 任务仍使用 `getResult(taskId)`。

> `MineruOcrModel` 只在当前进程内记住哪些 ID 是批任务。进程重启后，不能仅调用通用 `getResult(batchId)`，应根据持久化的任务类型调用 `getBatchResult(batchId)`。自定义 Async Task Handler 时可以把 `batch=true` 保存到 `TaskQueryParams.providerParams`。

## 常用选项

| 选项 | 说明 |
| --- | --- |
| `is_ocr` | 是否启用 OCR |
| `language` | 文档语言 |
| `page_ranges` | 解析页范围 |
| `extra_formats` | 额外输出格式 |
| `formula_enable` | 是否解析公式 |
| `table_enable` | 是否解析表格 |

具体类型、取值和限制以 MinerU 当前接口文档为准。

## 结果资源

MinerU 完成后通常返回 `full_zip_url`，其中包含 `full.md`、JSON 和中间产物。框架将其映射为 `archive`
类型 `OcrResource`；`getResult()` 和 `getBatchResult()` 会自动下载 ZIP、提取 Markdown 并写入
`response.markdown`。下载 URL 可能过期，原始资源仍应及时转存。

## 常见问题

### 本地文件为什么比远程 URL 多一个 batch 流程？

本地文件需要先申请预签名地址并上传，再通过 batch 接口查询；远程 URL 可以直接创建普通解析任务。
适配器会自动兼容不同凭据签发的 OSS URL：优先根据文件名设置 `Content-Type`，若签名拒绝该值则以空
`Content-Type` 重试，不需要调用方手动区分。

### 应用重启后为什么查询不到本地文件任务？

请确认保存了 batch ID 和任务类型，并使用 `getBatchResult(batchId)`。仅保存一个不带类型的 ID 不足以恢复查询路由。

### 成功后为什么只有 ZIP 地址？

MinerU 常把 Markdown、JSON 和中间产物打包返回。当前适配器会自动提取其中的 Markdown；如果 ZIP 中的
图片需要长期可访问的 URL，请通过 `model.setExtractedImageHandler(...)` 配置对象存储上传逻辑。

## 下一步

- [OCR 快速开始](./getting-started)
- [OCR 核心概念](./overview)
- [自定义 Async Task Handler](../async-task/handler)
