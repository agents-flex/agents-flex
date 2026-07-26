<div v-pre>

# WeaviateVectorStore

## 概述

`WeaviateVectorStore` 使用 Weaviate 官方 Java Client，将 Agents-Flex `Document` 写入 Weaviate Collection，
并通过 HNSW 索引执行向量检索。`SearchWrapper` 条件会转换为原生 `WhereFilter`，由 Weaviate 在召回阶段执行，
不会先取 topK 再在 Java 内存中过滤。

当前模块支持：

- 写入、覆盖更新和按业务 ID 删除；
- 自动创建 Collection、HNSW 配置和 metadata 属性 schema；
- cosine、dot、l2-squared、manhattan 和 hamming 距离；
- EQ、NE、范围、BETWEEN、IN、NOT IN、NULL 和嵌套 AND/OR/NOT；
- SQL 风格条件表达式与 `withVector(false)` 纯过滤查询；
- `maxResults`、`minScore`、`outputFields` 和 `outputVector`；
- 通过 `StoreOptions.collectionName` 路由多个 Collection；
- 任意业务 ID 到稳定 UUID 的映射，并在结果中保留原始 ID。

模块默认使用调用方提供的向量，Collection 的 vectorizer 设置为 `none`。Embedding 仍由 Agents-Flex 的
EmbeddingModel 或业务代码负责，不要求 Weaviate 安装外部模型模块。

## 使用场景

Weaviate 适合以下场景：

- 希望使用专用开源向量数据库，而不是在关系数据库或搜索引擎上增加向量能力；
- RAG 文档需要向量召回与 tenant、分类、状态、数值等条件组合；
- 希望使用 Weaviate 的 HNSW、GraphQL、混合检索和集群扩展生态；
- 需要自建 Docker/Kubernetes，也可能在生产环境切换到 Weaviate Cloud；
- 希望通过独立 Collection 隔离知识库、租户或 Embedding 模型版本。

如果只需要单机嵌入式存储，Weaviate 的独立服务和运维成本可能偏高。已有 PostgreSQL、Elasticsearch 或
MongoDB Atlas 的团队，也应比较复用现有系统与新增 Weaviate 集群的整体成本。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-weaviate</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

模块使用 `io.weaviate:client` 官方 Java Client，不通过自定义 HTTP 字符串拼接实现查询。

## 本地安装

### 启动 Weaviate

下面使用固定版本并映射到本机 `8082`，避免与其他服务占用 `8080`：

```bash
docker run -d --name agents-flex-weaviate \
  -p 127.0.0.1:8082:8080 \
  -p 127.0.0.1:50053:50051 \
  -e QUERY_DEFAULTS_LIMIT=25 \
  -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
  -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
  -e DEFAULT_VECTORIZER_MODULE=none \
  -e AUTOSCHEMA_ENABLED=false \
  -e CLUSTER_HOSTNAME=agents-flex-node \
  cr.weaviate.io/semitechnologies/weaviate:1.38.6
```

这里特意关闭 auto-schema。模块会显式创建核心属性和 metadata 属性，因此测试不依赖服务端隐式猜测类型。
`DEFAULT_VECTORIZER_MODULE=none` 表示直接使用 Agents-Flex 写入的向量。

验证服务：

```bash
curl http://127.0.0.1:8082/v1/.well-known/ready
curl http://127.0.0.1:8082/v1/meta
curl http://127.0.0.1:8082/v1/schema
```

停止并清理：

```bash
docker stop agents-flex-weaviate
docker rm agents-flex-weaviate
```

生产环境应挂载持久化卷、启用认证和 TLS，并固定经过验证的 Weaviate 版本。

## 连接 Weaviate Cloud

```java
WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
config.setServerUrl("https://your-cluster.weaviate.network");
config.setApiKey(System.getenv("WEAVIATE_API_KEY"));
config.setDefaultCollectionName("KnowledgeDocuments");

WeaviateVectorStore store = new WeaviateVectorStore(config);
```

不要把 API Key 写入源码或提交到仓库。生产网络还应配置出口策略、超时和最小权限。

