# Splitter 文档分割器

<div v-pre>



## 1. 核心概念

在 RAG（Retrieval-Augmented Generation）场景中，文档往往过长，直接喂入大模型可能导致性能低下或上下文截断。**文档分割器（Document Splitter）** 的作用就是将长文档拆分成多个逻辑段落或 token 块，保证每一块都在语义或长度上合理，同时便于后续向量化和检索。

### 1.1 DocumentSplitter 接口

`DocumentSplitter` 是所有分割器的统一接口，核心方法为：

```java
List<Document> split(Document document, DocumentIdGenerator idGenerator);
```

- **document**：待拆分的文档对象。
- **idGenerator**：可选，用于为拆分后的每个文档生成唯一 ID。
- **`splitAll(List<Document>, idGenerator)`**：支持对文档列表进行批量拆分。


### 1.2 核心实现类型

Agents-Flex 提供了多种 `DocumentSplitter` 实现，适用于不同场景：

| 分割器                      | 特点                               | 适用场景                |
|--------------------------| -- | - |
| `SimpleDocumentSplitter` | 按固定字符长度拆分，可设置 overlap            | 文本长度固定或无需语义感知       |
| `SimpleTokenizeSplitter` | 按 token（编码）拆分，可解决 Unicode/中文字符问题 | 对接 LLM，保证 token 对齐  |
| `RegexDocumentSplitter`  | 按正则表达式拆分                         | 文本有固定分隔符，如段落、换行符、符号 |
| `AIDocumentSplitter`     | 基于 LLM/AI 语义拆分，保持逻辑完整            | 文档长度长，语义复杂，需 AI 感知  |



## 2. 快速入门


### 2.1 示例：字符块拆分

```java
Document doc = new Document();
doc.setContent("这里是一段很长的文本，需要拆分为多个小块以便检索。");

// 每块 100 字，重叠 10 字
SimpleDocumentSplitter splitter = new SimpleDocumentSplitter(100, 10);
List<Document> chunks = splitter.split(doc, id -> "doc-" + System.nanoTime());

chunks.forEach(c -> System.out.println(c.getContent()));
```

### 2.2 示例：正则拆分

```java
 // 按双换行拆分
RegexDocumentSplitter regexSplitter = new RegexDocumentSplitter("\\n\\n");
List<Document> regexChunks = regexSplitter.split(doc, id -> "doc-" + System.nanoTime());
```

### 2.3 示例：AI 语义拆分

```java
// 假设 chatModel 为已初始化
AIDocumentSplitter aiSplitter = new AIDocumentSplitter(chatModel);
aiSplitter.setMaxChunks(10); // 最大拆分块数
aiSplitter.setFallbackSplitter(new SimpleDocumentSplitter(200)); // 设置 fallback 拆分器

List<Document> aiChunks = aiSplitter.split(doc, id -> "doc-" + System.nanoTime());
```



## 3. 配置与高级应用


### 3.1 AI 拆分器（AIDocumentSplitter）高级配置

```java
aiSplitter.setSplitPromptTemplate(
    "请将以下文档按语义拆分，每块不超过300字，用  分隔:\n{document}"
);

ChatOptions options = new ChatOptions.Builder()
    .temperature(0.1f)
    .maxTokens(500)
    .build();

aiSplitter.setChatOptions(options);
```

自定义 fallback

```java
AIDocumentSplitter aiSplitter = new AIDocumentSplitter(chatModel);
SimpleDocumentSplitter fallback = new SimpleDocumentSplitter(200);
aiSplitter.setFallbackSplitter(fallback);
```

* 如果 AI 拆分失败，会自动调用 fallback。
* fallback 可是任何实现了 `DocumentSplitter` 的类。


### 3.2 Token 拆分器（SimpleTokenizeSplitter）注意事项

`SimpleTokenizeSplitter` 使用 `jtokkit` 编码器，能保证与 OpenAI token 对齐：

```java
SimpleTokenizeSplitter tokenSplitter = new SimpleTokenizeSplitter(256, 32);
tokenSplitter.setEncodingType(EncodingType.CL100K_BASE);
```

* **chunkSize**：token 数量，而非字符数。
* **overlapSize**：token 重叠数量。
* 自动处理 Unicode Replacement Character（0xFFFD）问题，避免中文乱码。


### 3.3 批量拆分

```java
List<Document> docs = Arrays.asList(doc1, doc2, doc3);
List<Document> allChunks = splitter.splitAll(docs, id -> "doc-" + System.nanoTime());
```

* `splitAll` 会自动处理空文档。
* 支持任意 `DocumentSplitter` 实现。



## 4. 实践建议

1. **文本长度与模型上下文**：AI 拆分器最好不要直接喂入超过模型最大上下文的文档，可先使用简单字符/Token 拆分器做初步截断。
2. **fallback**：建议总是配置 fallback，保证拆分鲁棒性。
3. **重叠**：适用于 token 拆分器，可在长文本中保留语义连续性，避免段落被截断。
4. **正则拆分**：适合固定格式文档，如 Markdown、HTML 或日志文件。
5. **调优 prompt**（AI 拆分器）：可根据文档类型定制拆分规则，比如按章节、表格、代码块等。



</div>
