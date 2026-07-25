<div v-pre>

# PgvectorVectorStore

## 概述

`PgvectorVectorStore` 把每个 Collection 映射为一张 PostgreSQL 表，使用 pgvector 的 `vector` 类型保存向量，
固定字段保存为普通列，其他 metadata 保存为 JSONB。它适合已经使用 PostgreSQL、希望复用事务、备份、权限和
监控体系的团队。

当前实现支持自动安装扩展、自动建表、可选 HNSW 索引、事务批量写入、参数化条件查询和按 Collection 路由。

## 本地安装

### 使用 Docker

```bash
docker run --name agents-flex-pgvector \
  -e POSTGRES_USER=agentsflex \
  -e POSTGRES_PASSWORD=agentsflex_dev \
  -e POSTGRES_DB=agent_vector \
  -p 5432:5432 \
  -d pgvector/pgvector:pg16
```

检查容器和数据库：

```bash
docker ps --filter name=agents-flex-pgvector
docker exec agents-flex-pgvector \
  pg_isready -U agentsflex -d agent_vector
```

查看 vector 扩展：

```bash
docker exec -it agents-flex-pgvector \
  psql -U agentsflex -d agent_vector \
  -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"
```

示例密码仅用于本机。生产环境固定镜像版本，使用密钥管理并限制 5432 端口访问。

### 停止和清理

```bash
docker stop agents-flex-pgvector
docker rm agents-flex-pgvector
```

删除容器会丢失未挂载卷的数据。本地需要保留数据时添加命名卷。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-pgvector</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
PgvectorVectorStoreConfig config = new PgvectorVectorStoreConfig();
config.setHost("127.0.0.1");
config.setPort(5432);
config.setDatabaseName("agent_vector");
config.setUsername("agentsflex");
config.setPassword(System.getenv().getOrDefault(
    "PGVECTOR_PASSWORD", "agentsflex_dev"
));
config.setDefaultCollectionName("knowledge");
config.setVectorDimension(1536);
config.setAutoCreateCollection(true);
config.setUseHnswIndex(true);

PgvectorVectorStore store = new PgvectorVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | 无 | PostgreSQL 主机 |
| `port` | `5432` | PostgreSQL 端口 |
| `databaseName` | `agent_vector` | 数据库名 |
| `defaultCollectionName` | 无 | 默认表名 |
| `vectorDimension` | `1024` | 新表向量维度 |
| `autoCreateCollection` | `true` | 是否自动创建表 |
| `useHnswIndex` | `false` | 是否创建 HNSW 索引 |
| `properties` | 空 Map | 传给 `PGSimpleDataSource` 的附加属性 |

构造 Store 时调用 `initDb()`，执行 `CREATE EXTENSION IF NOT EXISTS vector`，并按配置创建默认表。生产数据库
通常由 DBA 预装扩展；应用用户只保留目标 schema 的必要权限。

## 快速开始

```java
Document document = Document.of("pgvector 可以把向量和关系数据保存在同一数据库。");
document.setId("pg-001");
document.setTitle("Pgvector 入门");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

StoreResult stored = store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("PostgreSQL 向量检索")
    .maxResults(5)
    .eq("tenant", "demo");

List<Document> result = store.search(query);
```

## 更新和删除

```java
document.setContent("更新后的正文");
StoreResult updated = store.update(document);

StoreResult deleted = store.delete(document.getId());
```

更新使用 SQL `UPDATE ... WHERE id = ?`，不存在的 ID 不会插入新记录。业务需要区分 upsert 时应自行检查或扩展。

## 高级查询

### JSONB metadata

```java
SearchWrapper query = new SearchWrapper()
    .text("生产部署")
    .condition(
        "tenant = 'tenant-a' " +
        "AND metadata.profile.level >= 3 " +
        "AND category IN ('guide', 'reference') " +
        "AND year BETWEEN 2024 AND 2026"
    );
```

`id`、`title`、`content` 是固定列；其他字段从 JSONB `metadata` 读取。`metadata.` 和 `metadataMap.` 前缀会被
规范化。数字和 Boolean 条件会生成相应 CAST，因此同名字段必须保持稳定类型。

条件值全部通过 PreparedStatement 绑定。字段路径只允许字母、数字、下划线和连字符组成的片段。

### 相似度和输出

查询使用余弦距离 `<=>`，分值为 `1 - distance`，按 score 降序：

```java
query.minScore(0.65);
query.outputFields("tenant", "category", "year");
query.outputVector(true);
```

`outputFields` 过滤返回 metadata，`outputVector` 控制是否读取 vector 列。

## 多 Collection

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
```

Collection 是 SQL 标识符，当前实现会双引号转义。业务仍应通过白名单生成表名，限制租户创建表的数量。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-pgvector \
  -Dtest=PgvectorVectorStoreIntegrationTest test
```

确认测试报告没有 `Skipped`。测试连接参数以测试类当前读取的环境变量为准。

## 生产建议

- 用迁移脚本管理扩展、表和索引，不依赖应用账号自动 DDL；
- 根据数据量调整 HNSW 参数、连接池和 PostgreSQL 内存；
- 使用稳定 ID 和事务批次；
- 定期 ANALYZE，并监控慢 SQL、索引大小和连接数；
- 更换 Embedding 模型时新建表并重建全部向量；
- 对 Collection 名称实施白名单和租户配额。

</div>
