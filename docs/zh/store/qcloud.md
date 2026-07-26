<div v-pre>

# QCloudVectorStore（腾讯云向量数据库）

## 概述

`QCloudVectorStore` 是腾讯云向量数据库 Tencent Cloud VectorDB 的文档向量存储实现。模块使用腾讯云官方
Java SDK `vectordatabase-sdk-java`，不再由 Agents-Flex 手工拼装 HTTP 请求。

它提供以下能力：

- 文档 upsert、更新和按 ID 删除；
- 向量相似度搜索；
- 不携带向量的纯条件查询；
- `SearchWrapper` 条件树和 SQL 风格条件表达式；
- `outputFields`、`outputVector`、`minScore`；
- 每次操作通过 `StoreOptions` 选择 Collection；
- `Document` 的 id、vector、score、content、title 和 metadata 往返；
- SDK 错误码、消息和 requestId 检查。

腾讯云向量数据库是托管服务，服务端不能安装到本地 Docker。开发机需要通过实例的私网或公网访问地址连接云端
测试实例。

## 使用场景

适合以下情况：

- 业务基础设施已经部署在腾讯云；
- 希望使用托管向量数据库，避免维护索引节点；
- 需要通过标量过滤实现租户、状态、分类或时间范围约束；
- 同一个 Database 下维护多个知识库 Collection；
- 需要在 Java 应用中直接使用腾讯云官方 SDK。

如果应用必须完全离线运行，或开发环境不能访问腾讯云实例，应选择支持本地部署的 Milvus、Qdrant、
Elasticsearch 或 PostgreSQL + pgvector。

## 开通与连接准备

1. 在腾讯云控制台创建 VectorDB 实例；
2. 获取实例访问地址和网络端口；
3. 开启开发机可访问的网络，并配置安全组或公网白名单；
4. 创建 API Key，确认使用账户，默认账户通常为 `root`；
5. 创建 Database 和 Collection；
6. 确认 Collection 向量维度与 Embedding 模型一致。

Cluster 名称不是连接地址。SDK 的 `ConnectParam.withUrl(...)` 需要完整地址，例如：

```text
http://10.0.0.8:80
https://public-endpoint.example.com:443
```

