# SoMark

`agents-flex-ocr-somark` 适配 SoMark 文档智能解析 API，支持本地文件和远程 URL，并同时提供同步解析与异步任务接口。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-ocr-somark</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
import com.agentsflex.ocr.somark.SomarkOcrConfig;
import com.agentsflex.ocr.somark.SomarkOcrModel;

SomarkOcrConfig config = new SomarkOcrConfig();
config.setApiKey(System.getenv("SOMARK_API_KEY"));
config.setPollIntervalMillis(3_000L);
config.setTimeoutMillis(10 * 60_000L);

SomarkOcrModel model = new SomarkOcrModel(config);
```

默认地址使用 SoMark 正式版 API `https://somark.cn/api/v1`，对应新版路径：

| 操作 | 路径 |
| --- | --- |
| 异步提交 | `/parse/async` |
| 异步查询 | `/parse/async_check` |
| 同步解析 | `/parse/sync` |

API Key 按 SoMark 协议放在 multipart 请求体的 `api_key` 字段中，不会作为 Bearer Token 发送。

## 异步解析

`recognize()` 提交异步任务，`getResult()` 查询一次；`recognizeAndWait()` 会按配置的间隔持续查询至终态：

```java
import com.agentsflex.core.model.ocr.OcrRequest;
import com.agentsflex.core.model.ocr.OcrResponse;

OcrRequest request = OcrRequest.ofUrl("https://example.com/report.pdf");
OcrResponse response = model.recognizeAndWait(request);
System.out.println(response.getMarkdown());
```

SoMark 官方建议每 3 到 5 秒查询一次。服务端应用或批量任务建议保存 `taskId`，通过异步任务模块调度查询。

## 同步解析

需要在一次供应商请求中直接取得结果时，调用 `recognizeSync()`：

```java
import java.io.File;

OcrRequest request = OcrRequest.ofFile(new File("report.pdf"));
OcrResponse response = model.recognizeSync(request);
```

同步调用会一直占用当前线程。大文件、页数较多或批处理场景应使用异步接口。

## 解析选项

供应商特有参数通过 `OcrRequest.putOption()` 传入。`output_formats` 可以使用集合，HTTP 客户端会生成同名的重复 multipart 字段；`element_formats` 和 `feature_config` 可以直接使用 `Map`，适配器会将其序列化为 JSON：

```java
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

OcrRequest request = OcrRequest.ofFile(new File("report.pdf"));
request.putOption("output_formats", Arrays.asList("markdown", "json", "zip"));

Map<String, Object> elementFormats = new HashMap<>();
elementFormats.put("image", "file");
elementFormats.put("formula", "latex");
elementFormats.put("table", "html");
request.putOption("element_formats", elementFormats);

Map<String, Object> featureConfig = new HashMap<>();
featureConfig.put("enable_title_level_recognition", true);
featureConfig.put("enable_image_ocr", true);
featureConfig.put("keep_header_footer", false);
request.putOption("feature_config", featureConfig);
```

当 `output_formats` 包含 `zip` 时，SoMark 要求 `element_formats.image` 使用 `file`。Markdown 和 ZIP 会分别映射到统一响应的 `markdown` 与 `resources`。

## 结构化版面结果

SoMark 的 `outputs.json.pages` 会映射到公共的 `OcrDocument`：

```java
import com.agentsflex.core.model.ocr.OcrBlock;
import com.agentsflex.core.model.ocr.OcrDocument;
import com.agentsflex.core.model.ocr.OcrPage;

OcrDocument document = response.getDocument();
for (OcrPage page : document.getPages()) {
    System.out.println(page.getPageNumber());
    System.out.println(page.getSize().getWidth() + " x " + page.getSize().getHeight());

    for (OcrBlock block : page.getBlocks()) {
        System.out.println(block.getType() + " " + block.getBoundingBox());
        System.out.println(block.getContent());
    }
}
```

具体映射包括：

| SoMark 字段 | 公共模型 |
| --- | --- |
| `file_name` | `OcrDocument.fileName` |
| `pages` | `OcrDocument.pages` |
| `page_num` | `OcrPage.pageNumber` |
| `page_size.w/h` | `OcrPageSize.width/height` |
| `merge_content_from_pre_page` | `OcrPage.mergedFromPreviousPage` |
| `idx` | `OcrBlock.index` |
| `type` | `OcrBlock.type` 和 `providerType` |
| `bbox` | `OcrBoundingBox` |
| `content` / `format` | `OcrBlock.content/format` |
| `captions` | `OcrBlock.captionBlockIndexes` |
| `img_url` | `OcrBlock.imageUrl` |
| `title_level` | `OcrBlock.titleLevel` |

边界框在公共模型中固定按 `x1, y1, x2, y2` 保存。SoMark 当前文档没有明确声明坐标单位和原点，
因此适配器将 `OcrCoordinateUnit` 和 `OcrCoordinateOrigin` 保守映射为 `UNKNOWN`，不会根据示例数值猜测。
未标准化的跨页信息仍保存在块级 metadata，完整供应商响应仍可通过 `response.getMetadata("data")` 读取。

SoMark 正式版当前限制为单文件 200MB、最多 300 页、每账号 QPS 4。远程 `file_url` 必须可以由供应商直接打开或下载。

## 下一步

- [OCR 快速开始](./getting-started)
- [OCR 核心概念](./overview)
- [使用异步任务持久化跟踪](../async-task/getting-started)
