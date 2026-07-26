<div v-pre>

# Apache Cassandra VectorStore

## 概述

`CassandraVectorStore` 基于 Apache Cassandra 5.x 原生 `vector<float, N>` 类型、Storage-Attached Indexes（SAI）
和官方 Apache Java Driver 实现。每个 Agents-Flex Collection 对应同一 keyspace 中的一张表，向量由 SAI 执行
ANN 召回，metadata 则映射为显式标量列并建立 SAI。

模块支持：

- 文档写入、原子 upsert、覆盖更新和按业务 ID 删除；
- cosine、dot product 和 euclidean 三种 Cassandra 原生相似度；
- 自动创建 keyspace、表、向量 SAI、metadata 列和标量 SAI；
- EQ、GT、GE、LT、LE、BETWEEN、AND 条件；
- 将 IN 和 OR 规划为多条 Cassandra 查询，合并后按 ID 去重并全局排序；
- SQL 风格条件表达式与 `withVector(false)` 纯过滤查询；
- `maxResults`、`minScore`、`outputFields`、`outputVector`；
- 使用 `StoreOptions.collectionName` 在一个 keyspace 内路由多张表。

模块不会使用 `ALLOW FILTERING`。Cassandra 无法正确执行的 NE、NOT IN、NULL 和 NOT 条件会立即抛出明确异常，
避免先取少量 ANN 结果再在 Java 端过滤而造成漏召回。

## 使用场景

适合：

- 已有 Cassandra 5.x 集群，希望复用多副本、高可用和水平扩展能力；
- 文档写入量大，要求节点故障时仍可持续写入和查询；
- metadata schema 相对稳定，过滤以等值和数值范围为主；
- 希望业务主数据与向量都保存在 Cassandra，而不是新增专用向量数据库；
- 不同知识库可以自然映射为独立表，并接受 Cassandra schema 管理成本。

不适合：

- 查询大量依赖 `!=`、`NOT IN`、`IS NULL` 或任意 NOT 逻辑；
- 需要复杂 JOIN、聚合、全文检索或关系事务；
- 每条文档都有完全不同、持续变化的 metadata 字段；
- 只做小规模本地原型，不愿承担 Cassandra JVM 和集群运维成本。

## 版本要求

- Apache Cassandra 5.0 或更高版本；
- Java 8 或更高版本；
- Cassandra 节点必须启用原生传输协议；
- 使用 `org.apache.cassandra:java-driver-core:4.19.3`。

Cassandra 4.x 没有本模块依赖的原生 vector 与向量 SAI，不能作为替代环境。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-cassandra</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 本地安装

### 使用 Docker 启动 Cassandra 5.0.5

```bash
docker run -d --name agents-flex-cassandra \
  -p 127.0.0.1:9043:9042 \
  -e CASSANDRA_CLUSTER_NAME=AgentsFlexTest \
  -e CASSANDRA_DC=datacenter1 \
  -e CASSANDRA_ENDPOINT_SNITCH=GossipingPropertyFileSnitch \
  -e MAX_HEAP_SIZE=1024M \
  -e HEAP_NEWSIZE=256M \
  cassandra:5.0.5
```

这里将容器的 `9042` 映射到本机 `9043`，避免占用已有 Cassandra 端口。首次启动通常需要几十秒。

检查 CQL 是否就绪：

```bash
docker exec agents-flex-cassandra \
  cqlsh -e "SELECT release_version, data_center FROM system.local;"

docker exec agents-flex-cassandra nodetool status
```

停止和清理：

```bash
docker stop agents-flex-cassandra
docker rm agents-flex-cassandra
```

生产环境不要直接照搬单节点参数，应配置持久化卷、认证、TLS、多节点 seed、NetworkTopologyStrategy、备份和监控。

## 快速开始

### 创建 Store

