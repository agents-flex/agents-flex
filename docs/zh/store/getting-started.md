<div v-pre>

# Store 快速开始

## 概述

本章使用 Redis Stack 搭建一个最小但完整的向量知识库：启动数据库、创建 Store、写入文档、执行带 metadata
过滤的语义搜索，最后完成更新和删除。

完成后，你将理解 Store 最基本的四个对象：

| 对象 | 本章用途 |
| --- | --- |
| `RedisVectorStore` | 连接 Redis Stack 并执行存储操作 |
| `Document` | 保存正文、ID 和 metadata |
| `SearchWrapper` | 描述查询文本、召回数量和过滤条件 |
| `StoreOptions` | 指定本次操作使用的 Collection |

## 使用场景

本章适合第一次接入 Store，或者需要先验证统一 API 的开发者。Redis Stack 只是便于本地启动的示例；业务代码
的基本结构同样适用于其他 Store，但配置项和数据库能力不能直接照搬。

## 前置条件

- JDK 8 或更高版本；
- Maven 3.6 或更高版本；
- Docker；
- 一个可用的 `EmbeddingModel`。

普通 Redis 不包含 RedisJSON 和 RediSearch，不能运行 `RedisVectorStore`，必须使用 Redis Stack。

## 第一步：启动 Redis Stack

```bash
docker run --name agents-flex-redis \
  -p 6379:6379 \
  -v agents-flex-redis-data:/data \
  -d redis/redis-stack-server:latest
```

检查服务和模块：

```bash
docker exec agents-flex-redis redis-cli PING
docker exec agents-flex-redis redis-cli MODULE LIST
```

第一条命令应返回 `PONG`，模块列表应包含 Search 和 RedisJSON。`latest` 仅适合本地体验，团队环境应固定镜像
版本。

## 第二步：添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-redis</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

供应商模块会传递依赖 `agents-flex-core`。如果改用其他数据库，请替换为对应的 Store 模块。

## 第三步：创建 Store

```java
RedisVectorStoreConfig config = new RedisVectorStoreConfig();
config.setUri("redis://127.0.0.1:6379");
config.setStorePrefix("agentsflex:docs:");
config.setDefaultCollectionName("knowledge_base");

RedisVectorStore store = new RedisVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

示例中的 `embeddingModel` 由应用创建。它负责把正文和查询文本转换为 `float[]`。首次创建向量索引时会使用
`embeddingModel.dimensions()`，因此模型输出维度必须稳定。

::: tip 已经有向量时
可以直接设置 `Document.vector` 和 `SearchWrapper.vector`。这种情况下 Store 不会重复调用 Embedding 模型。
:::

## 第四步：写入文档

```java
Document guide = Document.of("Agents-Flex Store 提供统一的向量存储接口。");
guide.setId("doc-001");
guide.setTitle("Store 使用指南");
guide.putMetadata("tenant", "demo");
guide.putMetadata("category", "guide");
guide.putMetadata("year", 2026);

StoreResult stored = store.store(guide);
if (!stored.isSuccess()) {
    throw new IllegalStateException(
        stored.getMessage(), stored.getException()
    );
}
```

写入前 `DocumentStore` 会为缺少向量的文档生成向量。未设置 ID 且没有配置切分器时，还会使用当前
`DocumentIdGenerator` 生成 ID。生产导入建议使用稳定业务 ID，便于幂等更新和删除。

批量写入使用同一个接口：

```java
StoreResult result = store.store(Arrays.asList(guide, faq, reference));
```

批量是否具备事务原子性取决于数据库实现。不要因为 `store(List)` 是一个 Java 调用，就假设后端一定是一个
事务。

## 第五步：执行语义检索

```java
SearchWrapper query = new SearchWrapper()
    .text("怎样使用向量数据库保存知识")
    .maxResults(5)
    .minScore(0.60)
    .eq("tenant", "demo")
    .in("category", Arrays.asList("guide", "reference"));

List<Document> documents = store.search(query);
for (Document document : documents) {
    System.out.printf(
        "id=%s, score=%s, title=%s%n",
        document.getId(),
        document.getScore(),
        document.getTitle()
    );
}
```

执行过程包括两部分：先把查询文本转换为向量，再由 Redis 同时执行 KNN 召回和 metadata 过滤。`minScore`
不是跨数据库统一标尺，切换 Store 后需要使用真实数据重新校准。

## 第六步：更新和删除

```java
guide.setContent("Agents-Flex Store 统一了文档的写入、检索、更新和删除。");

StoreResult updated = store.update(guide);
if (!updated.isSuccess()) {
    throw new IllegalStateException(updated.getMessage(), updated.getException());
}

StoreResult deleted = store.delete(guide.getId().toString());
if (!deleted.isSuccess()) {
    throw new IllegalStateException(deleted.getMessage(), deleted.getException());
}
```

更新时不会再次切分，也不会为文档补充缺失 ID；但正文变化且向量为空时，会重新调用 Embedding 模型。不同后端
对不存在 ID 的更新可能是无操作、失败或 upsert，必须查看对应实现文档。

## 第七步：使用独立 Collection

默认 Collection 适合单知识库。多个知识库或需要物理隔离时，为每次操作传入相同的 `StoreOptions`：

```java
StoreOptions productDocs =
    StoreOptions.ofCollectionName("product_docs_2026");

store.store(Arrays.asList(guide, faq), productDocs);
List<Document> found = store.search(query, productDocs);
store.delete(Collections.singletonList("doc-001"), productDocs);
```

只在写入时传 Collection、查询时使用默认值，会得到“写入成功但查询不到”的结果。更新和删除也必须保持相同
路由。

## 使用预计算向量

当向量由外部服务批量生成时，可以跳过 Store 的自动 Embedding：

```java
Document document = Document.of("已完成向量化的文档");
document.setId("doc-vector-001");
document.setVector(documentVector);
store.store(document);

SearchWrapper query = new SearchWrapper().maxResults(10);
query.setVector(queryVector);

List<Document> result = store.search(query);
```

写入向量和查询向量必须来自同一模型、相同版本和相同维度。更换模型后不要把新旧向量混在同一个 Collection。

## 常见问题

### Redis 命令不存在

确认连接的是 Redis Stack。普通 Redis 会出现 `FT.INFO`、`FT.SEARCH` 或 JSON 命令未知。

### 写入成功但查询为空

依次检查 Collection 是否一致、查询向量维度是否正确、`minScore` 是否过高，以及 metadata 字段的值和类型是否
与条件一致。

### 查询文本没有生成向量

确认 Store 已设置 `EmbeddingModel`、`SearchWrapper.withVector` 没有关闭，并且查询文本不是空值。

### 条件在某个 Store 上无效

`SearchWrapper` 能构造条件不代表每个 Store 都已接入条件查询。先查看[存储选型与能力矩阵](./providers)和目标
数据库文档。

## 从示例走向生产

- 固定数据库镜像和客户端版本；
- 使用认证、TLS、持久卷和受控网络；
- 为文档 ID、metadata schema 和 Collection 命名制定规范；
- 使用生产 Embedding 模型执行真实召回率和阈值测试；
- 覆盖批量部分失败、网络重试、维度不匹配和跨 Collection 隔离；
- 对数据库连接、写入失败、查询延迟、索引大小和 Embedding 调用建立监控。

## 下一步

- [核心 API 与数据流](./core-api)
- [SearchWrapper 查询构造](./search-wrapper)
- [StoreOptions 与多集合](./store-options)
- [RedisVectorStore](./redis)

</div>
