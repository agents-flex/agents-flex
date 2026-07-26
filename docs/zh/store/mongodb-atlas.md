<div v-pre>

# MongoDBAtlasVectorStore

## 概述

`MongoDBAtlasVectorStore` 基于 MongoDB 官方同步 Java Driver 和 Atlas Vector Search 实现文档向量存储。
Agents-Flex 的 Collection 映射为 MongoDB Collection，向量查询通过聚合管道中的 `$vectorSearch` 执行，
metadata 条件作为服务端预过滤条件参与向量召回。

当前模块支持：

- 批量写入、覆盖更新和按 ID 删除；
- 自动创建 MongoDB Collection 和 Atlas Vector Search Index；
- cosine、dotProduct 和 euclidean 三种相似度；
- EQ、NE、范围、BETWEEN、IN、NOT IN、NULL 和嵌套 AND/OR/NOT；
- SQL 风格条件表达式；
- `withVector(false)` 纯 MongoDB 条件查询；
- `outputFields`、`outputVector`、`minScore` 和 `maxResults`；
- 多 Collection 和多 Vector Search Index 路由；
- 等待 `mongot` 完成新增、更新和删除同步，保证调用返回后的检索可见性。

## 使用场景

适合以下场景：

- 业务数据已经保存在 MongoDB Atlas，希望避免额外维护一个向量数据库；
- RAG 文档既需要普通 BSON 查询，也需要向量语义检索；
- metadata 结构经常演进，但过滤字段集合相对明确；
- 希望使用 Atlas 的托管、备份、权限、监控和弹性扩缩容能力；
- 需要通过独立 Collection 隔离租户、知识库或 Embedding 模型版本。

如果只运行普通 MongoDB Community Server，并且没有 Atlas Search 的 `mongot`，则不能使用 `$vectorSearch`。
本模块的向量能力面向 MongoDB Atlas 和 Atlas Local，不应把普通 MongoDB Docker 当作等价测试环境。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-mongodb-atlas</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

模块使用 MongoDB 官方同步 Java Driver，不通过非官方 HTTP 协议访问 Atlas。

## 本地安装

### 启动 Atlas Local

官方 Atlas Local 镜像同时包含单节点 MongoDB 副本集和本地开发版 `mongot`：

```bash
docker run -d --name agents-flex-mongodb-atlas \
  -p 127.0.0.1:27018:27017 \
  mongodb/mongodb-atlas-local:8.3.4
```

这里映射到本机 `27018`，避免与普通 MongoDB 的默认 `27017` 冲突。等待初始化并验证：

```bash
docker exec agents-flex-mongodb-atlas \
  mongosh --quiet --eval 'db.runCommand({ping: 1})'

docker exec agents-flex-mongodb-atlas \
  mongosh --quiet --eval 'db.version()'
```

连接字符串：

```text
mongodb://127.0.0.1:27018/?directConnection=true
```

`directConnection=true` 很重要，因为容器内单节点副本集通告的是容器主机名。本地测试应固定镜像版本；生产环境
使用 MongoDB Atlas 云集群，而不是 Atlas Local。

### 停止和清理

```bash
docker stop agents-flex-mongodb-atlas
docker rm agents-flex-mongodb-atlas
```

## 配置 Store

```java
MongoDBAtlasVectorStoreConfig config =
    new MongoDBAtlasVectorStoreConfig();

config.setConnectionString(
    "mongodb://127.0.0.1:27018/?directConnection=true"
);
config.setDatabaseName("agents_flex");
config.setDefaultCollectionName("knowledge");
config.setVectorIndexName("knowledge_vector_index");
config.setVectorDimension(1536);
config.setSimilarity(MongoDBAtlasSimilarity.COSINE);
config.setFilterFields(Arrays.asList(
    "tenant",
    "category",
    "status",
    "year"
));

MongoDBAtlasVectorStore store =
    new MongoDBAtlasVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

### filterFields 为什么必须配置

Atlas Vector Search 的预过滤字段必须出现在 Vector Search Index 定义中。以下查询：

```java
query.eq("tenant", "tenant-a")
    .between("year", 2024, 2026);