```java
CassandraVectorStoreConfig config = new CassandraVectorStoreConfig();
config.setContactPoint("127.0.0.1:9043");
config.setLocalDatacenter("datacenter1");
config.setKeyspace("agents_flex");
config.setDefaultCollectionName("knowledge_documents");
config.setVectorDimension(1536);
config.setSimilarity(CassandraSimilarity.COSINE);
config.setRequestTimeoutMillis(10000);

CassandraVectorStore store = new CassandraVectorStore(config);
```

`localDatacenter` 必须与 Cassandra 节点实际数据中心一致。可以通过 `system.local` 或 `nodetool status` 查看。
生产集群可以使用英文逗号配置多个接入节点，例如
`node1.example.com:9042,node2.example.com:9042,node3.example.com:9042`。

### 声明 metadata schema

生产环境建议显式声明可过滤字段类型：

```java
Map<String, CassandraMetadataType> types = new LinkedHashMap<>();
types.put("tenant", CassandraMetadataType.TEXT);
types.put("category", CassandraMetadataType.TEXT);
types.put("status", CassandraMetadataType.TEXT);
types.put("views", CassandraMetadataType.INT);
types.put("publishedAt", CassandraMetadataType.TIMESTAMP);
config.setMetadataFieldTypes(types);
```

每个字段会映射为 `metadata_tenant`、`metadata_category` 等列并建立 SAI。metadata key 必须以字母开头，只能包含
字母、数字和下划线，最长 48 个字符。

### 写入文档

```java
Document document = Document.of("Apache Cassandra 5 支持原生向量检索。");
document.setId("article-1001");
document.setTitle("Cassandra Vector 入门");
document.setVector(embedding);
document.putMetadata("tenant", "team-a");
document.putMetadata("category", "database");
document.putMetadata("views", 10);

StoreResult result = store.store(Collections.singletonList(document));
```

向量长度必须严格等于 `vectorDimension`。写入使用 Cassandra INSERT upsert；同 ID 再次写入会覆盖核心字段，并清除
本次文档中未提供的已知 metadata 列，避免旧 metadata 残留。

### 向量检索

```java
SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team-a")
    .between("views", 1, 100)
    .maxResults(10)
    .minScore(0.7)
    .outputFields("category", "views")
    .outputVector(false);
search.setVector(queryEmbedding);

List<Document> result = store.search(search);
```

过滤条件与 `ORDER BY embedding ANN OF ?` 在 Cassandra 服务端共同执行。`minScore` 在 Cassandra 返回每个分支的
topK 后移除低分结果，不改变已召回结果的相关性顺序。

## 表与索引结构

默认自动创建的结构近似如下：

```sql
CREATE TABLE agents_flex.knowledge_documents (
  id text PRIMARY KEY,
  title text,
  content text,
  embedding vector<float, 1536>,
  metadata_tenant text,
  metadata_category text,
  metadata_views int
);

CREATE CUSTOM INDEX ... ON agents_flex.knowledge_documents (embedding)
USING 'StorageAttachedIndex'
WITH OPTIONS = {'similarity_function': 'cosine'};

CREATE CUSTOM INDEX ... ON agents_flex.knowledge_documents (metadata_tenant)
USING 'StorageAttachedIndex';
```

标题和正文默认不建立标量 SAI。需要按标题或正文做结构化过滤时，应把可过滤值复制到声明的 metadata 字段；全文
检索应交给专门的搜索系统。

## metadata 类型

| Java 值 | CassandraMetadataType | CQL 类型 |
| --- | --- | --- |
| `String`、`Character`、`Enum` | `TEXT` | `text` |
| `Byte`、`Short`、`Integer` | `INT` | `int` |
| `Long` | `BIGINT` | `bigint` |
| `Float`、`Double` | `DOUBLE` | `double` |
| `Boolean` | `BOOLEAN` | `boolean` |
| `Date`、`Instant` | `TIMESTAMP` | `timestamp` |

