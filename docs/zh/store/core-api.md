<div v-pre>

# Store 核心 API 与数据流

## 概述

Store API 分为两层：`VectorStore<T>` 定义与数据类型无关的向量 CRUD；`DocumentStore` 在此基础上处理文档
切分、ID 生成和 Embedding。数据库模块只需要实现最终的 `doStore`、`doSearch`、`doUpdate` 和 `doDelete`。

理解这个分层有两个直接收益：

- 使用 Store 时，可以判断哪些行为由框架统一完成，哪些取决于具体数据库；
- 扩展新 Store 时，可以复用文档预处理流程，不必重复实现 Embedding 和 ID 管理。

## 使用场景

### 直接存储文档

业务提供正文和 metadata，由 `DocumentStore` 自动生成缺失的 ID 和向量。这是知识库导入最常见的方式。

### 存储预计算向量

离线任务已经批量完成 Embedding，写入时直接设置 `Document.vector`。Store 保留已有向量，不重复计算。

### 写入前自动切分

为 Store 配置 `DocumentSplitter`，让原始文档在写入前转换成多个 chunk。适合简单的在线导入流程；复杂 ETL
通常更适合在外部完成切分和版本管理。

### 实现新的数据库适配器

继承 `DocumentStore`，把通用对象映射到目标数据库，并在 `doSearch` 中转换查询条件和结果分值。

## API 层次

```text
VectorData
├── Document
└── SearchWrapper

VectorStore<T extends VectorData>
└── DocumentStore
    ├── RedisVectorStore
    ├── MilvusVectorStore
    ├── PgvectorVectorStore
    └── ...
```

`Document` 和 `SearchWrapper` 都继承 `VectorData`：前者表示要保存或返回的业务文档，后者复用其中的查询向量
能力。

## VectorData

`VectorData` 是最小向量对象：

| 字段 | 写入时 | 查询结果中 |
| --- | --- | --- |
| `float[] vector` | 待保存的向量，可为空 | 只有后端和 `outputVector` 支持时才返回 |
| `Float score` | 通常为空 | 表示相似度或归一化分值 |
| `metadataMap` | 业务扩展属性 | 由后端返回和字段控制决定 |

适配只接受集合类型向量的数据库客户端时，可以使用：

```java
List<Float> floats = vectorData.getVectorAsList();
List<Double> doubles = vectorData.getVectorAsDoubleList();

vectorData.setVectorByNumbers(numbers);
```

这些转换会创建新集合。高吞吐场景应避免在业务层反复转换大向量。

## Document

`Document` 在 `VectorData` 上增加：

| 字段 | 说明 |
| --- | --- |
| `id` | 字符串或数字 ID，具体支持范围由数据库决定 |
| `title` | 可选标题 |
| `content` | 用于 Embedding 和检索返回的正文 |

```java
Document document = Document.of("向量数据库接入指南");
document.setId("guide-001");
document.setTitle("Store 指南");
document.putMetadata("tenant", "tenant-a");
document.putMetadata("version", 3);
```

metadata 应使用稳定 schema。相同字段不要在一部分文档中写数字、另一部分写字符串，否则范围过滤和数据库字段
映射可能失效。

## VectorStore

`VectorStore<T>` 暴露统一操作：

```java
StoreResult store(T data);
StoreResult store(List<T> data, StoreOptions options);

StoreResult update(T data);
StoreResult update(List<T> data, StoreOptions options);

StoreResult delete(Collection<?> ids, StoreOptions options);

List<T> search(SearchWrapper wrapper, StoreOptions options);
```

单条方法最终委托给批量方法；未传 `StoreOptions` 的重载使用 `StoreOptions.DEFAULT`。统一方法签名不承诺每个
数据库有相同事务语义，例如批量写入可能出现部分成功。

## DocumentStore

`DocumentStore` 是模板方法实现。它保存三个可选组件：

| 组件 | 作用 | 默认行为 |
| --- | --- | --- |
| `EmbeddingModel` | 生成文档和查询向量 | 未配置时不自动生成 |
| `DocumentSplitter` | 写入前切分文档 | 默认不切分 |
| `DocumentIdGenerator` | 为缺少 ID 的文档生成 ID | 使用工厂提供的默认生成器 |

```java
store.setEmbeddingModel(embeddingModel);
store.setDocumentSplitter(splitter);
store.setDocumentIdGenerator(idGenerator);
```

## 写入流程

```text
store(documents, options)
  │
  ├─ options 为空 -> StoreOptions.DEFAULT
  ├─ 有 splitter -> splitAll(documents, idGenerator)
  ├─ 无 splitter -> 为缺少 ID 的原始文档生成 ID
  ├─ 为缺少 vector 的文档执行 Embedding
  └─ doStore(processedDocuments, options)
```

