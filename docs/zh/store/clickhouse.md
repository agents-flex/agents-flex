<div v-pre>

# ClickHouseVectorStore

## 概述

`ClickHouseVectorStore` 为 Agents-Flex 提供基于 ClickHouse 的文档向量存储能力。每个 Collection 映射为一张
`MergeTree` 表，向量保存为 `Array(Float32)`，业务 metadata 保存为 JSON 字符串。向量检索使用 ClickHouse
原生距离函数；在 ClickHouse 25.8+ 上，余弦和 L2 模式还可以自动创建 `vector_similarity` HNSW 索引。

当前支持：

- 自动创建 database、Collection 表和向量维度约束；
- cosine、L2 和 dot product 检索；
- ClickHouse 25.8+ 的 HNSW 向量相似度索引；
- 写入、覆盖更新、按 ID 删除和纯 metadata 查询；
- EQ、NE、范围、BETWEEN、IN、NOT IN、NULL 和嵌套 AND/OR/NOT；
- SQL 风格条件、`maxResults`、`minScore`、`outputFields` 和 `outputVector`；
- 通过 `StoreOptions.collectionName` 隔离多张 Collection 表；
- Java 8 项目基线和官方 ClickHouse JDBC 驱动。

## 使用场景

ClickHouse 适合已经使用它承载日志、事件、分析数据，希望在同一平台增加向量召回和结构化过滤的团队。典型场景
包括大批量知识数据导入、离线或准实时语义分析、事件相似性检索，以及需要把向量结果与 ClickHouse 分析能力结合
的系统。

ClickHouse 是面向分析型、追加写入工作负载的列式数据库，不是高频行级更新数据库。本模块为了维持业务 ID 唯一，
覆盖写入需要执行同步 DELETE mutation 后再 INSERT。文档频繁变更、要求多行事务或极低延迟在线 upsert 时，应优先
评估专用向量数据库、Pgvector 或其他更符合更新模型的后端。

## 版本要求

| 组件 | 要求 |
| --- | --- |
| ClickHouse Server | 25.8+，用于 `vector_similarity` HNSW 索引 |
| Java | 8+ |
| JDBC | 模块内置官方 `clickhouse-jdbc` 0.8.6 |

较旧 ClickHouse 版本可能可以执行精确距离函数，但不能保证支持当前索引 DDL、查询设置和 metadata JSON 语义。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-clickhouse</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 本地安装

### Docker 启动

本模块真实集成测试使用 ClickHouse 25.8 官方镜像，并固定到以下 digest：

```bash
docker run -d --name agents-flex-clickhouse \
  -p 127.0.0.1:8124:8123 \
  -p 127.0.0.1:9002:9000 \
  -e CLICKHOUSE_DB=agents_flex \
  -e CLICKHOUSE_USER=agentsflex \
  -e CLICKHOUSE_PASSWORD=agentsflex \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
  -v agents-flex-clickhouse-data:/var/lib/clickhouse \
  clickhouse/clickhouse-server@sha256:a9d328123ff8a61bf6b16448528b577d59deb85758172e13b09054b0727f8adf
```

这里把 HTTP/JDBC 端口映射为 `8124`，原生协议端口映射为 `9002`，避免与开发机已有服务冲突。本模块通过 HTTP
JDBC 连接，只需要开放 `8124`。

### 验证服务

```bash
curl -u agentsflex:agentsflex \
  'http://127.0.0.1:8124/?query=SELECT%20version()'
```

真实测试环境返回 `25.8.28.1`。也可以查看容器状态和日志：

```bash
docker ps --filter name=agents-flex-clickhouse
docker logs --tail 100 agents-flex-clickhouse
```

### 停止和清理

```bash
docker stop agents-flex-clickhouse
docker rm agents-flex-clickhouse
docker volume rm agents-flex-clickhouse-data
```

删除 volume 会永久删除 ClickHouse 数据，需要保留本地数据时不要执行最后一条命令。

## 快速开始

### 创建 Store

```java
ClickHouseVectorStoreConfig config = new ClickHouseVectorStoreConfig();
config.setHost("127.0.0.1");
config.setPort(8124);
config.setUsername("agentsflex");
config.setPassword("agentsflex");
config.setDatabaseName("agents_flex");
config.setDefaultCollectionName("knowledge_documents");
config.setVectorDimension(1536);
config.setSimilarity(ClickHouseSimilarity.COSINE);

ClickHouseVectorStore store = new ClickHouseVectorStore(config);
```

database 和 Collection 名称必须以字母开头，只能包含字母、数字和下划线，最长 64 个字符。物理名称不能使用 JDBC
参数绑定，因此 Store 会先执行严格白名单校验。

### 写入文档

