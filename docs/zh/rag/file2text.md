# File2Text 文档解析
<div v-pre>

`File2Text` 是 Agents-Flex 提供的统一文档内容提取能力。它可以从本地文件、HTTP URL、输入流或字节数组中识别文档类型，并将正文、表格和图片转换为适合大模型、RAG、全文检索和内容审查使用的 Markdown 风格文本。

无论输入是 Office 文档、PDF、网页、邮件、电子书、OpenDocument、代码文件，还是包含多个文档的压缩包，调用方都可以使用同一套 API。

## 核心能力

- **统一输入接口**：支持文件、HTTP、`InputStream`、`byte[]` 和自定义数据源。
- **丰富的格式覆盖**：覆盖 Microsoft Office、OpenDocument、PDF、RTF、HTML、邮件、EPUB、Apple iWork、压缩包、代码和配置文件。
- **Markdown 风格输出**：保留标题、段落、表格、分页和幻灯片结构，内嵌图片可输出为 Base64 Data URI 或自定义存储 URL。
- **自动类型识别**：综合文件名、扩展名和 MIME 类型选择解析器，并支持候选解析器降级重试。
- **复杂文档支持**：可处理 Office 宏格式、模板格式、旧版二进制 Office 文件以及压缩包中的嵌套文档。
- **面向大文件设计**：HTTP 大文件自动落盘，PDF 使用内存与临时文件混合模式。
- **可扩展架构**：可以注册自定义 `FileExtractor`，也可以实现新的 `DocumentSource`。

## 快速开始

### 使用便捷工具类

```java
String text = File2TextUtil.readFromFile(new File("/path/to/report.docx"));
System.out.println(text);
```

从 HTTP 地址读取：

```java
String text = File2TextUtil.readFromHttpUrl(
    "https://example.com/files/report.pdf"
);
```

当 URL 中没有可靠的文件名时，建议显式传入文件名和 MIME：

```java
String text = File2TextUtil.readFromHttpUrl(
    "https://example.com/download?id=1001",
    "report.docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
);
```

### 使用服务实例

需要注册自定义解析器或控制解析器集合时，使用 `File2TextService`：

```java
File2TextService service = new File2TextService();

String fromFile = service.extractTextFromFile(new File("/path/to/report.pdf"));
String fromUrl = service.extractTextFromHttpUrl("https://example.com/report.pdf");
```

### 从流和字节数组读取

```java
try (InputStream input = new FileInputStream("/path/to/data.tsv")) {
    String text = service.extractTextFromStream(
        input,
        "data.tsv",
        "text/tab-separated-values"
    );
}
```

```java
byte[] bytes = Files.readAllBytes(Paths.get("/path/to/slides.pptx"));
String text = service.extractTextFromBytes(
    bytes,
    "slides.pptx",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
);
```

文件名和 MIME 会参与解析器选择。处理流、字节数组或无扩展名下载地址时，应尽量提供准确的 `fileName` 和 `mimeType`。

## 支持格式

### Microsoft Word

| 类型 | 扩展名 | 解析能力 |
| --- | --- | --- |
| Word 97-2003 文档 | `.doc` | 段落、图片、Markdown 表格 |
| Word 97-2003 模板 | `.dot` | 段落、图片、Markdown 表格 |
| Word OOXML 文档 | `.docx` | 段落、图片、Markdown 表格 |
| Word OOXML 模板 | `.dotx` | 段落、图片、Markdown 表格 |
| Word 宏文档 | `.docm` | 与 DOCX 相同，宏不会执行 |
| Word 宏模板 | `.dotm` | 与 DOCX 相同，宏不会执行 |

### Microsoft Excel