### 切分和 ID

切分后一个输入文档可能对应多个实际记录。建议 chunk metadata 至少保存：

- 原始文档 ID；
- chunk 序号；
- 文档版本；
- 数据来源；
- 权限或 tenant 标识。

稳定 chunk ID 是增量重建的基础。否则同一文档重复导入可能产生重复记录，也很难准确删除旧版本 chunk。

### 自动 Embedding

只有 `Document.vector == null` 且 Store 已配置 `EmbeddingModel` 时才生成向量。向量已存在时保持原值。

```java
Document document = Document.of("正文");
document.setVector(precomputedVector);
store.store(document); // 不会重复 Embedding
```

如果既没有向量也没有模型，父类仍会调用具体 Store。目标数据库通常会因为缺失向量或无法建索引而失败，所以
应在应用启动检查或导入校验中提前拦截。

## 更新流程

```text
update(documents, options)
  ├─ options 为空 -> StoreOptions.DEFAULT
  ├─ 为缺少 vector 的文档执行 Embedding
  └─ doUpdate(documents, options)
```

更新与写入有两个关键差异：不会执行文档切分，也不会自动生成缺失 ID。更新前必须提供数据库中已有的 ID。

如果只修改 metadata，但提交的 `Document.vector` 为空，父类仍可能重新调用 Embedding 模型。需要部分字段更新时，
应先确认目标 Store 的更新语义，避免不必要的模型调用或字段覆盖。

## 查询流程

```text
search(wrapper, options)
  │
  ├─ options 为空 -> StoreOptions.DEFAULT
  ├─ vector 为空 + 有模型 + withVector=true
  │      └─ embed(Document.of(wrapper.text), embeddingOptions)
  ├─ 将生成的向量写回 wrapper
  └─ doSearch(wrapper, options)
```

已有查询向量优先于文本：

```java
SearchWrapper query = new SearchWrapper().text("保留用于日志或展示的文本");
query.setVector(queryVector);

store.search(query); // 不会再次对 text 执行 Embedding
```

如果 Embedding 模型返回 `null`，查询会抛出 `ModelException`。`withVector(false)` 会跳过父类自动 Embedding，
但目标 Store 是否支持纯 metadata 查询需要单独确认。

## StoreOptions 与 EmbeddingOptions

`StoreOptions` 不只负责数据路由，其中的 `EmbeddingOptions` 会同时用于文档写入和查询文本向量化：

```java
StoreOptions options = StoreOptions.ofCollectionName("knowledge_v2");
options.setEmbeddingOptions(embeddingOptions);

store.store(documents, options);
store.search(query, options);
```

同一 Collection 中必须保持模型、维度和预处理策略一致。不要按请求切换到不兼容的 Embedding 模型。

## StoreResult

写入、更新和删除返回 `StoreResult`：

```java
StoreResult result = store.store(documents, options);
if (!result.isSuccess()) {
    log.error("store failed: {}", result.getMessage(), result.getException());
    // 根据错误类型决定重试、补偿或进入死信队列
}
```

| 属性 | 说明 |
| --- | --- |
| `success` | Store 对本次操作的成功判断 |
| `message` | 结果说明 |
| `exception` | 可选原始异常 |
| `ids` | 可选受影响 ID，具体实现不一定返回 |
| metadata | 后端可附加扩展信息 |

成功不代表目标记录原本一定存在，也不代表批量操作一定具有事务原子性。需要强一致语义时，要结合目标数据库
实现和集成测试判断。

## 异常边界

- 参数、配置或条件错误通常抛出 `IllegalArgumentException`；
- Embedding 返回异常数据可能抛出 `ModelException`；
- 数据库写操作可能返回 `StoreResult.fail(...)`，也可能抛出 `StoreException`；
- 查询没有 `StoreResult` 包装，失败通常直接抛出异常。

日志应包含操作类型、Collection/Index、批次大小和追踪 ID，不应记录密码、API Key、完整向量或敏感正文。

## 生命周期与并发

Store 通常持有数据库客户端或连接资源，推荐作为应用级单例复用，并在应用关闭时调用实现提供的 `close()`。
`SearchWrapper` 和 `StoreOptions` 是可变对象，应按请求创建，不要在并发请求之间共享和修改。

## 扩展实现边界

自定义 Store 至少需要处理：

1. Collection 或 Index 的默认值和单次覆盖；
2. ID、正文、标题、向量和 metadata 的序列化；
3. `Condition` 到数据库过滤语法的安全转换；
4. 距离到 score 的转换及 `minScore`；
5. 批量部分失败和异常映射；
6. `outputFields`、`outputVector` 和无向量查询的支持范围；
7. 客户端连接和关闭生命周期。

完整步骤参见[自定义 VectorStore](./custom-store)。

</div>