```java
Document document = Document.of("ClickHouse 是面向分析工作负载的列式数据库。");
document.setId("article_1001");
document.setTitle("ClickHouse 向量检索");
document.setVector(embedding);
document.putMetadata("tenant", "team_a");
document.putMetadata("category", "database");
document.putMetadata("views", 20);
document.putMetadata("published", true);

StoreResult result = store.store(Collections.singletonList(document));
if (!result.isSuccess()) {
    throw new IllegalStateException(result.getMessage(), result.getException());
}
```

向量不能为空，维度必须等于 `vectorDimension`，并且每一个值都必须是有限数值。

### 向量检索

```java
SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team_a")
    .in("category", Arrays.asList("database", "search"))
    .maxResults(5)
    .minScore(0.7)
    .outputFields("category", "views");
search.setVector(queryEmbedding);

List<Document> documents = store.search(search);
```

带过滤条件的向量查询会设置 `vector_search_filter_strategy = 'prefilter'`，使权限、租户和业务条件先于 ANN 排序
生效，避免先取近邻再过滤造成结果不足或过滤语义偏差。

## 表结构与自动创建

默认情况下，一个 Collection 会创建为：

```sql
CREATE TABLE agents_flex.knowledge_documents
(
    id String,
    title Nullable(String),
    content Nullable(String),
    vector Array(Float32),
    metadata String,
    CONSTRAINT vector_dimension CHECK length(vector) = 1536,
    INDEX vector_idx vector TYPE vector_similarity(
        'hnsw', 'cosineDistance', 1536, 'bf16', 32, 128
    )
)
ENGINE = MergeTree
ORDER BY id
```

Store 启动和切换 Collection 时会检查向量列类型、维度约束和已有索引的距离函数，避免把不同向量空间误用到同一
张表。可以关闭自动创建：

```java
config.setAutoCreateDatabase(false);
config.setAutoCreateCollection(false);
config.setAutoCreateVectorIndex(false);
```

关闭后应由迁移脚本预建完全一致的 schema。

## HNSW 配置

```java
config.setHnswM(32);
config.setHnswEfConstruction(128);
config.setHnswEfSearch(256);
config.setQuantization("bf16");
config.setMaxVectorSearchResults(100);
```

| 参数 | 作用 |
| --- | --- |
| `hnswM` | HNSW 图每层最大连接数，增大通常提高召回率，也增加内存和构建成本 |
| `hnswEfConstruction` | 建索引候选队列大小 |
| `hnswEfSearch` | 查询候选队列大小 |
| `quantization` | `f64`、`f32`、`f16`、`bf16`、`i8` 或 `b1` |
| `maxVectorSearchResults` | 限制单次向量查询 topK，防止超出索引适用范围 |

参数必须用真实数据比较召回率、延迟、索引大小和构建时间，不能只依赖默认值。

## 相似度与 score

| 配置 | ClickHouse 排序 | Agents-Flex score | HNSW |
| --- | --- | --- | --- |
| `COSINE` | `cosineDistance` 升序 | `1 - distance` | 支持 |
| `L2` | `L2Distance` 升序 | `1 / (1 + distance)` | 支持 |
| `DOT_PRODUCT` | `dotProduct` 降序 | 原始 dot product | ClickHouse 25.8 不支持，自动精确检索 |

`minScore` 在 topK 结果读取后应用。三种度量的分值分布不同，阈值必须针对实际 Embedding 模型分别校准。

## metadata 与高级查询

metadata 以 JSON 保存到 `metadata` 字符串列。Store 使用 `JSON_VALUE` 提取字段，并把数值比较转换为
`toFloat64OrNull(JSON_VALUE(...))`，避免 `"100" < "20"` 这样的字符串字典序错误。

### 链式条件

```java
SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team_a")
    .between("views", 10, 100)
    .in("category", Arrays.asList("AI", "database"))
    .isNotNull("owner")
    .not(group -> group.eq("status", "deleted"));
search.setVector(queryEmbedding);
```

`category`、`metadata.category` 和 `metadataMap.category` 都会映射到 metadata JSON 字段。`id`、`title` 和
`content` 映射固定表列。JSON 路径片段只允许字母、数字、下划线和连字符，条件值全部使用 JDBC 参数绑定。

### SQL 风格条件

```java
SearchWrapper search = new SearchWrapper()
    .condition(
        "tenant = 'team_a' " +
        "AND views BETWEEN 10 AND 100 " +
        "AND category NOT IN ('internal', 'deleted')"
    )
    .maxResults(10);
search.setVector(queryEmbedding);
```

`SearchWrapper` 先把字符串解析为通用条件树，`ClickHouseExpressionAdaptor` 再把条件树转换为参数化 ClickHouse
SQL。原始表达式不会直接透传到数据库。

### NULL 查询

```java
SearchWrapper search = new SearchWrapper()
    .isNull("optional")
    .isNotNull("tenant");
```

查询会启用 `function_json_value_return_type_allow_nullable = 1`，使 JSON 中不存在的路径可以参与 `IS NULL` 和
`IS NOT NULL` 判断。应区分字段缺失、JSON null 和业务空字符串，不要把空字符串当作数据库 NULL。