| 类型 | 扩展名 | 解析能力 |
| --- | --- | --- |
| Excel 97-2003 工作簿 | `.xls` | 多 Sheet、公式计算、Markdown 表格 |
| Excel 97-2003 模板 | `.xlt` | 多 Sheet、公式计算、Markdown 表格 |
| Excel OOXML 工作簿 | `.xlsx` | 多 Sheet、公式计算、Markdown 表格 |
| Excel 宏工作簿 | `.xlsm` | 与 XLSX 相同，宏不会执行 |
| Excel OOXML 模板 | `.xltx` | 多 Sheet、公式计算、Markdown 表格 |
| Excel 宏模板 | `.xltm` | 与 XLSX 相同，宏不会执行 |
| Excel 二进制工作簿 | `.xlsb` | 通过 Apache Tika 提取可读内容 |

### Microsoft PowerPoint

| 类型 | 扩展名 | 解析能力 |
| --- | --- | --- |
| PowerPoint 97-2003 演示文稿 | `.ppt` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint 97-2003 放映文件 | `.pps` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint 97-2003 模板 | `.pot` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint OOXML 演示文稿 | `.pptx` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint OOXML 放映文件 | `.ppsx` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint OOXML 模板 | `.potx` | 幻灯片文本、图片、Markdown 表格 |
| PowerPoint 宏演示文稿 | `.pptm` | 与 PPTX 相同，宏不会执行 |
| PowerPoint 宏放映文件 | `.ppsm` | 与 PPTX 相同，宏不会执行 |
| PowerPoint 宏模板 | `.potm` | 与 PPTX 相同，宏不会执行 |

### PDF

| 类型 | 扩展名 | 解析能力 |
| --- | --- | --- |
| PDF 文档 | `.pdf` | 分页文本、实际绘制的图片、同页图片去重 |

PDF 输出包含 `--- Page N ---` 分页标记。图片统一转换为 PNG 后交给 `ExtractedImageHandler` 处理。纯扫描 PDF 可以提取页面中的图片，但当前不包含 OCR 文字识别。

### OpenDocument

| 分类 | 扩展名 |
| --- | --- |
| 文本文档 | `.odt`、`.fodt` |
| 电子表格 | `.ods`、`.fods` |
| 演示文稿 | `.odp`、`.fodp` |
| 绘图文档 | `.odg`、`.fodg` |
| 文本模板 | `.ott` |
| 表格模板 | `.ots` |
| 演示模板 | `.otp` |
| 绘图模板 | `.otg` |

OpenDocument 通过 Apache Tika 提取为 XHTML，再统一转换为 Markdown 风格文本。

### 富文本、网页和电子书

| 分类 | 扩展名 | 说明 |
| --- | --- | --- |
| Rich Text Format | `.rtf` | 提取富文本正文 |
| HTML | `.html`、`.htm`、`.xhtml` | 提取标题、段落、列表、链接和表格，并过滤常见网页噪音 |
| MHTML | `.mhtml` | 支持常见 HTML 内容，复杂 multipart 资源取决于文档结构 |
| EPUB | `.epub` | 按电子书内容顺序提取章节正文 |

### 邮件

| 类型 | 扩展名 | 说明 |
| --- | --- | --- |
| RFC 822 邮件 | `.eml` | 提取主题、正文及可解析的嵌入内容 |
| Outlook 邮件 | `.msg` | 提取 Outlook 消息正文和可解析内容 |

### Apple iWork

| 类型 | 扩展名 |
| --- | --- |
| Pages 文档 | `.pages` |
| Numbers 表格 | `.numbers` |
| Keynote 演示文稿 | `.key` |

iWork 通过 Apache Tika 的 Apple parser 处理。Apple 多次调整 iWork 内部 IWA 格式，因此不同应用版本的提取结果可能不同；部分现代文件可能只能获得元数据和包内资源信息。

### 压缩包

| 类型 | 扩展名 |
| --- | --- |
| ZIP | `.zip` |
| TAR | `.tar` |
| GZip/TGZ | `.gz`、`.tgz` |
| BZip2 | `.bz2` |
| XZ | `.xz` |
| 7-Zip | `.7z` |
| RAR | `.rar` |

压缩包会递归解析其中受支持的文档，并在输出中保留条目文件名。为防止压缩炸弹，默认安全限制如下：

