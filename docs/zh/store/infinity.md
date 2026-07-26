<div v-pre>

# InfinityVectorStore

## 概述

`InfinityVectorStore` 为 Agents-Flex 提供 [Infinity](https://infiniflow.org/docs) 向量数据存储支持。Infinity 是
Infiniflow 开源的 AI 原生数据库，支持稠密向量、稀疏向量、全文、Tensor 和融合检索。本模块聚焦
`Document` 的稠密向量检索与结构化 metadata 过滤。

当前支持：

- 自动创建 database、Collection 对应的 table、metadata 列和 HNSW；
- 写入、覆盖更新、按 ID 删除和纯 metadata 查询；
- cosine、inner product 和 L2 度量；
- EQ、NE、范围、BETWEEN、IN、NOT IN、NULL 和嵌套 AND/OR/NOT；
- SQL 风格条件、`maxResults`、`minScore`、`outputFields` 和 `outputVector`；
- 使用 `StoreOptions.collectionName` 隔离多个 table；
- metadata 标量类型推断和显式生产 schema。

模块使用 Infinity 官方 HTTP API。建库、DDL、写入和删除走 Agents-Flex 统一 HTTP 客户端；Infinity 查询接口
要求带 JSON body 的 GET，仅该请求使用 Apache HttpClient 5。

## 使用场景

Infinity 适合 RAG 系统同时需要向量召回、结构化过滤，并计划继续使用全文或混合检索的场景。它也适合希望部署
独立开源 AI 数据库、通过 Collection 隔离知识库或模型版本，以及需要跨语言 HTTP API 的团队。

如果团队已经稳定运行 PostgreSQL、Elasticsearch、OpenSearch 或 Cassandra，应比较复用现有平台与新增服务的
整体成本。Infinity API 仍在演进，升级前必须用实际镜像运行兼容测试。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-infinity</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 本地安装

本模块真实测试使用以下官方镜像 digest。HTTP API 端口为 `23820`，原生 SDK 端口为 `23817`：

```bash
docker run -d --name agents-flex-infinity \
  -p 127.0.0.1:23817:23817 \
  -p 127.0.0.1:23820:23820 \
  -v agents-flex-infinity-data:/var/infinity \
  --ulimit nofile=500000:500000 \
  infiniflow/infinity@sha256:c5e5aeaf7cdb8361b233d75b8f0dcb639ff14ee56358c61236ba5d6de89b8434
```

验证服务：

```bash
curl http://127.0.0.1:23820/databases
docker logs --tail 100 agents-flex-infinity
```

成功响应包含 `default_db`。官方也提供 `infiniflow/infinity:nightly`，但 nightly 会变化，生产环境和 CI 应固定
经过验证的版本或 digest。

停止和清理：

```bash
docker stop agents-flex-infinity
docker rm agents-flex-infinity
docker volume rm agents-flex-infinity-data
```

删除 volume 会永久清除数据，需要保留本地数据时不要执行最后一条命令。

## 快速开始

### 创建 Store

```java
InfinityVectorStoreConfig config = new InfinityVectorStoreConfig();
config.setServerUrl("http://127.0.0.1:23820");
config.setDatabaseName("agents_flex");
config.setDefaultCollectionName("knowledge_documents");
config.setVectorDimension(1536);
config.setSimilarity(InfinitySimilarity.COSINE);

InfinityVectorStore store = new InfinityVectorStore(config);
```

database、Collection 和 metadata 名称必须以字母开头，只能包含字母、数字和下划线，最长 64 个字符。

### 写入与检索

```java
Document document = Document.of("Infinity 是面向 AI 工作负载的开源数据库。");
document.setId("article_1001");
document.setTitle("Infinity 入门");
document.setVector(embedding);
document.putMetadata("tenant", "team_a");
document.putMetadata("category", "database");
document.putMetadata("views", 10);
store.store(Collections.singletonList(document));

SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team_a")
    .in("category", Arrays.asList("database", "search"))
    .maxResults(5)
    .minScore(0.7)
    .outputFields("category", "views");
search.setVector(queryEmbedding);

List<Document> documents = store.search(search);
```

`category`、`metadata.category` 和 `metadataMap.category` 都映射为 `metadata_category` 列。

## Schema 与 HNSW

默认配置自动创建 database、table、固定列、metadata 列和 `embedding_hnsw` 索引。可以分别关闭：

```java
config.setAutoCreateDatabase(false);
config.setAutoCreateCollection(false);
config.setAutoCreateMetadataColumns(false);
config.setAutoCreateVectorIndex(false);
```

关闭后，部署流程必须预建一致的向量维度、metadata 类型和 HNSW 度量。

### metadata 类型

| Java 值 | Infinity 类型 |
| --- | --- |
| `String`、`Character`、`Enum`、日期时间 | `varchar` |
| `byte`、`short`、`int` | `integer` |
| `long` | `bigint` |
| `float` | `float` |
| 其他 `Number` | `double` |
| `boolean` | `boolean` |

当前只支持标量 metadata，不支持数组、集合、嵌套对象和任意 JSON。字段创建后，后续值必须保持类型一致。

生产环境建议显式声明，尤其是首次值为 null 的字段：

```java
Map<String, InfinityMetadataType> schema = new LinkedHashMap<>();
schema.put("tenant", InfinityMetadataType.VARCHAR);
schema.put("views", InfinityMetadataType.INTEGER);
schema.put("optional", InfinityMetadataType.VARCHAR);
config.setMetadataFieldTypes(schema);
```

## 高级查询

### 链式条件

```java
SearchWrapper search = new SearchWrapper()
    .in("category", Arrays.asList("AI", "database"))
    .between("views", 10, 100)
    .isNotNull("tenant")
    .not(group -> group.eq("status", "deleted"));
search.setVector(queryEmbedding);
```

Infinity 原生执行比较、IN、NOT IN、AND、OR 和 NOT。真实测试中 HTTP API 的 `BETWEEN` 行为不可靠，Store 会
转换为 `field >= lower AND field <= upper`。

### SQL 风格条件

```java
SearchWrapper search = new SearchWrapper()
    .condition("views >= 20 AND category NOT IN ('internal', 'deleted')")
    .maxResults(10);
search.setVector(queryEmbedding);
```

SearchWrapper 先把字符串解析成通用条件树，Infinity Store 再完成字段白名单、metadata 映射、值转义和运算符适配，
不会把调用方的原始字符串直接透传给数据库。

### 纯属性过滤

```java
SearchWrapper filterOnly = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "team_a")
    .maxResults(100);
List<Document> documents = store.search(filterOnly);
```

纯过滤不需要 query vector，结果的 `score` 和 `vector` 为 null。

### 字段投影

```java
SearchWrapper search = new SearchWrapper()
    .outputFields("category", "views")
    .outputVector(true)
    .maxResults(5);
search.setVector(queryEmbedding);
```

ID、标题和正文始终返回。`outputFields` 为空时返回全部 metadata；指定后只返回所列 metadata；向量仅在
`outputVector(true)` 时返回。

## NULL 语义

Infinity 0.7 HTTP API 实测将空 varchar 输出为字符串 `"Null"`，而 `IS NULL` / `IS NOT NULL` 会报错。模块适配为：

- `isNull("optional")` -> `metadata_optional = 'Null'`；
- `isNotNull("optional")` -> `metadata_optional != 'Null'`；
- 查询结果中的精确字符串 `"Null"` 转回 Java null。

因此业务字段不能区分数据库空值和字面值 `"Null"`。需要保存该字面值时应在业务层换一种编码，并在 Infinity
升级后重新验证新版 HTTP API 的 null 行为。

## 相似度与 score

| 配置 | Infinity 返回 | Agents-Flex score |
| --- | --- | --- |
| `COSINE` | `_similarity` | 原始 similarity |
| `IP` | `_similarity` | 原始 similarity |
| `L2` | `_distance` | `1 / (1 + distance)` |

`minScore` 在 topK 结果上过滤。不同模型和度量的分值分布不同，阈值必须用真实数据校准。

## 多 Collection

```java
StoreOptions product = StoreOptions.ofCollectionName("product_knowledge");
StoreOptions support = StoreOptions.ofCollectionName("support_knowledge");
store.store(productDocuments, product);
store.store(supportDocuments, support);
```

Collection 映射同一 database 内的独立 table，schema、数据和 HNSW 不共享。更换向量模型、维度或度量时应创建
新 Collection。

## 覆盖写入语义

Infinity 普通 INSERT 不会可靠地按业务 ID 覆盖旧行。本模块在 store/update 时先按 ID 删除，再批量插入当前文档，
避免返回重复 ID；同一 Store 实例内还会按 Collection 串行化覆盖写入。多个应用实例之间没有分布式锁，且两个
HTTP 请求不是事务；删除成功后若插入因网络故障失败，旧行已经不可见。生产系统应保留可重放的数据源，并为
写入任务提供有限重试和幂等控制。

## 真实集成测试

```bash
mvn -pl agents-flex-store/agents-flex-store-infinity -am \
  -Dtest=InfinityExpressionAdaptorTest,InfinityVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.infinity.integration=true \
  -Dagentsflex.infinity.url=http://127.0.0.1:23820 \
  test
```

测试真实验证 database/table/HNSW 创建、多 Collection 隔离、CRUD、重复 ID 覆盖、动态 metadata 列、复杂条件、
SQL 条件、NULL、纯过滤、字段投影、向量输出、minScore、cosine 和 L2。每次使用随机 database 并自动清理。

## 生产建议

1. 固定 Infinity 镜像版本或 digest，升级前运行完整回归；
2. 显式配置向量维度、相似度和 metadata schema；
3. 评估删除后插入的非事务更新语义，保留可重放数据源；
4. 多租户物理 table 由服务端注册表映射，不接受客户端直接指定；
5. 配置持久化、备份、认证、TLS、资源限制和监控；
6. 使用真实数据校准 HNSW、topK、过滤后召回率和 minScore；
7. 对 `"Null"` 字面值建立业务约束；
8. 模型升级使用新 Collection 做蓝绿切换。

</div>
