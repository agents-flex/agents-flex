<div v-pre>

# ElasticSearchVectorStore

## 概述

`ElasticSearchVectorStore` 使用 Elasticsearch Java API Client，将 Collection 概念映射为 Elasticsearch Index。
当前实现通过 `dense_vector` 和脚本余弦相似度执行向量检索，并把 `SearchWrapper` 条件转换为 query string。

它适合已经使用 Elasticsearch、需要 metadata 过滤或希望将搜索运维统一到现有集群的系统。

## 本地安装

### 单节点 Docker

```bash
docker run --name agents-flex-elasticsearch \
  -p 9200:9200 \
  -e discovery.type=single-node \
  -e xpack.security.enabled=false \
  -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" \
  -d docker.elastic.co/elasticsearch/elasticsearch:8.15.5
```

等待健康：

```bash
curl -fsS http://127.0.0.1:9200/
curl -fsS http://127.0.0.1:9200/_cluster/health?pretty
```

如果主机内存不足，可降低 JVM heap，但向量索引测试至少应预留足够内存。上例关闭安全功能，仅允许本机开发。

### 清理

```bash
docker stop agents-flex-elasticsearch
docker rm agents-flex-elasticsearch
```

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-elasticsearch</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

无认证本地环境：

```java
ElasticSearchVectorStoreConfig config = new ElasticSearchVectorStoreConfig();
config.setServerUrl("http://127.0.0.1:9200");
config.setDefaultIndexName("knowledge");

ElasticSearchVectorStore store = new ElasticSearchVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

API Key：

```java
config.setApiKey(System.getenv("ELASTIC_API_KEY"));
```

Basic Auth：

```java
config.setUsername(System.getenv("ELASTIC_USERNAME"));
config.setPassword(System.getenv("ELASTIC_PASSWORD"));
```

三种认证方式只能选择与集群一致的一种。生产环境启用 TLS 和认证。

## 快速开始

```java
Document document = Document.of("Elasticsearch 支持向量检索和结构化过滤。");
document.setId("es-001");
document.setTitle("Elasticsearch Store");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("搜索引擎中的向量召回")
    .maxResults(5)
    .minScore(0.6)
    .eq("tenant", "demo");

List<Document> result = store.search(query);
```

首次写入会按文档向量维度创建 Index。写入和更新使用 refresh wait-for，因此调用返回后后续搜索可以看到数据。

## 高级查询

```java
SearchWrapper query = new SearchWrapper()
    .text("检索文档")
    .condition(
        "tenant = 'tenant-a' " +
        "AND status != 'deleted' " +
        "AND year BETWEEN 2024 AND 2026 " +
        "AND category IN ('guide', 'reference')"
    )
    .outputFields("id", "title", "content", "metadataMap")
    .outputVector(false);
```

适配器映射规则：

| 条件 | Elasticsearch query string |
| --- | --- |
| EQ / NE | 字段词项或 NOT 词项 |
| GT / GE / LT / LE | 开闭区间 |
| BETWEEN | `[start TO end]` |
| IN / NOT IN | OR 词项组 |
| EQ NULL / NE NULL | `_exists_` 判断 |

字段 mapping 决定查询是否正确。字符串精确匹配通常应指向 keyword 字段；对 text 字段执行精确过滤可能经过
分析器。上线前用实际 mapping 验证。

向量分值使用 `(cosineSimilarity + 1) / 2` 归一化到 0 到 1，并应用 `minScore`。

## 多 Index

Elasticsearch 使用 `StoreOptions.indexName`：

```java
StoreOptions options = new StoreOptions();
options.setIndexName("tenant-a-knowledge");

store.store(documents, options);
store.search(query, options);
store.delete(ids, options);
```

设置 `collectionName` 不会切换 Elasticsearch Index。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-elasticsearch \
  -Dtest=ElasticSearchVectorStoreIntegrationTest test
```

测试前确认 URL 和测试类读取的认证环境变量一致。

## 生产建议

```java
try (ElasticSearchVectorStore store = new ElasticSearchVectorStore(config)) {
    store.setEmbeddingModel(embeddingModel);
    // 使用 store
}
```

- Store 作为应用级单例并在关闭时释放客户端；
- 生产环境预建 mapping、Index Template 和 ILM 策略；
- 固定 Elasticsearch 客户端与服务端兼容版本；
- 监控 bulk 部分失败、refresh 成本、script score 延迟和 JVM 内存；
- 大规模检索评估原生 kNN、候选数和量化方案，而不是只使用脚本全量评分。

</div>
