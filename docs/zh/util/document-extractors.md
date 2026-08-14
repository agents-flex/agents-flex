# DocumentExtractors 文档提取工具

<div v-pre>

`DocumentExtractors` 是 `agents-flex-doc-extractor` 模块提供的静态便捷入口，用于把本地文件、网络地址、字节数组或输入流提取为文本内容。它会根据文件名和 MIME 类型选择合适的 `DocumentExtractor`，并在当前提取器失败时按注册顺序尝试其他候选提取器。

## 1. 引入依赖

Maven：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-doc-extractor</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块支持常见的文本、HTML、PDF、Word、Excel、PowerPoint 等文档格式。具体格式支持取决于项目中引入的解析器依赖。

## 2. 快速开始

### 2.1 提取本地文件

```java
import com.agentsflex.doc.DocumentExtractors;

String markdown = DocumentExtractors.extract(
    new File("/data/report.docx")
);
```

返回内容通常为 Markdown 风格文本；无法匹配提取器或所有候选提取器都失败时返回 `null`。

### 2.2 从 URL 提取

```java
String markdown = DocumentExtractors.extractFromUrl(
    "https://example.com/report.pdf"
);
```

当 URL 没有可靠的文件扩展名时，可以显式传入文件名和 MIME 类型：

```java
String markdown = DocumentExtractors.extractFromUrl(
    "https://example.com/download?id=42",
    "report.pdf",
    "application/pdf"
);
```

### 2.3 从字节数组或输入流提取

```java
String markdown = DocumentExtractors.extract(
    bytes,
    "report.docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
);
```

```java
try (InputStream input = Files.newInputStream(path)) {
    String markdown = DocumentExtractors.extract(
        input,
        "report.txt",
        "text/plain"
    );
}
```

`InputStream` 的关闭责任仍由调用方负责。

## 3. 支持的方法

| 方法 | 说明 |
| --- | --- |
| `extract(File file)` | 根据本地文件名和内容提取 |
| `extract(byte[] bytes, String fileName, String mimeType)` | 从字节数组提取 |
| `extract(InputStream inputStream, String fileName, String mimeType)` | 从输入流提取 |
| `extractFromUrl(String httpUrl)` | 根据 URL 提取 |
| `extractFromUrl(String httpUrl, String fileName)` | 为 URL 指定文件名 |
| `extractFromUrl(String httpUrl, String fileName, String mimeType)` | 同时指定文件名和 MIME 类型 |
| `setDefault(DocumentExtractionService service)` | 替换进程级默认服务 |
| `setExtractedImageHandler(ExtractedImageHandler handler)` | 配置文档内图片的处理方式；接口位于 `com.agentsflex.core.document` |

## 4. 图片处理

部分文档包含图片。默认处理器会将图片提取为 Base64 内容；可以通过全局入口替换处理器：

```java
DocumentExtractors.setExtractedImageHandler((imageBytes, mimeType, fileName) -> {
    // 将 image 保存到对象存储，并返回可访问的 URL
    return uploadAndGetUrl(imageBytes, mimeType, fileName);
});
```

图片处理器是进程级配置，通常在应用启动时设置一次。不要在多租户请求中按请求修改它。

## 5. 使用 DocumentExtractionService

`DocumentExtractors` 适合快速调用和单一全局配置。需要独立的提取器注册表、图片策略或多租户配置时，应直接创建 `DocumentExtractionService`：

```java
DocumentExtractionService service = new DocumentExtractionService(
    registry,
    extractedImageHandler
);

String markdown = service.extract(file);
```

服务实例不共享 `DocumentExtractors` 的默认配置，更适合在依赖注入容器中作为应用组件管理。

## 6. 注意事项

- `fileName` 和 `mimeType` 会影响提取器匹配；无法从 URL 推断格式时应显式传入。
- 文档会被完整读取和解析，大文件应限制大小并避免在请求线程中无限等待。
- 返回 `null` 表示没有成功提取内容，业务代码应明确处理该情况。
- URL 提取会发起网络请求，生产环境应配置超时、重试和目标地址校验。
- `DocumentExtractors` 的默认服务是全局共享的；需要请求级状态时使用独立的 `DocumentExtractionService`。

</div>