默认 `autoCreateMetadataColumns=true`，写入未声明的新标量字段时会推断类型、执行 `ALTER TABLE ADD` 并创建 SAI。
列一旦创建，后续写入必须保持相同类型。集合、数组、Map 和嵌套对象当前不自动映射，应在业务层转为稳定标量或拆成
多个字段。

生产环境建议：

```java
config.setAutoCreateMetadataColumns(false);
```

然后由配置或迁移脚本完整声明字段，防止拼写错误在运行时创建永久列和索引。

## 高级查询

### 等值与范围

```java
SearchWrapper search = new SearchWrapper()
    .eq("tenant", "team-a")
    .ge("views", 10)
    .lt("views", 1000)
    .between("publishedYear", 2024, 2026);
search.setVector(queryEmbedding);
```

BETWEEN 会转换为同一查询分支中的 `>=` 和 `<=`。参与过滤的 metadata 列必须存在并有 SAI。

### IN 与 OR

```java
SearchWrapper search = new SearchWrapper()
    .in("category", Arrays.asList("AI", "database"))
    .orCriteria(group -> group.eq("status", "featured"))
    .maxResults(10);
search.setVector(queryEmbedding);
```

Cassandra 不能把这些条件直接写成一条 ANN CQL。模块会执行多个分支：

```text
category = 'AI'
category = 'database'
status = 'featured'
```

每个分支都在 Cassandra 服务端执行过滤和 ANN，随后按业务 ID 去重，保留同一文档的最高 score，再进行全局排序并
截取 `maxResults`。条件展开最多 256 个分支，超过时拒绝查询，防止组合爆炸。

### SQL 风格条件

```java
SearchWrapper search = new SearchWrapper()
    .condition("tenant = 'team-a' AND (category IN ('AI', 'Java') OR views >= 100)")
    .maxResults(10);
search.setVector(queryEmbedding);
```

字符串先由 `SearchWrapper` 解析为通用条件树，Cassandra Store 再把树规划为可执行 CQL 分支。解析成功只代表语法
合法，最终是否支持仍由 Cassandra Store 根据 CQL/SAI 能力校验。

### 纯 metadata 查询

```java
SearchWrapper filterOnly = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "team-a")
    .ge("views", 100)
    .maxResults(100);

List<Document> documents = store.search(filterOnly);
```

纯过滤不需要查询向量，结果的 `score` 为 null。没有条件时会执行带 LIMIT 的表扫描，只适合受控管理任务，不应作为
高频在线查询。

## 条件能力与限制

| 条件 | 支持情况 | 实现方式 |
| --- | --- | --- |
| EQ | 支持 | 单条参数化 CQL |
| GT / GE / LT / LE | 支持 | SAI 范围条件 |
| BETWEEN | 支持 | 展开为上下界 |
| AND | 支持 | 同一查询分支 |
| IN | 支持 | 展开为多个 EQ 分支后合并 |
| OR | 支持 | 多条查询后合并 |
| NE / `!=` | 不支持 | Cassandra SAI 不支持该关系 |
| NOT IN | 不支持 | Cassandra CQL 不支持该语法 |
| IS NULL / IS NOT NULL | 不支持 | Cassandra CQL/SAI 无对应通用过滤 |
| NOT / AND NOT / OR NOT | 不支持 | 不使用全表客户端过滤模拟 |

这些限制同样适用于链式 API 和 SQL 风格字符串。Store 会抛出 `IllegalArgumentException`，错误消息会明确指出
Cassandra 5.x CQL/SAI 限制。

## 相似度与分值

```java
config.setSimilarity(CassandraSimilarity.COSINE);
config.setSimilarity(CassandraSimilarity.DOT_PRODUCT);
config.setSimilarity(CassandraSimilarity.EUCLIDEAN);
```

配置同时决定向量 SAI 的 `similarity_function` 和 SELECT 使用的 `similarity_*` 函数。已有表的索引度量不会被自动
修改；切换 Embedding 模型、维度或相似度时应新建 Collection，并完成全量重建与业务验证。