| 限制项 | 默认值 |
| --- | ---: |
| 最大嵌套深度 | 3 层 |
| 最大条目数 | 100 |
| 单个解压条目 | 20 MB |
| 累计解压内容 | 100 MB |

超过限制的条目会被跳过。加密压缩包需要外部提供密码，目前不会自动解密。

### 基础文本和结构化文本

| 分类 | 扩展名 |
| --- | --- |
| 基础文本 | `.txt`、`.text`、`.log` |
| Markdown | `.md`、`.markdown` |
| 表格文本 | `.csv`、`.tsv` |
| 数据交换 | `.json`、`.xml`、`.yml`、`.yaml` |
| Java 配置 | `.properties` |
| 通用配置 | `.conf`、`.cfg`、`.config`、`.ini`、`.toml`、`.env` |
| 工程配置 | `.editorconfig`、`.gitignore`、`.gitattributes` |

纯文本解析器支持 UTF-8、GBK、GB2312 等常见编码自动检测，并接受通用的 `text/*` MIME。

### 代码文件

| 生态 | 扩展名 |
| --- | --- |
| JVM | `.java`、`.kt`、`.kts`、`.groovy`、`.scala`、`.gradle` |
| Python/Ruby/PHP | `.py`、`.rb`、`.php` |
| JavaScript/TypeScript | `.js`、`.mjs`、`.cjs`、`.jsx`、`.ts`、`.tsx` |
| Web 框架 | `.vue`、`.svelte` |
| C/C++ | `.c`、`.h`、`.cc`、`.cpp`、`.hpp` |
| 其他编译语言 | `.cs`、`.go`、`.rs`、`.swift`、`.dart` |
| 脚本 | `.sh`、`.bash`、`.zsh`、`.fish`、`.bat`、`.cmd`、`.ps1` |
| 数据库 | `.sql` |
| 样式 | `.css`、`.scss`、`.sass`、`.less` |
| 其他 | `.lua`、`.r` |

同时支持常见的无扩展名工程文件：`Dockerfile`、`Makefile`、`Jenkinsfile`。

## 输出格式

File2Text 输出的是便于模型理解和后续切分的 Markdown 风格文本。

### 表格

```markdown
| Name | Score |
| --- | --- |
| Alice | 95 |
```

Word、PowerPoint、Excel、HTML 和部分 Tika 文档中的表格会尽可能保留为 Markdown 表格。单元格内的换行和管道符会进行转义。

### 图片

```markdown
![Image](data:image/png;base64,iVBORw0KGgo...)
```

DOC、DOCX、PPT、PPTX 和 PDF 中可识别的图片会以内嵌 Data URI 返回，不需要额外管理临时图片文件。

这是 `Base64ExtractedImageHandler` 提供的默认兼容行为。Base64 会显著增加文本长度，生产环境可以配置 `ExtractedImageHandler`，将图片上传到对象存储并返回 URL：

```java
ExtractedImageHandler imageHandler = (imageBytes, mimeType, fileName) -> {
    // 上传到 OSS、S3 或其他文件服务，并返回可访问的图片地址
    return objectStorage.upload(imageBytes, mimeType, fileName);
};

File2TextService service = new File2TextService(imageHandler);
String text = service.extractTextFromFile(new File("/path/to/report.docx"));
```

解析器会将处理器返回的地址直接写入 Markdown：

```markdown
![Image](https://cdn.example.com/files/image-1.png)
```

`ExtractedImageHandler.handle(...)` 的参数和返回值如下：

| 参数或返回值 | 说明 |
| --- | --- |
| `imageBytes` | 从文档中提取的图片二进制数据 |
| `mimeType` | 图片 MIME 类型；无法识别时为 `application/octet-stream` |
| `fileName` | 文档内的图片文件名；原格式不提供文件名时由解析器生成 |
| 返回值 | 用于 Markdown 渲染的 URL 或 Data URI；返回 `null` 或空字符串会跳过该图片 |

