<div v-pre>

# RedisVectorStore

## 概述

`RedisVectorStore` 基于 RedisJSON 保存文档，使用 RediSearch HNSW 索引执行 KNN 查询。每个 Collection 使用
独立索引和 Key 前缀，并按索引隔离 schema、创建锁和 metadata 字段类型缓存。

它适合已经使用 Redis Stack、希望获得低延迟检索和简单部署的系统。普通 Redis 不包含所需模块。

## 本地安装

必须使用包含 RedisJSON 和 RediSearch 的 Redis Stack：

```bash
docker run --name agents-flex-redis -p 6379:6379 \
  -v agents-flex-redis-data:/data \
  -d redis/redis-stack-server:latest
```

验证连接和模块：

```bash
docker exec agents-flex-redis redis-cli PING
docker exec agents-flex-redis redis-cli MODULE LIST
```

输出应包含 `search` 和 `ReJSON`（名称随版本可能有大小写差异）。如果 `FT.INFO` 或 JSON 命令未知，连接的不是
Redis Stack。

停止但保留数据：

```bash
docker stop agents-flex-redis
docker start agents-flex-redis
```

生产环境应固定镜像版本，配置认证、持久化、备份、内存上限和淘汰策略。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-redis</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
RedisVectorStoreConfig config = new RedisVectorStoreConfig();
config.setUri("redis://localhost:6379");
config.setStorePrefix("agentsflex:docs:");
config.setDefaultCollectionName("knowledge");

RedisVectorStore store = new RedisVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

| 配置 | 说明 |
| --- | --- |
| `uri` | Jedis 连接 URI，可包含认证和数据库信息 |
| `storePrefix` | 所有文档 Redis Key 的公共前缀 |
| `defaultCollectionName` | 未传 `StoreOptions` 时的集合名 |

## 快速开始

```java
Document document = Document.of("Redis Stack 保存向量文档");
document.setId("redis-001");
document.putMetadata("tenant", "demo");
document.putMetadata("year", 2026);

store.store(document);

document.setContent("更新后的 Redis 文档");
store.update(document);

List<Document> result = store.search(
    new SearchWrapper().text("Redis 文档").eq("tenant", "demo")
);

store.delete(document.getId());
```

写入和更新使用 JSON 文档；批量操作通过 pipeline 降低网络往返。批量返回成功前仍应检查 `StoreResult`。

## 集合隔离

每个 Collection 使用独立 RediSearch 索引和独立 Key 前缀。内部索引存在性、创建锁和元数据字段类型缓存也按
索引名隔离。

```java
StoreOptions products = StoreOptions.ofCollectionName("products");
StoreOptions manuals = StoreOptions.ofCollectionName("manuals");

store.store(productDocuments, products);
store.store(manualDocuments, manuals);

List<Document> result = store.search(query, manuals);
```

如果写入后查询不到数据，首先打印本次调用解析出的集合名，确认四类操作使用了同一个 options。

## 索引创建

首次操作某集合时，Store 会：

1. 调用 `FT.INFO` 检查索引；
2. 不存在时创建 JSON 索引和集合专属前缀；
3. 校验向量字段维度与结构；
4. 缓存已有元数据字段；
5. 并发进程同时创建时识别“索引已存在”并重新读取结构。

创建向量 schema 需要 `EmbeddingModel.dimensions()`。如果只写入预计算向量但没有 EmbeddingModel，当前自动
建索引流程无法推断维度，应在接入测试中提前验证。

## 高级查询

Redis 适配器把有序比较和 BETWEEN 映射为 NUMERIC range，把字符串相等、IN 和 NOT IN 映射为 TAG 查询。
首次查询新的元数据字段时会按推断类型创建字段索引。

```java
SearchWrapper query = new SearchWrapper()
    .text("部署指南")
    .eq("tenant", "tenant-a")
    .in("category", Arrays.asList("guide", "faq"))
    .between("year", 2024, 2026);
```

同一个集合中，同名字段应保持稳定类型。不要让某些文档把 `year` 写为数字，另一些写为字符串。

### 复杂查询

```java
SearchWrapper query = new SearchWrapper()
    .text("Redis 向量检索")
    .maxResults(10)
    .minScore(0.55)
    .condition(
        "tenant = 'tenant-a' " +
        "AND category NOT IN ('hidden', 'deleted') " +
        "AND year BETWEEN 2024 AND 2026 " +
        "AND (featured = true OR views >= 1000)"
    );
```

字符串 TAG 查询会转义 RediSearch 特殊字符。数字条件创建 NUMERIC 字段。字段第一次以某类型建索引后，后续
查询不能把同名字段当作另一类型。

## 特殊字段

`text`、`vector`、`score` 是 Store 保留字段，不应作为业务 metadata 名称。业务字段名会转换为 Redis JSONPath
和安全别名；仍建议使用稳定、简单的 ASCII schema。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-redis \
  -Dtest=RedisVectorStoreCollectionIsolationTest test
```

测试前确认 `localhost:6379` 指向 Redis Stack，而不是普通 Redis。

## 清理本地环境

```bash
docker stop agents-flex-redis
docker rm agents-flex-redis
docker volume rm agents-flex-redis-data
```

删除 volume 会永久删除本地测试数据。

## 生产建议

- 不要对向量数据使用会意外淘汰业务 Key 的内存策略；
- 固定向量维度和距离类型，更换模型时创建新 Collection；
- 预热或预建常用 metadata 字段，避免首个查询承担 ALTER 成本；
- 监控内存、索引大小、搜索延迟、pipeline 错误和 key 数量；
- 使用 TLS/ACL 或受控网络，不暴露无认证 Redis；
- 对 Collection 数量、字段数量和动态字段名设置业务限制。

</div>
