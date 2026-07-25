<div v-pre>

# MilvusVectorStore

## 概述

`MilvusVectorStore` 使用 Milvus Java SDK v2，支持自动创建 Collection、自动创建向量索引、Collection 缓存、
字符串/数字主键和 Milvus 标量过滤表达式。它适合向量规模较大、需要专用向量索引和分区能力的场景。

## 本地安装

### 使用官方 standalone 脚本

Milvus Standalone 还需要 etcd 和对象存储。官方脚本会生成并启动所需 Docker 容器：

```bash
curl -sfL \
  https://raw.githubusercontent.com/milvus-io/milvus/master/scripts/standalone_embed.sh \
  -o standalone_embed.sh

# 在执行下载脚本前先检查内容。
less standalone_embed.sh
bash standalone_embed.sh start
```

检查容器与健康状态：

```bash
docker ps
curl -fsS http://127.0.0.1:9091/healthz
```

Milvus SDK 默认连接端口为 19530。停止环境：

```bash
bash standalone_embed.sh stop
```

脚本来自 Milvus 主分支，适合本地体验。团队环境应使用官方固定版本 Docker Compose、Milvus Operator 或 Helm，
并把部署配置纳入版本控制。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-milvus</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
MilvusVectorStoreConfig config = MilvusVectorStoreConfig.builder()
    .endpoint("http://localhost:19530")
    .token("root:Milvus")
    .defaultCollectionName("knowledge")
    .defaultDimension(1536)
    .build();

MilvusVectorStore store = MilvusVectorStore.create(config);
store.setEmbeddingModel(embeddingModel);
```

常用配置包括：

| 配置 | 说明 |
| --- | --- |
| `endpoint` | Milvus 或 Zilliz Cloud 地址 |
| `token` | 用户名密码 Token 或云 API Key |
| `database` | Milvus 2.4+ 数据库名，默认 `default` |
| `defaultCollectionName` | 默认 Collection |
| `defaultDimension` | 向量维度 |
| `vectorField` / `idField` | 向量字段和主键字段 |
| `metricType` | `COSINE`、`IP` 或 `L2` |
| `enableDynamicField` | 是否使用动态字段保存 metadata |
| `defaultTopK` | 默认召回数量 |
| `consistencyLevel` | Strong、Session、Bounded 或 Eventually |

具体字段以当前 `MilvusVectorStoreConfig` 为准。Token 属于敏感信息，不要输出到日志。

## 快速开始

```java
Document document = Document.of("Milvus 专注于大规模向量检索。");
document.setId("milvus-001");
document.putMetadata("tenant", "demo");
document.putMetadata("year", 2026);

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("Milvus 向量数据库")
    .maxResults(10)
    .minScore(0.6)
    .eq("tenant", "demo");

List<Document> result = store.search(query);

document.setContent("更新后的内容");
store.update(document);
store.delete(document.getId());
```

## 高级查询

Milvus 使用专属 `MilvusExpressionAdaptor`：

```java
SearchWrapper query = new SearchWrapper()
    .text("Milvus 条件过滤")
    .condition(
        "tenant = 'tenant-a' " +
        "AND category IN ('guide', 'reference') " +
        "AND year BETWEEN 2024 AND 2026"
    );
```

适配器支持比较、IN/NOT IN、BETWEEN、NULL 和分组，并负责字符串转义。SQL 风格 `BETWEEN` 会展开为两条
Milvus 比较条件。

## 自动创建 Collection

首次操作时可以自动创建 Collection、向量索引并加载集合。Store 支持字符串和数字主键识别，并缓存已确认存在
的集合。不同集合的 schema 必须与当前配置和向量维度一致。

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
```

生产环境如果由 DBA 预建 schema，应关闭或绕开自动创建流程，并用部署检查验证字段、主键类型、维度、metric
和动态字段配置。

## 分区

```java
StoreOptions options = StoreOptions.ofCollectionName("knowledge");
options.partitionName("release_2026");

List<Document> result = store.search(query, options);
```

是否自动创建分区以及多个分区名的行为应结合当前配置和 Milvus 版本验证。集合与分区不能代替业务 tenant
过滤；采用哪一种隔离策略应保持一致。

## 客户端复用

相同连接配置的 Store 实例会复用 Milvus 客户端连接。普通业务代码不需要为每次请求创建 Store；推荐把 Store
作为应用级单例，`SearchWrapper` 和 `StoreOptions` 按请求创建。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-milvus \
  -Dtest=MilvusVectorStoreFilterIntegrationTest test
```

真实测试应覆盖字符串/数字主键、多个集合、IN/NOT IN、BETWEEN、NULL、嵌套逻辑和维度不匹配。

## 常见问题

### Collection 已存在但 schema 不兼容

检查主键类型、向量字段名、维度、metric、动态字段和内容字段配置。不要删除生产 Collection 来“自动修复”；
创建新 Collection、迁移数据并切换路由。

### 搜索前 Collection 未加载

确认自动加载成功、Milvus 节点资源充足，并检查服务端日志中的 index/load 任务状态。

### Token 连接失败

本地默认凭证、Zilliz Cloud API Key 和启用 RBAC 的 Milvus Token 格式不同，以目标服务官方文档为准。

## 生产建议

- 固定 Milvus、SDK、etcd 和对象存储版本；
- 根据数据规模设计 shard、partition、replica、index 和 consistency；
- 用真实数据评估 HNSW 参数、召回率和延迟；
- 对 Collection 创建和加载使用明确的超时与监控；
- 多实例共享客户端，但请求级 wrapper/options 不共享；
- 对 Token、网络、备份和对象存储配置执行安全审查。

</div>