```

要求索引定义中包含 `metadataMap.tenant` 和 `metadataMap.year` 两个 `filter` 字段。配置可以写简写字段名，Store
会自动补充 `metadataMap.` 前缀。未进入索引定义的字段仍可用于 `withVector(false)` 普通 MongoDB 查询，但不能
用作 `$vectorSearch` 预过滤。

生产环境建议显式维护过滤字段列表，不要根据用户输入动态重建索引。

## 连接 MongoDB Atlas

Atlas 云连接串通常使用 SRV：

```java
config.setConnectionString(
    System.getenv("MONGODB_ATLAS_URI")
);
```

环境变量示例：

```text
mongodb+srv://app-user:password@cluster.example.mongodb.net/?retryWrites=true&w=majority
```

数据库用户至少需要目标数据库的读写权限。启用自动创建索引时还需要管理 Search Index 的权限；如果生产权限不允许
应用创建索引，应提前在 Atlas 控制台或 IaC 中创建索引，并设置：

```java
config.setAutoCreateVectorIndex(false);
```

密码应通过环境变量或密钥服务提供，不要写入源码和日志。

## 快速开始

```java
Document document = Document.of(
    "MongoDB Atlas 支持 BSON 条件和向量语义检索。"
);
document.setId("mongo-001");
document.setTitle("MongoDB Atlas Store");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

StoreResult stored = store.store(document);
if (!stored.isSuccess()) {
    throw new IllegalStateException(
        stored.getMessage(), stored.getException()
    );
}

SearchWrapper query = new SearchWrapper()
    .text("MongoDB 中的向量搜索")
    .eq("tenant", "demo")
    .maxResults(5)
    .minScore(0.6);

List<Document> results = store.search(query);
```

首次写入时，Store 创建 Collection 和 Vector Search Index，并等待索引变为可查询状态。配置了
`EmbeddingModel` 时，`DocumentStore` 会为没有向量的文档和查询文本自动生成向量。

## 文档结构

模块使用稳定 BSON schema：

```javascript
{
  _id: "mongo-001",
  id: "mongo-001",
  title: "MongoDB Atlas Store",
  content: "...",
  vector: [0.12, -0.31, ...],
  metadataMap: {
    tenant: "demo",
    category: "guide",
    year: 2026
  }
}
```

文档 ID 统一按字符串存储，保证 String、数字等调用方 ID 在更新和删除时使用同一规则。metadata 应使用 MongoDB
Driver 可以编码的 BSON 类型，例如字符串、数字、布尔值、日期、列表和嵌套对象。

## 高级查询

### 链式条件

```java
SearchWrapper query = new SearchWrapper()
    .text("查找技术文档")
    .eq("tenant", "tenant-a")
    .in("category", Arrays.asList("guide", "reference"))
    .between("year", 2024, 2026)
    .isNotNull("owner")
    .not(group -> group.eq("status", "deleted"))
    .maxResults(20);
```

### SQL 风格条件

```java
SearchWrapper query = new SearchWrapper()
    .text("Atlas Vector Search")
    .condition(
        "tenant = 'tenant-a' " +
        "AND year BETWEEN 2024 AND 2026 " +
        "AND category NOT IN ('internal', 'deleted')"
    );
```

`SearchWrapper` 先把字符串解析为通用条件树，MongoDB Atlas Store 再转换为 BSON。向量查询时 BSON 放在
`$vectorSearch.filter` 中进行预过滤；不是在 Java 结果中二次筛选。

| 条件 | MongoDB BSON |
| --- | --- |
| EQ / NE | `$eq` / `$ne` |
| GT / GE / LT / LE | `$gt` / `$gte` / `$lt` / `$lte` |
| BETWEEN | 同一字段的 `$gte` 与 `$lte` |
| IN / NOT IN | `$in` / `$nin` |
| IS NULL / IS NOT NULL | 与 `null` 比较 |
| AND / OR / NOT / Group | `$and`、`$or` 和取反后的运算符组合 |

### 纯条件查询

不需要向量召回时可以使用普通 MongoDB 查询：

```java
SearchWrapper query = new SearchWrapper()
    .withVector(false)
    .condition("status = 'ready' AND year >= 2025")
    .maxResults(100);
