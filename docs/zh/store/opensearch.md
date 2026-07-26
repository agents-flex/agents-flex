<div v-pre>

# OpenSearchVectorStore

## 概述

`OpenSearchVectorStore` 基于 OpenSearch Java Client 和 k-NN 插件实现向量数据存储。一个 Agents-Flex
Collection 对应一个 OpenSearch Index，文档正文、向量和 metadata 保存在同一条 `_source` 中。

当前实现支持：

- 批量写入、覆盖更新和按 ID 删除；
- 使用 `knn_score` 和余弦空间执行向量检索；
- EQ、NE、范围、BETWEEN、IN、NOT IN、NULL 和嵌套逻辑条件；
- SQL 风格条件表达式和纯 metadata 查询；
- `outputFields`、`outputVector`、`minScore` 和 `maxResults`；
- 多 Index 路由以及写入、更新、删除后的立即可查；
- API Key、Basic Auth 和无认证连接。

它适合已经采用 OpenSearch 或 Amazon OpenSearch Service，希望把向量召回、metadata 过滤、权限和运维体系
放在同一个搜索平台中的应用。

## 使用场景

适合以下场景：

- 已有 OpenSearch 集群和搜索运维经验；
- RAG 检索需要在向量召回前应用 tenant、状态、分类或时间条件；
- 需要通过独立 Index 隔离知识库、租户或 Embedding 模型版本；
- 希望复用 OpenSearch 的扩缩容、快照、监控和权限能力。

如果只是小规模本地知识库，或者团队没有 OpenSearch 运维能力，应同时评估 Chroma、Pgvector 或托管向量服务。
大规模上线前还需要用真实向量规模验证 script score 的延迟和召回率。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-opensearch</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 本地安装

### 使用 Docker 启动

以下命令启动 OpenSearch 2.17.1，映射到本机 `9201`，避免与常用的 Elasticsearch `9200` 端口冲突：

```bash
docker run -d --name agents-flex-opensearch \
  -p 127.0.0.1:9201:9200 \
  -p 127.0.0.1:9600:9600 \
  -e discovery.type=single-node \
  -e DISABLE_SECURITY_PLUGIN=true \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  opensearchproject/opensearch:2.17.1
```

等待集群启动并验证：

```bash
curl -fsS http://127.0.0.1:9201/
curl -fsS http://127.0.0.1:9201/_cluster/health?pretty
```

`DISABLE_SECURITY_PLUGIN=true` 只适用于本地开发。生产环境必须启用 TLS、认证、网络访问控制和最小权限。

### 停止和清理

```bash
docker stop agents-flex-opensearch
docker rm agents-flex-opensearch
```

## 配置连接

本地无认证环境：

```java
OpenSearchVectorStoreConfig config = new OpenSearchVectorStoreConfig();
config.setServerUrl("http://127.0.0.1:9201");
config.setDefaultIndexName("knowledge");

OpenSearchVectorStore store = new OpenSearchVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

Basic Auth：

```java
config.setUsername(System.getenv("OPENSEARCH_USERNAME"));
config.setPassword(System.getenv("OPENSEARCH_PASSWORD"));
```

API Key：

```java
config.setApiKey(System.getenv("OPENSEARCH_API_KEY"));
```

默认客户端使用 JVM 信任库和标准主机名校验。私有 CA 应导入应用 truststore；需要特殊代理、AWS SigV4 或自定义
TLS 配置时，构造 `OpenSearchClient` 后通过 `OpenSearchVectorStore(config, client)` 注入。

## 快速开始

```java
Document document = Document.of("OpenSearch 支持向量检索和 metadata 过滤。");
document.setId("os-001");
document.setTitle("OpenSearch Store");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("搜索平台中的向量召回")
    .maxResults(5)
    .minScore(0.6)
    .eq("metadataMap.tenant", "demo");

List<Document> results = store.search(query);
```

使用 `store.search(...)` 时，如果只设置了 `text`，`DocumentStore` 会通过已配置的 `EmbeddingModel` 生成查询向量。
直接调用 `doStore/doSearch` 时则需要自行提供向量。

首次写入新 Index 时，Store 优先从文档向量推断维度；文档没有向量时才使用 `EmbeddingModel.dimensions()`。
自动 mapping 包含 `content` 的 text 字段和 `vector` 的 `knn_vector` 字段，并启用 `index.knn`。

## 高级查询

### 链式条件

```java
SearchWrapper query = new SearchWrapper()
    .text("OpenSearch 条件检索")
    .eq("metadataMap.tenant", "tenant-a")
    .in("metadataMap.category", Arrays.asList("guide", "reference"))
    .between("metadataMap.year", 2024, 2026)
    .isNotNull("metadataMap.owner")
    .not(group -> group.eq("metadataMap.status", "deleted"))
    .maxResults(20);