## 快速开始

### 创建 Store

```java
WeaviateVectorStoreConfig config = new WeaviateVectorStoreConfig();
config.setServerUrl("http://127.0.0.1:8082");
config.setDefaultCollectionName("KnowledgeDocuments");
config.setVectorDimension(1536);
config.setSimilarity(WeaviateSimilarity.COSINE);

WeaviateVectorStore store = new WeaviateVectorStore(config);
```

Collection 名称必须以大写字母开头，并且只能包含字母、数字和下划线。例如 `KnowledgeDocuments` 合法，
`knowledge-documents` 不合法。

### 写入文档

```java
Document document = Document.of("Weaviate 是开源向量数据库。");
document.setId("article-1001");
document.setTitle("Weaviate 入门");
document.setVector(embedding);
document.putMetadata("tenant", "team-a");
document.putMetadata("category", "database");
document.putMetadata("views", 10);

StoreResult result = store.store(Collections.singletonList(document));
```

Weaviate 对对象 ID 要求使用 UUID。模块会根据“Collection + 业务 ID”生成稳定 UUID，并将原始值保存为
`agentsFlexId`。两个 Collection 可以安全使用相同业务 ID。

### 向量检索

```java
SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team-a")
    .in("category", Arrays.asList("database", "search"))
    .maxResults(5)
    .minScore(0.7)
    .outputFields("category", "views");
search.setVector(queryEmbedding);

List<Document> documents = store.search(search);
```

条件字段可以写 `category` 或 `metadataMap.category`，两者都会映射到 `metadata_category` 属性。

## Collection 与 schema

### 自动创建 Collection

默认 `autoCreateCollection=true`。首次写入或查询时，模块创建：

- vectorizer：`none`；
- vector index type：`hnsw`；
- distance：由 `WeaviateSimilarity` 决定；
- inverted index：启用 `indexNullState`；
- 核心属性：`agentsFlexId`、`title`、`content`。

Collection 由运维平台预建时可以关闭自动创建：

```java
config.setAutoCreateCollection(false);
```

已有 Collection 的 vectorizer、距离度量和 null 索引应由部署流程保证，模块不会修改已有索引配置。

### metadata 类型推断

默认 `autoCreateMetadataProperties=true`。写入新字段时，模块按 Java 值创建属性：

| Java 值 | Weaviate 类型 |
| --- | --- |
| `String`、`Character`、`Enum` | `text` |
| `byte`、`short`、`int` | `int` |
| 其他 `Number` | `number` |
| `Boolean` | `boolean` |
| `Date` | `date` |
| 对应数组或 Collection | `text[]`、`int[]`、`number[]` 等 |

metadata key 当前只支持字母、数字和下划线，不支持嵌套 key。字段创建后，后续值必须保持相同类型。

### 显式声明 metadata 类型

字段首次出现为空数组、暂时全部为 null、需要固定生产 schema，或者需要执行 `IS NULL` 时，建议显式声明：

```java
Map<String, WeaviateMetadataType> types = new LinkedHashMap<>();
types.put("tenant", WeaviateMetadataType.TEXT);
types.put("views", WeaviateMetadataType.INT);
types.put("tags", WeaviateMetadataType.TEXT_ARRAY);
types.put("optional", WeaviateMetadataType.TEXT);
config.setMetadataFieldTypes(types);
```

完全禁止运行时新增属性：

```java
config.setAutoCreateMetadataProperties(false);
```

## 高级查询

### 范围、IN 与 NOT

```java
SearchWrapper search = new SearchWrapper()
    .in("category", Arrays.asList("AI", "database"))
    .between("views", 10, 100)
    .isNotNull("tenant")
    .not(group -> group.eq("status", "deleted"));
search.setVector(queryEmbedding);
```

`IN` 转换成多个 `Equal` 的 OR，`NOT IN` 转换成多个 `NotEqual` 的 AND，`BETWEEN` 转换成上下界条件。
全部条件均由 Weaviate 服务端执行。

### SQL 风格条件