不同相似度函数的 score 分布不同，`minScore` 必须使用真实模型和数据校准，不能复用其他数据库的阈值。

## 多 Collection

```java
StoreOptions product = StoreOptions.ofCollectionName("product_documents");
StoreOptions support = StoreOptions.ofCollectionName("support_documents");

store.store(productDocuments, product);
store.store(supportDocuments, support);
List<Document> result = store.search(search, support);
```

两个 Collection 对应两张独立 Cassandra 表，可以使用相同业务 ID，不会互相覆盖。所有 CRUD 操作必须传入同一组
`StoreOptions`；表数量过多会增加 schema、SAI 和集群管理成本。

## 自动创建控制

```java
config.setAutoCreateKeyspace(false);
config.setAutoCreateCollection(false);
config.setAutoCreateMetadataColumns(false);
```

- 关闭 keyspace 自动创建时，keyspace 必须已存在；
- 关闭 Collection 自动创建时，表、vector 列和全部 SAI 必须由迁移流程预建；
- 关闭 metadata 自动建列时，仍可通过 `metadataFieldTypes` 声明初始列，未声明字段会写入失败。

生产环境通常应由 DBA 或部署流水线创建 NetworkTopologyStrategy keyspace、表和索引，应用只负责读写。

## 一致性、分页与限制

- 模块使用 `LOCAL_ONE`，满足本地数据中心低延迟读写；更强一致性需要结合副本数和业务 SLA 设计；
- 普通读写默认超时 10 秒，可通过 `requestTimeoutMillis` 调整；schema DDL 使用独立的 `schemaAgreementTimeoutMillis`；
- ANN 的 `LIMIT` 最大为 1000，模块会拒绝更大的 `maxResults`；
- 查询 page size 设置为当前 LIMIT，避免在 topK 结果上产生不必要的分页；
- 单个 Store 持有长期 `CqlSession`，应复用并在应用关闭时调用 `close()`；
- SAI 建立会消耗 CPU、磁盘和 compaction 资源，不应让不受控 metadata 在生产环境持续自动建列；
- 多数据中心生产 keyspace 应使用 `NetworkTopologyStrategy`，不要使用本地示例的 `SimpleStrategy`。

## 真实集成测试

本模块测试使用 Cassandra 5.0.5 真实执行建表、SAI、ANN 和过滤，不使用 Mock：

```bash
mvn -pl agents-flex-store/agents-flex-store-cassandra -am \
  -Dmaven.javadoc.skip=true \
  -Dtest=CassandraConditionPlannerTest,CassandraVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.cassandra.integration=true \
  -Dagentsflex.cassandra.contact-point=127.0.0.1:9043 \
  test
```

测试覆盖：

- 随机 keyspace、表、vector SAI 和标量 SAI 的真实创建；
- cosine 与 euclidean ANN；
- EQ、范围、BETWEEN、IN、OR 和 SQL 风格表达式；
- 纯过滤、字段选择、向量返回和 minScore；
- 多 Collection 隔离、覆盖更新和删除；
- 关闭自动建表以及不支持条件的明确异常；
- 测试结束后删除随机 keyspace。

## 生产建议

1. 固定 Cassandra 与 Java Driver 版本，并先验证滚动升级；
2. 使用生产 Embedding 模型确认维度、归一化方式和相似度；
3. 预先声明 metadata schema，限制字段数量和 IN/OR 分支数量；
4. 按节点磁盘、compaction、SAI 内存和索引构建时间规划容量；
5. 配置认证、TLS、最小权限、网络边界和凭证轮换；
6. 使用 NetworkTopologyStrategy 和正确的本地数据中心名称；
7. 监控读写超时、dropped messages、tombstone、compaction、SAI build 和磁盘水位；
8. 执行节点故障、滚动重启、备份恢复和全量重建演练；
9. 模型升级时使用新表完成蓝绿切换，不要修改已有 vector 维度；
10. 使用真实数据验证 Recall@K、过滤后召回率以及 P95/P99 延迟。

</div>