也可以在服务创建后设置处理器：

```java
File2TextService service = new File2TextService();
service.setExtractedImageHandler(imageHandler);
```

使用静态工具类时，可以全局设置：

```java
File2TextUtil.setExtractedImageHandler(imageHandler);
String text = File2TextUtil.readFromFile(file);
```

自定义 `ExtractorRegistry` 和图片处理器可以同时传入：

```java
File2TextService service = new File2TextService(registry, imageHandler);
```

`ExtractedImageHandler` 可能被并发解析请求调用，实现类应避免使用非线程安全的共享状态。上传或转换失败时可以抛出 `IOException`。

### 页面和幻灯片

```markdown
--- Page 1 ---
PDF page content

--- Slide 2 ---
Presentation content
```

分页和幻灯片边界可以直接用于 RAG 分块、引用定位和上下文展示。

### Excel Sheet

```markdown
### Sheet1

| Product | Amount |
| :--- | :--- |
| Service | 1000 |
```

## 工作原理

一次解析请求会经过以下流程：

1. `DocumentSource` 提供文件名、MIME 和可重复打开的输入流。
2. `ExtractorRegistry` 根据文件名和 MIME 筛选候选解析器。
3. 候选解析器按 `getOrder()` 从小到大依次执行。
4. 解析器发现内嵌图片时，将图片数据交给 `ExtractedImageHandler`，并把返回地址写入 Markdown。
5. 第一个返回非空内容的解析器结束本次处理。
6. 所有解析器均失败或返回空内容时，服务返回 `null`。
7. 请求结束后自动调用 `DocumentSource.cleanup()` 清理临时资源。

默认注册的解析器包括：

| 解析器 | 主要职责 |
| --- | --- |
| `PdfTextExtractor` | PDF 文字、分页和图片 |
| `DocExtractor` | Word 97-2003 |
| `DocxExtractor` | Word OOXML、宏格式和模板 |
| `PptExtractor` | PowerPoint 97-2003 |
| `PptxExtractor` | PowerPoint OOXML、宏格式和模板 |
| `ExcelExtractor` | XLS/XLSX、宏格式和模板 |
| `TikaDocumentExtractor` | RTF、OpenDocument、邮件、EPUB、XLSB、iWork 和压缩包 |
| `HtmlExtractor` | HTML 内容清洗和 Markdown 转换 |
| `PlainTextExtractor` | 文本、代码、配置和结构化文本 |

## DocumentSource

### 内置数据源

| 数据源 | 用途 |
| --- | --- |
| `FileDocumentSource` | 本地文件 |
| `HttpDocumentSource` | HTTP/HTTPS 文件，支持缓存和临时文件 |
| `ByteArrayDocumentSource` | 已加载到内存的字节数组 |
| `ByteStreamDocumentSource` | 输入流，内部缓存后允许解析器重复打开 |
| `TemporaryFileStreamDocumentSource` | 将大输入流保存为临时文件，默认最大 100 MB |

### HTTP 下载配置

`HttpDocumentSource` 默认连接超时为 20 秒、读取超时为 60 秒。已知长度且不超过 10 MB 的文件使用内存缓存，其他文件使用临时文件，并在解析结束后清理。

```java
HttpDocumentSource source = new HttpDocumentSource(
    "https://example.com/private/report.pdf",
    "report.pdf",
    "application/pdf",
    10_000,
    60_000,
    connection -> connection.setRequestProperty(
        "Authorization",
        "Bearer " + accessToken
    )
);

String text = service.extractTextFromSource(source);
```

获取已缓存的数据大小：

```java
long size = source.getCachedSize();
```

### 自定义数据源

可以通过实现 `DocumentSource` 接入对象存储、数据库、内部文件系统或其他数据平台：