### 纯属性过滤

```java
SearchWrapper filterOnly = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "team_a")
    .ge("views", 20)
    .maxResults(100);

List<Document> documents = store.search(filterOnly);
```

纯过滤不需要 query vector，按 ID 排序返回，结果中的 `score` 为 null。

### 字段投影与向量返回

```java
SearchWrapper search = new SearchWrapper()
    .outputFields("category", "views")
    .outputVector(true)
    .maxResults(5);
search.setVector(queryEmbedding);
```

ID、标题和正文始终返回。`outputFields` 为 null 时返回全部 metadata；指定后只返回所列顶层 metadata 字段；向量
仅在 `outputVector(true)` 时返回。

## 多 Collection

```java
StoreOptions product = StoreOptions.ofCollectionName("product_knowledge");
StoreOptions support = StoreOptions.ofCollectionName("support_knowledge");

store.store(productDocuments, product);
store.store(supportDocuments, support);
```

每个 Collection 对应同一 database 内的独立 MergeTree 表，数据、维度约束和 HNSW 索引互不共享。真实测试验证
了相同文档 ID 在两个全新 Collection 中不会互串。更换 Embedding 模型、向量维度或距离度量时应创建新
Collection，不要复用已有表。

## 覆盖写入与删除

ClickHouse 没有适合此场景的低成本行级 upsert。`store` 和 `update` 会按以下顺序执行：

1. 校验全部文档、ID、metadata 序列化和向量维度；
2. 使用同步 `ALTER TABLE ... DELETE WHERE id IN (...) SETTINGS mutations_sync = 2` 删除旧 ID；
3. 批量 INSERT 当前文档；
4. 同一 Store 实例内按 Collection 加锁，防止本实例并发覆盖产生重复 ID。

删除和插入是两个独立请求，不构成事务。删除成功而插入失败时，旧数据已经不可见；多个应用实例之间也没有分布式
锁。高频小批量更新会产生大量 mutation，应改为较大的批次、降低更新频率，并保留可重放的权威数据源。

## 连接配置

```java
config.setRequestTimeoutMillis(30000);

Map<String, String> properties = new LinkedHashMap<>();
properties.put("compress", "1");
config.setProperties(properties);
```

额外 JDBC 属性会经过键名白名单校验和值编码。`setProperties` 会做防御性复制，调用方后续修改原 Map 不会改变
Store 配置。生产环境应按 ClickHouse 部署方式配置 TLS、最小权限用户和网络访问控制。

## 真实集成测试

```bash
mvn -pl agents-flex-store/agents-flex-store-clickhouse -am \
  -Dtest=ClickHouseVectorUtilTest,ClickHouseExpressionAdaptorTest,ClickHouseVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.clickhouse.integration=true \
  -Dagentsflex.clickhouse.host=127.0.0.1 \
  -Dagentsflex.clickhouse.port=8124 \
  -Dagentsflex.clickhouse.username=agentsflex \
  -Dagentsflex.clickhouse.password=agentsflex \
  test
```

测试在真实 ClickHouse 25.8.28.1 上验证：

- database、MergeTree 表、维度约束和 HNSW 创建；
- `EXPLAIN indexes = 1` 确认 `vector_idx` 参与查询计划；
- 多 Collection 隔离、CRUD 和同 ID 覆盖后只有一行；
- cosine、L2 和 dot product，其中 dot product 明确不创建 HNSW；
- SQL 风格复杂条件、IN、NOT IN、BETWEEN、NULL、嵌套 NOT、Boolean 和数值 metadata；
- 纯过滤、字段投影、向量输出、`minScore`、禁用自动建表和错误维度。

每个测试使用随机 database，并在结束后删除，避免依赖旧数据或污染下一次运行。

## 生产建议

1. 固定 ClickHouse Server 和 JDBC 版本，升级前运行完整真实回归；
2. 使用生产 Embedding 模型确认维度、归一化方式和距离度量；
3. 用迁移脚本显式管理 database、表、约束和索引，应用自动创建更适合开发环境；
4. 使用真实过滤比例验证 prefilter 后的召回率和延迟；
5. 控制 mutation 数量，避免把 ClickHouse 当作高频 OLTP upsert 数据库；
6. 多租户物理表名通过服务端注册表映射，不接受客户端直接指定；
7. 配置持久化、备份、TLS、最小权限、资源限制、查询监控和 mutation 监控；
8. 模型升级使用新 Collection 做全量重建和蓝绿切换；
9. DOT_PRODUCT 当前为精确排序，大数据量使用前必须单独压测；
10. 保留可重放数据源，处理删除成功但插入失败的非事务窗口。

## 参考资料

- [ClickHouse Approximate Nearest Neighbour Search Indexes](https://clickhouse.com/docs/engines/table-engines/mergetree-family/annindexes)
- [ClickHouse Java Integrations](https://clickhouse.com/docs/integrations/java)

</div>
