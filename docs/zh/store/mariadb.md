<div v-pre>

# MariaDBVectorStore

## 概述

`MariaDBVectorStore` 使用 MariaDB 11.7+ 的原生 `VECTOR(n)` 类型保存 Embedding，通过
`VEC_DISTANCE_COSINE` 或 `VEC_DISTANCE_EUCLIDEAN` 完成向量检索。每个 Collection 映射为独立数据表，
文档固定字段保存为普通列，业务 metadata 保存为 JSON。

它适合已经使用 MariaDB，希望复用数据库事务、权限、备份和监控体系，同时又需要中小规模语义检索的应用。
MariaDB 旧版本以及 MySQL 不提供这里使用的原生 VECTOR 接口，不能直接替代。

当前实现支持：

- 自动创建 Collection 表和 VECTOR INDEX；
- 事务批量写入、upsert、更新和删除；
- 余弦距离与欧氏距离；
- 向量检索和纯 metadata 过滤查询；
- SQL 风格条件、`IN/NOT IN`、`BETWEEN` 和嵌套分组；
- `StoreOptions` 多 Collection 路由；
- metadata 输出字段裁剪和可选向量返回。

## 本地安装

### 使用 Docker

```bash
docker run -d --name agents-flex-mariadb \
  -p 127.0.0.1:3307:3306 \
  -e MARIADB_DATABASE=agent_vector \
  -e MARIADB_USER=agentsflex \
  -e MARIADB_PASSWORD=agentsflex \
  -e MARIADB_ROOT_PASSWORD=agentsflex-root \
  mariadb:11.7
```

确认服务版本和连接状态：

```bash
docker exec agents-flex-mariadb \
  mariadb -uagentsflex -pagentsflex agent_vector \
  -e "SELECT VERSION();"
```

示例端口使用 `3307`，避免与本机已有 MySQL/MariaDB 的 `3306` 冲突。生产环境应固定补丁版本、挂载持久化卷，
并使用密钥管理系统保存密码。

停止和删除本地测试容器：

```bash
docker stop agents-flex-mariadb
docker rm agents-flex-mariadb
```

删除未挂载数据卷的容器会同时删除其中的数据。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-mariadb</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

模块使用 MariaDB Connector/J，不依赖 Spring JDBC。

## 配置

```java
MariaDBVectorStoreConfig config = new MariaDBVectorStoreConfig();
config.setHost("127.0.0.1");
config.setPort(3307);
config.setDatabaseName("agent_vector");
config.setUsername("agentsflex");
config.setPassword(System.getenv().getOrDefault(
    "MARIADB_PASSWORD", "agentsflex"
));
config.setDefaultCollectionName("knowledge");
config.setVectorDimension(1536);
config.setDistanceType(MariaDBDistanceType.COSINE);
config.setAutoCreateCollection(true);
config.setUseVectorIndex(true);

MariaDBVectorStore store = new MariaDBVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | 无 | MariaDB 主机 |
| `port` | `3306` | MariaDB 端口 |
| `databaseName` | `agent_vector` | 已存在的数据库名 |
| `username` / `password` | 无 | 数据库凭证 |
| `defaultCollectionName` | 无 | 默认 Collection 对应的表名 |
| `vectorDimension` | `1024` | 自动创建表时的向量维度 |
| `distanceType` | `COSINE` | `COSINE` 或 `EUCLIDEAN` |
| `autoCreateCollection` | `true` | 是否允许 Store 自动建表 |
| `useVectorIndex` | `true` | 自动建表时是否创建 VECTOR INDEX |
| `properties` | 空 Map | MariaDB JDBC URL 附加参数 |

生产环境通常应关闭 `autoCreateCollection`，通过数据库迁移脚本预建表，并只授予应用账号所需的 DML 权限。

## 快速开始

```java
Document document = Document.of("MariaDB 可以原生保存和检索向量。");
document.setId("mariadb-001");
document.setTitle("MariaDB Vector 入门");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

StoreResult stored = store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("MariaDB 向量检索")
    .eq("tenant", "demo")
    .maxResults(5)
    .minScore(0.65);

List<Document> result = store.search(query);
```

如果没有配置 `EmbeddingModel`，写入文档和查询必须直接携带维度一致的向量。

## 高级查询

### SQL 风格条件

```java
SearchWrapper query = new SearchWrapper()
    .text("生产部署")
    .condition(
        "tenant = 'tenant-a' " +
        "AND metadata.profile.level >= 3 " +
        "AND category IN ('guide', 'reference') " +
        "AND status NOT IN ('deleted', 'blocked') " +
        "AND year BETWEEN 2024 AND 2026"
    );
```

`id`、`title` 和 `content` 直接映射到表列；其他字段通过 `JSON_VALUE(metadata, path)` 查询。
`metadata.` 和 `metadataMap.` 前缀可以省略。条件值全部使用 `PreparedStatement` 参数绑定，metadata 路径片段只允许
字母、数字、下划线和连字符。

### 纯过滤查询

不需要向量排序时可以关闭向量检索：

```java
SearchWrapper query = new SearchWrapper()
    .withVector(false)
    .condition("tenant = 'tenant-a' AND status = 'published'")
    .maxResults(100);
```

纯过滤结果的 `score` 为 `null`。

### 返回字段和向量

```java
query.outputFields("tenant", "category", "year");
query.outputVector(true);
```

`outputFields` 只裁剪 metadata；文档的 ID、标题和正文仍会返回。默认不读取向量列。

## 多 Collection

```java
StoreOptions tenantA = StoreOptions.ofCollectionName("tenant_a_knowledge");
StoreOptions tenantB = StoreOptions.ofCollectionName("tenant_b_knowledge");

store.store(documentsA, tenantA);
store.store(documentsB, tenantB);
store.search(query, tenantA);
```

Collection 名会作为 SQL 标识符转义，不会作为普通 SQL 文本拼接。业务层仍应使用受控命名规则和租户配额，避免
无限创建数据表。

## 相似度分值

- `COSINE`：`score = 1 - VEC_DISTANCE_COSINE(...)`；
- `EUCLIDEAN`：`score = 1 / (1 + VEC_DISTANCE_EUCLIDEAN(...))`。

两种分值都按降序返回，并支持 `minScore`。更换距离类型、Embedding 模型或向量维度时，应创建新 Collection
并重新生成全部向量。

## 测试与验证

启动前面的 MariaDB 11.7 容器后运行：

```bash
mvn -pl agents-flex-store/agents-flex-store-mariadb -am \
  -Dtest=MariaDBVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.mariadb.integration=true \
  test
```

测试会创建随机命名的表，验证余弦和欧氏距离、条件查询、集合隔离、VECTOR INDEX、更新和删除，结束后删除测试表。

## 生产建议

- 使用 MariaDB 11.7 或更高稳定版本，并固定 Connector/J 兼容版本；
- 用迁移工具管理表和索引，不让应用账号执行生产 DDL；
- 根据实际数据校准距离类型、`minScore`、topK 和 VECTOR INDEX；
- 为 Collection 名实施白名单，限制租户表数量；
- 监控连接数、慢查询、索引大小、磁盘和备份状态；
- 在上线前执行数据库重启、备份恢复和模型迁移演练。

</div>