```java
public class ObjectStorageDocumentSource implements DocumentSource {

    private final String objectKey;

    public ObjectStorageDocumentSource(String objectKey) {
        this.objectKey = objectKey;
    }

    @Override
    public String getFileName() {
        return objectKey;
    }

    @Override
    public String getMimeType() {
        return "application/pdf";
    }

    @Override
    public InputStream openStream() {
        return objectStorageClient.openStream(objectKey);
    }

    @Override
    public void cleanup() {
        // 按需释放临时文件、连接或其他资源
    }
}
```

`openStream()` 可能被多个候选解析器调用，因此自定义数据源应返回新的、可独立读取的流。

## 自定义解析器

实现 `FileExtractor` 并注册到 `ExtractorRegistry`：

```java
public class CustomExtractor implements FileExtractor {

    @Override
    public boolean supports(DocumentSource source) {
        return "custom".equalsIgnoreCase(getExtension(source.getFileName()));
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        try (InputStream input = source.openStream()) {
            byte[] bytes = IOUtils.toByteArray(input, 10 * 1024 * 1024);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("Failed to parse custom document", e);
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
```

注册并创建服务：

```java
ExtractorRegistry registry = new ExtractorRegistry();
registry.register(new CustomExtractor());

File2TextService service = new File2TextService(registry);
```

`getOrder()` 数值越小，尝试顺序越靠前。自定义解析器不应执行文档中的宏、脚本或外部命令。

## 错误处理

`File2TextService` 会记录候选解析器及失败原因，并尝试下一个候选解析器。

| 场景 | 结果 |
| --- | --- |
| `DocumentSource` 为 `null` | 抛出 `IllegalArgumentException` |
| 没有支持该格式的解析器 | 返回 `null` |
| 所有候选解析器失败 | 返回 `null`，并记录 `WARN` 日志 |
| 解析器成功但没有可读内容 | 继续尝试其他候选，最终可能返回 `null` |

生产环境应检查返回值：

```java
String text = service.extractTextFromFile(file);
if (text == null || text.trim().isEmpty()) {
    // 记录失败、进入人工处理或 OCR 流程
}
```

## 安全与性能

- Office 宏格式只读取文档内容，**不会执行宏**。
- PDF 使用 32 MB 内存阈值和临时文件混合加载，降低大文件堆内存压力。
- PDF 单张图片默认限制为 5000 万像素，异常超大图片会被跳过。
- 压缩包限制嵌套深度、条目数量和解压大小，降低 ZIP bomb 风险。
- HTTP 内容仅下载一次；小文件缓存在内存，大文件写入临时文件。
- 处理不可信文件时，仍建议在隔离进程中设置请求超时、堆内存上限和文件大小上限。
- `ByteStreamDocumentSource` 会将整个流缓存到内存；大流优先使用 `TemporaryFileStreamDocumentSource`。

## 已知边界

- 扫描 PDF 和普通图片尚未集成 OCR；PDF 中没有文本层时主要返回图片。
- PDF 通常没有语义化表格结构，基础解析器不会对任意坐标文本强行推断表格。
- 加密 Office、PDF、EPUB 或压缩包需要密码时，默认解析可能失败。
- Apple iWork 内部格式随应用版本变化，部分现代文件只能提取元数据和资源列表。
- MHTML 的复杂 multipart、内嵌脚本和动态网页行为不会完整还原。
- Tika 长尾格式以内容提取为目标，不保证完全保留源文档视觉布局。
- 默认的 Base64 图片会显著增加输出长度；生产环境建议配置 `ExtractedImageHandler`，在解析过程中直接替换为对象存储 URL。

## 推荐实践

1. 对流和 HTTP 下载尽量提供准确的文件名及 MIME。
2. 大文件使用临时文件数据源，并在入口处限制原始文件大小。
3. 将返回的分页、幻灯片或 Sheet 标记作为 RAG 分块边界。
4. 使用 `ExtractedImageHandler` 在解析图片时直接上传到对象存储，避免在最终文本中保留 Base64 数据。
5. 对扫描件配置独立 OCR 流程，并与 File2Text 的文本结果合并。
6. 对 `null` 结果保留原文件和解析日志，便于降级处理与格式补充。

</div>