```

纯条件查询不要求 Vector Search Index，也不要求字段出现在 `filterFields` 中。该模式不计算相似度，结果
`score` 为 `null`，`minScore` 不生效。

## 返回字段控制

默认返回正文和全部 metadata，但不返回原始向量：

```java
query.outputFields("category", "year")
    .outputVector(false);
```

Store 始终保留 `id`、`title` 和 `content`。需要向量时显式开启：

```java
query.outputVector(true);
```

## 多 Collection 与多索引

`collectionName` 选择 MongoDB Collection，`indexName` 选择该 Collection 中的 Vector Search Index：

```java
StoreOptions options = StoreOptions.ofCollectionName(
    "tenant_a_documents"
);
options.setIndexName("tenant_a_vector_index");

store.store(documents, options);
store.search(query, options);
store.update(documents, options);
store.delete(ids, options);
```

四类操作必须使用相同的 Collection。切换 `indexName` 适合蓝绿索引或不同向量配置，但索引的向量字段、维度和
过滤字段必须与 Store 配置及数据一致。

## 索引同步与一致性

MongoDB 写入先进入 `mongod`，Atlas Search 的 `mongot` 随后异步建立向量和过滤索引。默认情况下
`waitForSearchIndexing=true`，Store 会在写入、更新或删除返回前执行真实 `$vectorSearch` 探测，直到：

- 新文档可以按 ID 和已配置过滤字段检索；
- 更新后的正文、向量和 metadata 均已生效；
- 删除文档不再出现在向量索引中。

这提供了更符合统一 Store API 的立即可查行为，但会增加写入延迟。吞吐优先且业务允许最终一致时可以关闭：

```java
config.setWaitForSearchIndexing(false);
```

关闭后，应用必须接受短时间内向量查询读到旧数据。

## 测试与验证

条件单元测试：

```bash
mvn -pl agents-flex-store/agents-flex-store-mongodb-atlas -am \
  -Dtest=MongoDBAtlasConditionBuilderTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

启动 Atlas Local 后执行真实集成测试：

```bash
mvn -pl agents-flex-store/agents-flex-store-mongodb-atlas -am \
  -Dtest=MongoDBAtlasConditionBuilderTest,MongoDBAtlasVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.mongodb-atlas.integration=true \
  -Dagentsflex.mongodb-atlas.uri='mongodb://127.0.0.1:27018/?directConnection=true' \
  test
```

真实测试创建随机数据库和真实 Vector Search Index，覆盖多集合隔离、复杂预过滤、SQL 条件、纯过滤查询、
输出字段、更新、删除、索引同步以及关闭自动建索引，结束后自动删除测试数据库。

## 生产建议

- 使用 Atlas 控制台、Terraform 或其他 IaC 显式管理生产 Vector Search Index；
- 固定 MongoDB Driver 和 Atlas 兼容版本；
- 将 tenant、权限、状态等服务端强制条件加入 `filterFields`；
- 对用户提供的 SQL 风格条件实施字段白名单、长度和 IN 数量限制；
- 用生产数据校准 `numCandidatesMultiplier`、`maxResults` 和 `minScore`；
- 监控 Search Index 状态、索引同步延迟、查询 P95/P99、存储和计算费用；
- Embedding 模型改变维度时创建新 Collection 或新索引并完成数据重建；
- 使用应用级单例复用 MongoClient，并在应用关闭时调用 `close()`。

## 常见问题

### 普通 MongoDB Docker 报不认识 $vectorSearch

普通 MongoDB Community 镜像不包含 Atlas Search 的 `mongot`。本地开发使用官方
`mongodb/mongodb-atlas-local` 镜像，生产使用 MongoDB Atlas。

### 过滤字段未被索引

把字段加入 `filterFields` 后需要重建或更新 Vector Search Index。仅修改 Java 配置不会自动改变已经存在的同名索引。

### 写入成功但向量查询暂时查不到

确认没有关闭 `waitForSearchIndexing`，并检查 `indexReadyTimeoutMillis` 是否足够。Atlas Search 索引同步本身是异步的。

### 向量维度不匹配

同一 Vector Search Index 的 `numDimensions` 固定。确认 Embedding 模型、`vectorDimension` 和已有索引定义一致；
更换模型时应创建新索引或新 Collection。

</div>
