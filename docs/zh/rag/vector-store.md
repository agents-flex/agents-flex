# 向量存储 Vector Store
<div v-pre>


## 1. 概念

向量存储（Vector Store）是现代 AI 系统，尤其是 RAG 系统中，用于存储和检索高维向量数据的核心组件。其主要目的是将文本或文档映射为向量（Embedding），并通过相似度搜索快速找到相关内容，从而支持问答、推荐和知识检索等功能。

### 核心概念

1. **向量（Vector）**
   文本或文档通过 Embedding 模型转换为固定维度的浮点数组。例如，句子 `"Hello world"` 可能映射为 `[0.12, -0.05, ...]`。

2. **向量数据（VectorData）**
   `VectorData` 是向量存储操作的基本单元，包含向量本身及相关 metadata。

3. **向量存储（VectorStore）**
   抽象类 `VectorStore<T extends VectorData>` 定义了向量的增删改查接口：

    * `store`：存储向量数据。
    * `delete`：删除向量数据。
    * `update`：更新向量数据。
    * `search`：根据相似度检索向量数据。

4. **文档存储（DocumentStore）**
   `DocumentStore` 是 `VectorStore<Document>` 的子类，专门处理文档类型的数据。它增加了：

    * **文档拆分器（DocumentSplitter）**：将大文档拆分为子段落或块。
    * **文档 ID 生成器（DocumentIdGenerator）**：生成唯一 ID。
    * **嵌入模型（EmbeddingModel）**：将文本自动转换为向量。

5. **搜索包装器（SearchWrapper）**
   封装搜索请求，支持：

    * `text`：待搜索文本。
    * `vector`：向量，可通过 Embedding 模型生成。
    * `maxResults`：返回结果数量。
    * `minScore`：相似度阈值。
    * 条件查询（`eq`, `gt`, `in`, `between` 等）。
    * 输出字段控制（`outputFields`, `outputVector`）。

6. **距离度量**
   向量相似度可使用：

    * **COSINE**：余弦相似度。
    * **L2**：欧氏距离。
    * **IP**：内积距离。



## 2. 快速入门

以下示例展示如何在 RAG 框架中使用向量存储。

### 2.1 初始化 RedisVectorStore

```java
RedisVectorStoreConfig config = new RedisVectorStoreConfig();
config.setUri("redis://localhost:6379");
config.setDefaultCollectionName("docs");
config.setStorePrefix("vec:");

RedisVectorStore vectorStore = new RedisVectorStore(config);

// 配置 Embedding 模型
vectorStore.setEmbeddingModel(myEmbeddingModel);

// 可选：配置文档拆分器
vectorStore.setDocumentSplitter(myDocumentSplitter);
```

### 2.2 存储文档

```java
Document doc = new Document();
doc.setContent("This is a sample document about AI.");
doc.addMetadata("author", "John Doe");

StoreResult result = vectorStore.store(doc);
System.out.println("Store success: " + result.isSuccess());
```

### 2.3 搜索文档

```java
SearchWrapper search = new SearchWrapper()
    .text("AI")
    .maxResults(5);

List<Document> results = vectorStore.search(search);

for (Document d : results) {
    System.out.println("ID: " + d.getId() + ", Content: " + d.getContent() + ", Score: " + d.getScore());
}
```

### 2.4 更新文档

```java
doc.setContent("Updated content about AI and ML.");
vectorStore.update(doc);
```

### 2.5 删除文档

```java
vectorStore.delete(doc.getId());
```



## 3. 配置与高级应用

### 3.1 向量存储配置

* **索引名称**：通过 `StoreOptions` 指定集合名，否则使用 `defaultCollectionName`。
* **向量属性**：

    * 类型：`FLOAT32`
    * 距离度量：`COSINE`、`L2`、`IP`
    * 维度：通过 Embedding 模型决定
* **Redis HNSW 算法**：支持高效 KNN 搜索

### 3.2 条件搜索

`SearchWrapper` 支持复杂查询：

```java
SearchWrapper search = new SearchWrapper()
    .text("AI")
    .eq("author", "John Doe")
    .ge("publishYear", 2020)
    .between("rating", 4, 5);
```

支持分组查询和逻辑组合：

```java
search.andCriteria(s -> s.eq("category", "Tech").gt("views", 1000))
      .orCriteria(s -> s.eq("category", "Science").lt("views", 500));
```

</div>
