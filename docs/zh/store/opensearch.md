<div v-pre>

# OpenSearchVectorStore

## 概述

`OpenSearchVectorStore` 使用 OpenSearch Java Client，自动创建启用 k-NN 的 Index，并通过 `knn_score`
script score 执行余弦相似度检索。

当前实现支持 CRUD、向量检索、最低分值和按 Index 路由，但尚未把 `SearchWrapper.condition` 和输出字段控制
接入查询。这是选择该模块前必须了解的边界。

## 本地安装

### 单节点 Docker

```bash
docker run --name agents-flex-opensearch \
  -p 9201:9200 \
  -p 9600:9600 \
  -e discovery.type=single-node \
  -e DISABLE_SECURITY_PLUGIN=true \
  -e "OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g" \
  -d opensearchproject/opensearch:2.17.1
```

验证：

```bash
curl -fsS http://127.0.0.1:9201/
curl -fsS http://127.0.0.1:9201/_cluster/health?pretty
```

关闭安全插件只适用于本机开发。生产环境使用官方安全插件、TLS 和最小权限账号。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-opensearch</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

当前 `OpenSearchVectorStoreConfig.checkAvailable()` 要求 API Key 或用户名密码。即使本地容器关闭认证，建议使用
带认证的本地配置测试生产路径，或直接构造 Store 而不先调用 `checkAvailable()`。

```java
OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
config.setServerUrl("http://127.0.0.1:9201");
config.setDefaultIndexName("knowledge");

OpenSearchVectorStore store = new OpenSearchVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

认证集群：

```java
config.setUsername(System.getenv("OPENSEARCH_USERNAME"));
config.setPassword(System.getenv("OPENSEARCH_PASSWORD"));
// 或 config.setApiKey(System.getenv("OPENSEARCH_API_KEY"));
```

::: warning TLS 安全边界
当前默认构造器创建了信任所有证书并关闭主机名校验的 TLS 策略。不要直接把这一行为用于生产。生产接入应通过
`OpenSearchVectorStore(config, client)` 注入经过正确 CA 和主机名校验配置的 `OpenSearchClient`，或先修正
默认客户端构造逻辑。
:::

## 快速开始

```java
Document document = Document.of("OpenSearch k-NN 向量检索示例");
document.setId("os-001");
document.putMetadata("tenant", "demo");

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("OpenSearch 向量搜索")
    .maxResults(5)
    .minScore(0.5);

List<Document> result = store.search(query);
```

首次写入创建 `knn=true` 的 Index，默认 mapping 包含 `content` 文本字段和 `vector` knn_vector 字段，维度取
`EmbeddingModel.dimensions()`。

## 更新、删除和多 Index

```java
StoreOptions options = new StoreOptions();
options.setIndexName("tenant-a-knowledge");

store.store(documents, options);
store.update(documents, options);
store.delete(ids, options);
store.search(query, options);
```

Index 路由必须使用 `indexName`。

## 高级查询

当前搜索请求以 `match_all` 作为 script score 的基础 query：

```java
query.eq("tenant", "tenant-a");
```

上述 Condition 当前不会进入 OpenSearch 请求，不能作为租户隔离或安全过滤。需要 metadata 条件时，应：

1. 扩展 Store，把 Condition 转换为 OpenSearch Query DSL；
2. 在 script score 外层组合 bool filter；
3. 添加 AND/OR/IN/BETWEEN/NULL 和多租户真实测试；
4. 在修复前选择已支持条件的 Store。

`outputFields` 和 `outputVector` 当前也没有应用到 source filtering，返回行为由 OpenSearch 文档反序列化决定。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-opensearch test
```

## 生产建议

- 注入安全配置的客户端，不使用默认的全信任 TLS；
- 预建 Index Template 并明确向量 engine、space type 和参数；
- 在条件能力补齐前不要用于共享 Collection 多租户隔离；
- 监控 bulk item error，而不仅是 HTTP 状态；
- 校准 `knn_score` 与 `minScore`；
- 应用关闭时管理底层 transport 生命周期。

</div>