具体接入方式参见[腾讯云 Java SDK Demo](https://cloud.tencent.com/document/product/1709/100555)。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-qcloud</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

模块内部使用：

```xml
<dependency>
    <groupId>com.tencent.tcvectordb</groupId>
    <artifactId>vectordatabase-sdk-java</artifactId>
    <version>2.5.5</version>
</dependency>
```

SDK 传递的 `log4j-slf4j-impl` 已被排除，宿主应用可以自行选择 Logback、Log4j 2 或其他 SLF4J 实现。

## Collection 与索引

腾讯云 VectorDB 支持动态 Schema。`id` 和 `vector` 是必须建立的索引；普通 metadata、content 和 title 可以
直接写入。只有需要参与 filter 的业务字段才应建立 `FilterIndex`。

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `id` | `string` primary key | `Document.id` |
| `vector` | `vector` | `Document.vector` |
| `__agentsflex_content` | 动态 string 字段 | `Document.content` |
| `__agentsflex_title` | 动态 string 字段 | `Document.title` |

例如，使用腾讯云 SDK 创建 Collection：

```java
CreateCollectionParam collection = CreateCollectionParam.newBuilder()
    .withName("knowledge")
    .withShardNum(1)
    .withReplicaNum(1)
    .addField(new FilterIndex("id", FieldType.String, IndexType.PRIMARY_KEY))
    .addField(new VectorIndex(
        "vector", 1536, IndexType.HNSW,
        MetricType.COSINE, new HNSWParams(16, 200)))
    .addField(new FilterIndex("tenant", FieldType.String, IndexType.FILTER))
    .addField(new FilterIndex("status", FieldType.String, IndexType.FILTER))
    .build();
```

免费实例通常要求 `replicaNum` 为 `0`，其他规格按实例限制配置。业务 metadata 不得使用
`__agentsflex_content` 和 `__agentsflex_title`。除非业务确实需要直接过滤正文或标题，否则不要为这两个保留字段
建立 FilterIndex，以免浪费索引内存。

## 配置 Store

```java
QCloudVectorStoreConfig config = new QCloudVectorStoreConfig();
config.setHost(System.getenv("QCLOUD_VECTOR_HOST"));
config.setAccount(System.getenv().getOrDefault("QCLOUD_VECTOR_ACCOUNT", "root"));
config.setApiKey(System.getenv("QCLOUD_VECTOR_API_KEY"));
config.setDatabase("knowledge_db");
config.setDefaultCollectionName("knowledge");
config.setTimeout(10);
config.setConnectTimeout(10);
config.setMaxIdleConnections(10);

if (!config.checkAvailable()) {
    throw new IllegalStateException("腾讯云 VectorDB 配置不完整");
}

QCloudVectorStore store = new QCloudVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

`host` 必须包含协议和端口。`timeout` 与 `connectTimeout` 的单位均为秒。

## 写入、更新和删除

```java
Document document = Document.of("腾讯云 VectorDB 使用说明");
document.setId("guide-001");
document.setTitle("VectorDB 指南");
document.putMetadata("tenant", "tenant-a");
document.putMetadata("status", "published");

StoreResult stored = store.store(document);
if (!stored.isSuccess()) {
    throw new IllegalStateException(stored.getMessage(), stored.getException());
}

document.setContent("更新后的正文");
StoreResult updated = store.update(document);

StoreResult deleted = store.delete(document.getId());
```

`store(...)` 要求文档具有 ID 和向量。调用公共 `DocumentStore.store(...)` 时，如果已经设置 Embedding 模型，
框架可以先根据正文生成向量。`update(...)` 可以只更新 metadata/content/title，也可以携带新向量。

SDK 返回 `code != 0`、响应为空或 upsert/update 的受影响数量不符合预期时，`StoreResult` 会失败；消息中会尽可能
保留 requestId。

## 向量查询

```java
SearchWrapper query = new SearchWrapper()
    .text("腾讯云向量检索")
    .maxResults(10)
    .minScore(0.75)
    .eq("tenant", "tenant-a")
    .nin("status", Arrays.asList("deleted", "blocked"))
    .outputFields("tenant", "status")
    .outputVector(true);

List<Document> documents = store.search(query);
```

映射关系：

| SearchWrapper | 腾讯云 SDK |
| --- | --- |
| `vector` | `SearchByVectorParam.vectors` |
| `maxResults` | `limit` |
| `condition` | `filter` |
| `outputFields` | `outputFields` |
| `outputVector` | `retrieveVector` |
| `minScore` | SDK 返回后在 Store 中过滤 |

VectorDB 返回的原始 `score` 会保存到 `Document.score`。不同 Collection 的 metric 可能具有不同分值含义，
设置 `minScore` 前应根据实际索引类型验证阈值。

## 纯条件查询

不需要相似度排序时，关闭向量查询：

```java
SearchWrapper query = new SearchWrapper()
    .withVector(false)
    .condition("tenant = 'tenant-a' AND status IN ('draft', 'published')")
    .maxResults(100)
    .outputVector(false);

List<Document> documents = store.search(query);
```

此时 Store 使用 SDK 的 `QueryParam`，不会发送伪造向量。纯条件查询结果通常没有相似度分值，因此
`Document.score` 可以为 `null`。

## 条件表达式

腾讯云适配器支持：

- `=`, `!=`, `>`, `>=`, `<`, `<=`；
- `IN`, `NOT IN`；
- `BETWEEN`，转换为 `>=` 和 `<=`；
- `AND`, `OR`, `NOT` 与括号；
- 数字、布尔值和字符串字面量；
- `profile.level` 形式的 JSON 路径字段。

```java
SearchWrapper query = new SearchWrapper()
    .condition("tenant = 'tenant-a' "
        + "AND (status NOT IN ('deleted', 'blocked') OR year BETWEEN 2024 AND 2026)");
```

字符串先由 `ConditionExpressionParser` 解析为通用条件树，然后
`QCloudExpressionAdaptor` 再转换为腾讯云 filter 语法。业务代码只需要使用 `SearchWrapper`，无需直接调用解析器或
适配器。

## Metadata 类型

官方 SDK 支持的字段值包括：

- `String`；
- `Integer`、`Long`、`Float`、`Double`；
- 只包含字符串的 `List`；
- `JSONObject`；
- `Map`，Store 会转换为 `JSONObject`；
- `Byte`、`Short`、`BigInteger`、`BigDecimal`，Store 会做带范围检查的转换。

`null`、Boolean、非字符串数组元素及其他任意 Java 对象会明确失败，避免 SDK 在序列化阶段产生难以定位的错误。
已经建立索引的字段，其值必须与索引声明的字段类型一致。

## 多 Collection

```java
StoreOptions tenantA = StoreOptions.ofCollectionName("tenant_a_knowledge");
StoreOptions tenantB = StoreOptions.ofCollectionName("tenant_b_knowledge");

store.store(documentA, tenantA);
store.store(documentB, tenantB);
store.search(query, tenantA);
```

每次调用都会把解析出的 Collection 名直接传给 SDK，不共享或复用上一次调用的 Collection 状态。Database 固定
来自 Config；跨 Database 应创建不同的 Store 实例。

腾讯云 VectorDB 不支持 `StoreOptions.partitionName(...)`，传入 Partition 会明确失败。

## 注入官方 Client

```java
ConnectParam connect = ConnectParam.newBuilder()
    .withUrl(host)
    .withUsername("root")
    .withKey(apiKey)
    .build();

VectorDBClient client = new VectorDBClient(
    connect, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);
QCloudVectorStore store = new QCloudVectorStore(config, client);
```

关闭 Store 时会同时关闭注入的 Client：

```java
try (QCloudVectorStore store = new QCloudVectorStore(config)) {
    // CRUD 与查询
}
```

## 真实集成测试

集成测试会创建一个唯一 Database 和两个唯一 Collection，验证写入、SQL 风格条件、IN/NOT IN/BETWEEN、更新、
删除及 Collection 隔离，最后删除整个测试 Database。

```bash
export QCLOUD_VECTOR_INTEGRATION_TEST=true
export QCLOUD_VECTOR_HOST="https://your-public-endpoint:port"
export QCLOUD_VECTOR_ACCOUNT="root"
export QCLOUD_VECTOR_API_KEY="your-api-key"
export QCLOUD_VECTOR_DIMENSION="4"
export QCLOUD_VECTOR_REPLICA_NUM="0"

mvn -pl agents-flex-store/agents-flex-store-qcloud -am \
  -Dtest=QCloudVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

未设置 `QCLOUD_VECTOR_INTEGRATION_TEST=true` 时，真实云测试自动跳过。测试前应确认公网白名单允许当前开发机访问，
并确认测试账号具有创建和删除 Database/Collection 的权限。

## 生产建议

- 优先使用私网连接和最小权限 API Key；
- 不要在源码、测试资源或日志中保存 API Key；
- 为租户、状态等常用过滤字段建立 filter index；
- 让 Collection schema、Embedding 维度和应用版本同步发布；
- 对网络失败使用有界重试，写入使用稳定 ID；
- 监控 SDK 错误码、requestId、延迟、限流和配额；
- 应用关闭时关闭 Store，释放 SDK 连接池。

</div>
