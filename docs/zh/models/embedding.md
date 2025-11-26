# Embedding 模型
<div v-pre>



## 1. 核心概念

### 1.1 Embedding 的作用

Embedding 是将文本、文档或其他数据映射为固定维度的向量表示的技术。在 RAG、向量数据库或语义搜索中，Embedding 可用于：

* **语义检索**：通过向量相似度进行查询。
* **文档聚类**：按语义对文档进行分组。
* **增强生成模型**：将检索到的向量作为上下文输入 LLM。

### 1.2 EmbeddingModel 接口

`EmbeddingModel` 是生成文本向量的统一接口，核心方法包括：

```java
VectorData embed(Document document, EmbeddingOptions options);
int dimensions(); // 返回向量维度
```

接口提供了默认方法，支持：

* 直接嵌入文本字符串
* 使用默认 `EmbeddingOptions`

### 1.3 EmbeddingOptions

`EmbeddingOptions` 用于控制嵌入生成的参数：

* **model**：指定使用的嵌入模型
* **encodingFormat**：嵌入编码格式（通常为 float 或 base64）
* **DEFAULT**：不可修改的默认实例，保证快速调用。



## 2. 快速入门

以下示例展示如何使用 EmbeddingModel 生成向量。

### 2.1 使用默认模型

```java
EmbeddingModel model = new OpenAIEmbeddingModel(config);
VectorData vector = model.embed("Hello, Agents-Flex!");
System.out.println("向量维度：" + vector.getVector().length);
```

### 2.2 使用自定义 Document

```java
Document doc = Document.of("这是一个测试文本");
VectorData vector = model.embed(doc);
System.out.println("向量内容：" + Arrays.toString(vector.getVector()));
```

### 2.3 指定嵌入选项

```java
EmbeddingOptions options = new EmbeddingOptions();
options.setModel("text-embedding-3-small");
options.setEncodingFormat("float");

VectorData vector = model.embed(doc, options);
```



## 3. 配置与高级应用

### 3.1 配置 Embedding 模型

在 OpenAIEmbeddingModel 中，可以通过 `OpenAIEmbeddingConfig` 配置：

* **API Key**
* **Endpoint**：如 `https://api.openai.com`
* **默认模型**：如 `text-embedding-3-large`

```java
OpenAIEmbeddingConfig config = new OpenAIEmbeddingConfig();
config.setApiKey("YOUR_API_KEY");
config.setEndpoint("https://api.openai.com");
config.setModel("text-embedding-3-large");

EmbeddingModel model = new OpenAIEmbeddingModel(config);
```

### 3.2 自定义 HTTP 客户端

`OpenAIEmbeddingModel` 使用 `HttpClient` 发送请求，可替换自定义客户端实现：

```java
HttpClient customClient = new HttpClient();
model.setHttpClient(customClient);
```

### 3.3 高级 EmbeddingOptions

* **多模型支持**：在同一系统中针对不同任务使用不同模型。
* **向量编码格式**：可选 `float` 或 `base64`，根据存储和传输需求选择。
* **默认与覆盖**：使用 `options.getModelOrDefault(config.getModel())` 获取模型名称，保证灵活性。

### 3.4 向量数据处理

生成的向量保存在 `VectorData` 中：

```java
VectorData vector = model.embed(doc, options);
double[] array = vector.getVector();
System.out.println("向量维度: " + array.length);
```

可用于：

* **向量存储**：写入向量数据库（如 Milvus、FAISS）
* **相似度检索**：计算余弦相似度或欧氏距离
* **下游模型输入**：用于 RAG 系统中的语义增强

### 3.5 错误处理

* 当 `VectorData` 为 `null` 或向量为空时，会抛出 `ModelException`。
* 建议在调用 `embed` 时进行异常捕获：

```java
try {
    VectorData vector = model.embed(doc);
} catch (ModelException e) {
    System.err.println("Embedding 失败: " + e.getMessage());
}
```

### 3.6 批量嵌入

对于大规模文档，可封装批量处理逻辑，减少 HTTP 请求次数，提高效率。



## 4. 总结

`EmbeddingModel` 提供统一接口生成文本向量，是 RAG、语义检索、聚类和增强生成模型的重要基础模块。通过合理配置 `EmbeddingOptions` 与模型参数，开发者可以：

* 快速生成高质量向量
* 支持多模型和多格式
* 与向量存储和搜索引擎高效集成

本模块设计目标：**简单调用、灵活配置、可扩展**，为构建语义智能系统提供核心能力。






</div>