```java
SearchWrapper search = new SearchWrapper()
    .condition("views >= 20 AND category NOT IN ('internal', 'deleted')")
    .maxResults(10);
search.setVector(queryEmbedding);
```

字符串先由 `SearchWrapper` 解析为通用条件树，Weaviate Store 再读取真实 Collection schema，将条件转换成正确
类型的 `WhereFilter`。例如 `int` 属性使用 `valueInt`，`number` 属性使用 `valueNumber`。

### 纯属性过滤

```java
SearchWrapper filterOnly = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "team-a")
    .maxResults(100);

List<Document> documents = store.search(filterOnly);
```

纯过滤查询不需要查询向量，也不会执行 nearVector。

### 返回字段与向量

```java
SearchWrapper search = new SearchWrapper()
    .outputFields("category", "views")
    .outputVector(true)
    .maxResults(5);
search.setVector(queryEmbedding);
```

`outputFields` 为空时返回全部已定义 metadata；指定后只返回列出的 metadata。ID、标题和正文始终返回，向量仅在
`outputVector(true)` 时返回。

## 相似度与 minScore

Weaviate 原生返回 distance，Agents-Flex 返回越大越相似的 score：

- cosine 使用 Weaviate certainty；
- dot 使用基于 dot distance 的单调归一化分值；
- l2-squared、manhattan 和 hamming 使用 `1 / (1 + distance)`。

`minScore` 会转换为 nearVector 的最大 distance，在服务端限制结果。不同数据库、距离和模型的 score 不应直接
比较，阈值必须使用真实业务数据校准。

## 多 Collection

```java
StoreOptions productOptions = StoreOptions.ofCollectionName("ProductKnowledge");
StoreOptions supportOptions = StoreOptions.ofCollectionName("SupportKnowledge");

store.store(productDocuments, productOptions);
store.store(supportDocuments, supportOptions);
List<Document> products = store.search(productSearch, productOptions);
```

更换 Embedding 模型、维度或距离度量时，应创建新 Collection、重新计算向量并验证召回后再切换流量。

## 真实集成测试

启动 Docker 后执行：

```bash
mvn -pl agents-flex-store/agents-flex-store-weaviate -am \
  -Dtest=WeaviateConditionBuilderTest,WeaviateVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.weaviate.integration=true \
  -Dagentsflex.weaviate.url=http://127.0.0.1:8082 \
  test
```

测试会真实验证 auto-schema 关闭后的 Collection/schema 创建、HNSW 配置、多 Collection 隔离、nearVector、
复杂条件、SQL 条件、纯过滤、字段投影、向量输出、空数组、NULL、更新、删除和禁用自动创建。测试使用随机
Collection 名称，并在结束后删除测试数据。

## 生产建议

1. 固定 Weaviate 服务端和 Java Client 版本，并验证升级兼容性；
2. 使用生产 Embedding 模型固定维度与距离度量；
3. 通过 `metadataFieldTypes` 固定经常过滤、允许为空或数组类型的字段；
4. 多租户权限条件必须由服务端代码强制追加，敏感场景优先独立 Collection；
5. 配置持久化卷、认证、TLS、备份、资源限制和监控；
6. 使用真实数据校准 HNSW、topK、过滤后召回率和 `minScore`；
7. 模型升级使用新 Collection 做蓝绿切换，不要混写不同向量空间。

## 常见问题

### 为什么 Collection 名称不能使用小写开头？

当前官方 Java Client 的 schema API 沿用 Weaviate Class 命名规则，名称必须以大写字母开头。模块会在请求前校验。

### 为什么空数组需要声明类型？

空数组无法判断应创建 `text[]` 还是 `number[]`。通过 `metadataFieldTypes` 声明后可以正常写入和返回。

### 普通业务 ID 会变成什么？

Weaviate 内部使用由 Collection 和业务 ID 确定生成的 UUID，查询结果仍返回原始业务 ID。

### 为什么修改 similarity 后已有 Collection 没变化？

距离度量属于 Collection 索引配置。模块只在新建 Collection 时应用配置，需要新建 Collection 并迁移数据。

</div>
