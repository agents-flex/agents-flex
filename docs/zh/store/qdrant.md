<div v-pre>

# QdrantVectorStore

## 概述

`QdrantVectorStore` 使用官方 gRPC Client。文档向量保存为 Point vector，ID、标题、正文和业务 metadata 保存到
payload；查询条件直接转换为 Qdrant 原生 `Filter`。

它支持自动创建 Collection、API Key、自定义 CA、复杂 payload 过滤和按 Collection 路由。

## 本地安装

### Docker 启动

```bash
docker run --name agents-flex-qdrant \
  -p 6333:6333 \
  -p 6334:6334 \
  -v agents-flex-qdrant-data:/qdrant/storage \
  -d qdrant/qdrant:latest
```

- 6333：HTTP API 和 Dashboard；
- 6334：`QdrantVectorStore` 使用的 gRPC API。

验证：

```bash
curl -fsS http://127.0.0.1:6333/healthz
curl -fsS http://127.0.0.1:6333/collections
```

浏览器可打开 `http://127.0.0.1:6333/dashboard`。生产环境固定镜像版本并启用认证和 TLS。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-qdrant</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
QdrantVectorStoreConfig config = new QdrantVectorStoreConfig();
config.setUri("127.0.0.1:6334");
config.setDefaultCollectionName("knowledge");
config.setAutoCreateCollection(true);

QdrantVectorStore store = new QdrantVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

远程服务：

```java
config.setUri("qdrant.example.internal:6334");
config.setApiKey(System.getenv("QDRANT_API_KEY"));
config.setCaPath("/etc/agents-flex/qdrant-ca.pem");
```

配置 CA 文件后使用 TLS。CA 路径应是应用进程可读的实际文件。

## 快速开始

```java
Document document = Document.of("Qdrant 使用 payload 保存文档元数据。");
document.setId("business-doc-001");
document.setTitle("Qdrant 入门");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("Qdrant payload filter")
    .maxResults(5)
    .eq("tenant", "demo");

List<Document> result = store.search(query);
```

Qdrant Point ID 有类型限制。Store 会把业务 ID 转换为可用 Point ID，并在 `__agentsflex_id` payload 中保留原值。

## 高级查询

```java
SearchWrapper query = new SearchWrapper()
    .text("生产部署")
    .condition(
        "tenant = 'tenant-a' " +
        "AND status NOT IN ('deleted', 'blocked') " +
        "AND year BETWEEN 2024 AND 2026 " +
        "AND (featured = true OR views > 10000)"
    )
    .outputVector(false);
```

Condition Builder 支持比较、IN/NOT IN、BETWEEN、NULL、AND/OR、NOT 和嵌套 Group，分别转换为 Qdrant
match、range、isNull、must、should 和 mustNot。

payload 数字字段应始终写为数字，Boolean 和字符串也不能混用。类型不稳定会让 range 或 match 条件漏数据。

## 保留字段

以下 payload key 由 Store 使用：

```text
__agentsflex_id
__agentsflex_title
__agentsflex_content
```

业务 metadata 不要使用这些名称。

## 多 Collection

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
store.delete(ids, options);
```

每次操作都必须传相同 options。自动创建需要可用的向量维度和足够权限。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-qdrant test
```

测试应至少覆盖字符串和 UUID 文档 ID、IN/NOT IN、范围条件、NULL、嵌套 AND/OR/NOT、两个 Collection 的
隔离，以及 `outputVector` 的返回行为。

## 清理本地环境

```bash
docker stop agents-flex-qdrant
docker rm agents-flex-qdrant
docker volume rm agents-flex-qdrant-data
```

删除 volume 不可恢复，只在确认本地测试数据不再需要时执行。

## 生产建议

应用关闭时调用 `store.close()` 释放 gRPC 客户端。

- 使用 API Key、TLS、私网和最小网络暴露；
- 固定 Qdrant 版本并备份 storage；
- 根据规模配置 HNSW、segment、量化和 replication；
- 监控 Collection 状态、优化任务、磁盘与查询延迟；
- 用真实条件和多租户隔离测试验证 payload schema。

</div>
