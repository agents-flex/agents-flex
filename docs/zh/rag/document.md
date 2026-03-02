# 文档概念（Document）
<div v-pre>

在 RAG（Retrieval-Augmented Generation）和向量检索系统中，**文档（Document）** 是信息处理的核心单位。它不仅承载原始文本内容，还包含向量表示、元数据、唯一标识和可选的评分信息，从而支持文档拆分、向量化、检索和排序。

## 1. Document 的核心结构

`Document` 类继承自 `VectorData`，而 `VectorData` 又继承自 `Metadata`。因此，`Document` 同时具备：

* **文本内容与标识**
* **向量存储与检索能力**
* **可扩展元数据管理功能**

核心字段和功能如下：

| 字段/功能         | 类型                   | 描述                       |
| - | -- |--------------------------|
| `id`          | `Object`             | 文档唯一标识，可用于索引和查找。         |
| `title`       | `String`             | 文档标题，用于标识或展示。            |
| `content`     | `String`             | 文档正文内容，是向量化和检索的基础。       |
| `vector`      | `double[]`           | 文档向量表示，用于向量检索。           |
| `score`       | `Double`             | 文档相关性或排序得分，0~1，值越大相似度越高。 |
| `metadataMap` | `Map<String,Object>` | 可存储任意自定义元数据，如来源、标签、时间戳等。 |

### 1.1 ID

* 用于唯一标识文档，可为字符串、数字或任意对象。
* 在拆分文档或生成向量索引时，通常通过 `DocumentIdGenerator` 自动生成唯一 ID。

### 1.2 标题与内容

* `title` 描述文档主题或来源，用于展示或摘要。
* `content` 是文档的核心文本，拆分器、向量化器和检索器都以此为基础进行操作。

### 1.3 向量和得分

* `vector` 是文档向量表示，通过向量化器生成，可用于相似性搜索。
* `score` 用于存储检索相关性、rerank结果或排序分数，支持 0~1 的范围，值越大表示越高的相似度。

### 1.4 元数据管理

继承自 `Metadata`，文档支持任意键值对的元数据存储：

```java
doc.addMetadata("source", "wiki");
doc.addMetadata("tags", Arrays.asList("AI", "RAG"));
Object source = doc.getMetadata("source");
```

* 可用于标注文档来源、分类、时间戳、用户信息等。
* 元数据与向量、内容一同存储和检索，便于结果展示和后续处理。



## 2. Document 的创建方式

### 2.1 默认构造器

```java
Document doc = new Document();
doc.setContent("这是文档内容");
doc.setTitle("文档标题");
doc.setId("doc-001");
```

### 2.2 内容构造器

```java
Document doc = new Document("这是文档内容");
doc.setTitle("文档标题");
doc.setId("doc-001");
```

### 2.3 静态工厂方法

```java
Document doc = Document.of("这是文档内容");
```

* 提供简洁方式创建 `Document` 对象。
* 自动设置 `content` 字段，便于快速生成文档实例。



## 3. Document 与向量存储的关系

由于 `Document` 继承自 `VectorData`，每个文档可以生成对应的向量表示：

* 向量用于近似搜索（ANN）或相似度计算。
* `vector` 与 `metadataMap`、`id`、`title` 等信息关联，用于检索结果展示和 rerank。
* `score` 可动态更新，用于排序或过滤。

```java
doc.setVector(embedding); // embedding 是 double[] 类型
doc.setScore(similarityScore); // similarityScore ∈ [0,1]
```



## 4. 实用方法

| 方法                                                                  | 描述                  |
| - | - |
| `getId()` / `setId(Object id)`                                      | 获取或设置文档唯一标识。        |
| `getTitle()` / `setTitle(String title)`                             | 获取或设置文档标题。          |
| `getContent()` / `setContent(String content)`                       | 获取或设置文档正文内容。        |
| `getVector()` / `setVector(double[] vector)`                        | 获取或设置文档向量。          |
| `getScore()` / `setScore(Double score)`                             | 获取或设置文档相似度/排序得分。    |
| `getMetadata(String key)` / `addMetadata(String key, Object value)` | 元数据访问和管理方法。         |
| `toString()`                                                        | 返回文档完整信息，便于调试和日志记录。 |



</div>
