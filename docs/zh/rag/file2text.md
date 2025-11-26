# File2Text 模块
<div v-pre>


## 1. 概念介绍

`File2Text` 是一个文档文本提取服务，旨在从多种类型的文档（包括本地文件、HTTP 文档、流、字节数组）中提取纯文本内容。其核心特点如下：

1. **统一接口**：无论文档来源于本地文件、HTTP URL、流还是字节数组，使用统一的 `extractTextFromSource` 方法进行处理。
2. **多提取器机制**：支持多种 `FileExtractor` 实现，每种提取器可针对不同文件类型（.doc, .docx, .pdf, .txt, .html 等）提取文本。系统会根据文件类型选择合适的提取器，支持降级重试。
3. **灵活的文档源**：通过 `DocumentSource` 抽象，将文件来源统一封装，支持自定义扩展。
4. **缓存与优化**：对 HTTP 文档可进行缓存（内存或临时文件），避免重复下载。


## 2. 快速入门


### 2.1 创建服务实例

```java
File2TextService file2TextService = new File2TextService();
```

### 2.2 从本地文件提取文本

```java
File file = new File("/path/to/document.docx");
String text = file2TextService.extractTextFromFile(file);
System.out.println(text);
```

### 2.3 从 HTTP URL 提取文本

```java
String url = "https://example.com/sample.pdf";
String text = file2TextService.extractTextFromHttpUrl(url);
System.out.println(text);
```

### 2.4 从输入流提取文本

```java
try (InputStream is = new FileInputStream("/path/to/sample.txt")) {
    String text = file2TextService.extractTextFromStream(is, "sample.txt", "text/plain");
    System.out.println(text);
}
```

### 2.5 从字节数组提取文本

```java
byte[] bytes = Files.readAllBytes(Paths.get("/path/to/sample.doc"));
String text = file2TextService.extractTextFromBytes(bytes, "sample.doc", "application/msword");
System.out.println(text);
```



## 3. 提取器机制

`File2Text` 使用 **多提取器策略**，每种提取器实现 `FileExtractor` 接口，并定义 `supports(DocumentSource)` 方法判断是否可以处理该文档。

### 内置提取器

| 提取器类                 | 支持类型                                     |
| -- | - |
| `DocExtractor`       | .doc (Word 97-2003)                      |
| `DocxExtractor`      | .docx / .dotx                            |
| `PdfTextExtractor`   | .pdf                                     |
| `PptxExtractor`      | .pptx                                    |
| `HtmlExtractor`      | .html / .htm                             |
| `PlainTextExtractor` | .txt, .md, .csv, .json, .xml, .log 等文本文件 |

提取器通过 `getOrder()` 设置优先级，数值越小优先级越高。

### 自定义提取器

```java
public class CustomExtractor implements FileExtractor {

    @Override
    public boolean supports(DocumentSource source) {
        return source.getFileName().endsWith(".custom");
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        try (InputStream is = source.openStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}

// 注册
ExtractorRegistry registry = new ExtractorRegistry();
registry.register(new CustomExtractor());
File2TextService service = new File2TextService(registry);
```



## 4. 高级应用与配置

### 4.1 HTTP 文档高级配置

`HttpDocumentSource` 支持以下高级特性：

* **自定义超时**：

```java
HttpDocumentSource source = new HttpDocumentSource(
    "https://example.com/sample.pdf",
    null,
    null,
    10000,  // connectTimeout 10s
    30000,  // readTimeout 30s
    null
);
```

* **自定义 HTTP Header**：

```java
HttpDocumentSource source = new HttpDocumentSource(
    "https://example.com/sample.pdf",
    null,
    null,
    20000,
    60000,
    conn -> conn.setRequestProperty("Authorization", "Bearer token")
);
```

* **缓存机制**：

    * 小文件（<10MB）使用内存缓存
    * 大文件使用临时文件缓存，自动删除

* **获取缓存大小**：

```java
long size = source.getCachedSize();
System.out.println("Downloaded size: " + size + " bytes");
```

### 4.2 使用自定义 `DocumentSource`

你可以实现自己的 `DocumentSource`，用于读取任意来源的文档：

```java
public class S3DocumentSource implements DocumentSource {

    private final String s3Key;

    public S3DocumentSource(String s3Key) {
        this.s3Key = s3Key;
    }

    @Override
    public String getFileName() {
        return s3Key;
    }

    @Override
    public String getMimeType() {
        return "application/pdf";
    }

    @Override
    public InputStream openStream() {
        // 使用 AWS SDK 下载文件
        return s3Client.getObject(bucket, s3Key).getObjectContent();
    }

    @Override
    public void cleanup() {
        // 可选，释放资源
    }
}
```

### 4.3 错误处理与降级

`File2TextService` 在提取文本时会尝试所有可用的提取器，并在失败时记录日志：

* **日志级别**：

    * `INFO`：列出候选提取器
    * `DEBUG`：提取器尝试结果
    * `WARN`：提取失败或无支持提取器

* **异常处理**：

    * `IllegalArgumentException`：当 `DocumentSource` 为 null
    * `IOException`：提取器内部错误
    * 返回 null：当没有可用提取器或所有提取器失败

### 4.4 批量处理文件

```java
List<File> files = List.of(file1, file2, file3);
List<String> texts = files.stream()
    .map(file2TextService::extractTextFromFile)
    .collect(Collectors.toList());
```


</div>