```

### SQL 风格条件

```java
SearchWrapper query = new SearchWrapper()
    .text("查询文档")
    .condition(
        "metadataMap.tenant = 'tenant-a' " +
        "AND metadataMap.year BETWEEN 2024 AND 2026 " +
        "AND metadataMap.category NOT IN ('internal', 'deleted')"
    );
```

`SearchWrapper` 先把字符串解析为通用条件树，OpenSearch Store 再将条件适配成 query string，并放入
`script_score.query` 中。因此 metadata 条件在向量评分之前由服务端执行，不是在 Java 结果中二次过滤。

| SearchWrapper 条件 | OpenSearch 执行形式 |
| --- | --- |
| EQ / NE | 字段词项或 NOT 词项 |
| GT / GE / LT / LE | query string 开闭区间 |
| BETWEEN | `[start TO end]` |
| IN / NOT IN | OR 词项组及 NOT |
| IS NULL / IS NOT NULL | `_exists_` 判断 |
| AND / OR / NOT / Group | 对应逻辑表达式和括号 |

字符串字段是否精确匹配由 mapping 决定。动态字符串通常会映射出 `.keyword` 子字段；对 text 字段做权限或枚举过滤时，
应预建 mapping 并使用 keyword 字段路径。

### 纯条件查询

不需要向量相似度时关闭向量检索：

```java
SearchWrapper query = new SearchWrapper()
    .withVector(false)
    .condition("metadataMap.status = 'ready' AND metadataMap.year >= 2025")
    .maxResults(100);
```

`withVector(true)` 是默认值，此时没有查询向量会抛出明确异常。纯条件模式不计算向量分值，`minScore` 不生效。

## 控制返回字段

默认不返回向量，以减少网络和反序列化成本：

```java
query.outputFields("metadataMap.category", "metadataMap.year")
    .outputVector(false);
```

Store 会保留 `id`、`title` 和 `content`，并仅返回指定 metadata 路径。需要原始向量时显式开启：

```java
query.outputVector(true);
```

## 多 Index 与隔离

可以使用统一的 `collectionName`，也可以使用 OpenSearch 专属的 `indexName`：

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant-a-knowledge");

store.store(documents, options);
store.search(query, options);
store.update(documents, options);
store.delete(ids, options);
```

当两者同时设置时，`collectionName` 优先；两者都为空时使用 `defaultIndexName`。同一操作链必须传递同一个
`StoreOptions`，避免写入和查询落到不同 Index。权限敏感场景优先使用独立 Index，再在 Index 内追加 tenant 条件。

## 一致性和资源管理

写入、更新和删除使用 `refresh=wait_for`，方法返回后后续搜索可以看到变更。这个保证会增加高频写入时的等待成本，
需要在生产负载下验证吞吐和 refresh interval。

Store 持有底层连接，应作为应用级单例并在关闭时释放：

```java
try (OpenSearchVectorStore store = new OpenSearchVectorStore(config)) {
    store.setEmbeddingModel(embeddingModel);
    // 使用 store
}
```

## 测试与验证

普通单元和请求诊断测试不要求本地 OpenSearch：

```bash
mvn -pl agents-flex-store/agents-flex-store-opensearch -am \
  -Dtest=OpenSearchExpressionAdaptorTest,OpenSearchVectorStoreDiagnosticTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

启动前述 Docker 容器后执行真实集成测试：

```bash
mvn -pl agents-flex-store/agents-flex-store-opensearch -am \
  -Dtest=OpenSearchVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentsflex.opensearch.integration=true \
  -Dagentsflex.opensearch.url=http://127.0.0.1:9201 test
```

集成测试使用随机 Index，并验证多集合隔离、复杂条件、纯过滤、字段输出、更新和删除，结束后自动清理。

## 生产建议

- 预建 Index Template，固定 metadata 类型、keyword 字段、向量维度和 k-NN 参数；
- 不依赖动态 mapping 承载 tenant 或权限字段；
- 固定 OpenSearch Java Client 与服务端的兼容版本；
- 对动态条件表达式实施字段白名单、长度和 IN 数量限制；
- 监控 bulk item error、refresh 等待、script score 延迟、JVM 和 native memory；
- 用生产数据校准 `minScore`、topK、向量归一化和召回质量；
- 使用快照验证恢复流程，并用新 Index 完成 Embedding 模型迁移。

## 常见问题

### 写入时报向量维度错误

同一个 Index 的向量维度固定。确认写入文档使用同一 Embedding 模型；更换模型或维度时创建新 Index。

### 条件没有命中预期字符串

检查字段 mapping。枚举和标识符应使用 keyword 类型或 `.keyword` 路径，text 字段会受分析器影响。

### HTTPS 证书校验失败

将签发 CA 导入 JVM truststore，并确保 URL 主机名与证书一致。默认客户端不会绕过证书或主机名校验。

### 写入成功但搜索报 mapping 错误

自动 mapping 只固定核心字段。生产环境应预建 metadata mapping，避免首条文档把字段推断为错误类型。

</div>
