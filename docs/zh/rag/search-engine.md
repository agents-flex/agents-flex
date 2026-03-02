# 搜索引擎
<div v-pre>


## 1. 核心概念

### 1.1 RAG 中的搜索引擎作用
在 RAG 架构中，搜索引擎用于在大量文档中快速定位与查询相关内容。它在多路复用（multi-pass retrieval）、重排序（rerank）以及上下文增强（context augmentation）等场景中发挥关键作用。

主要功能包括：
- **文档索引**：将文档结构化存储以便高效搜索。
- **全文检索**：支持基于关键词的快速匹配。
- **文档更新与删除**：保证检索数据的动态性。
- **支持多种引擎**：如 Elasticsearch、Lucene 等，可根据系统需求选择。

### 1.2 搜索引擎接口
搜索引擎模块通常定义统一接口 `DocumentSearcher`，核心方法包括：
```java
boolean addDocument(Document document); // 添加文档
boolean deleteDocument(Object id);      // 删除文档
boolean updateDocument(Document document); // 更新文档
List<Document> searchDocuments(String keyword, int count); // 搜索文档
```



## 2. 快速入门

以下示例展示如何使用 Lucene 和 Elasticsearch 两种搜索引擎实现 RAG 的文档检索功能。

### 2.1 Lucene 搜索引擎
Lucene 是一个轻量级、高性能的全文搜索库，适合嵌入式场景。

#### 2.1.1 初始化
```java
LuceneConfig config = new LuceneConfig();
config.setIndexDirPath("/path/to/lucene/index");
LuceneSearcher searcher = new LuceneSearcher(config);
```

#### 2.1.2 添加文档
```java
Document doc = new Document();
doc.setId("1");
doc.setTitle("RAG 介绍");
doc.setContent("RAG 是结合检索与生成的 AI 架构...");
searcher.addDocument(doc);
```

#### 2.1.3 搜索文档
```java
List<Document> results = searcher.searchDocuments("RAG", 10);
for (Document d : results) {
    System.out.println(d.getTitle() + ": " + d.getContent());
}
```

#### 2.1.4 更新与删除文档
```java
doc.setContent("更新后的内容...");
searcher.updateDocument(doc);
searcher.deleteDocument("1");
```

### 2.2 Elasticsearch 搜索引擎
Elasticsearch 是分布式搜索引擎，适合大规模文档检索。

#### 2.2.1 初始化
```java
ESConfig esConfig = new ESConfig();
esConfig.setHost("https://localhost:9200");
esConfig.setUserName("elastic");
esConfig.setPassword("password");
esConfig.setIndexName("rag_docs");

ElasticSearcher esSearcher = new ElasticSearcher(esConfig);
```

#### 2.2.2 添加文档
```java
esSearcher.addDocument(doc);
```

#### 2.2.3 搜索文档
```java
List<Document> esResults = esSearcher.searchDocuments("RAG", 10);
```

#### 2.2.4 更新与删除文档
```java
esSearcher.updateDocument(doc);
esSearcher.deleteDocument("1");
```


## 3. 高级配置与优化

### 3.1 索引优化
- **Lucene**：可调整 `Analyzer` 类型以优化中文分词或英文搜索性能，如 `JcsegAnalyzer`。
- **Elasticsearch**：可配置 `mapping` 和 `analyzer` 来提高搜索精度。

### 3.2 搜索优化
- **多字段搜索**：同时对 `title` 和 `content` 字段进行搜索，使用布尔查询（BooleanQuery）。
- **Top-K 控制**：通过 `count` 控制返回结果数量，减少无关结果。
- **评分与重排序**：检索后可基于向量相似度或自定义评分函数对结果进行 rerank。



</div>
