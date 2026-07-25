<div v-pre>

# ChromaVectorStore

## 概述

`ChromaVectorStore` 通过 Chroma v2 REST API 管理租户、数据库、Collection 和文档。它使用结构化 `where`
JSON 实现 metadata 过滤，适合本地开发、原型验证和中小规模知识库。

当前实现可自动确保 tenant、database 和 collection 存在，并对临时 HTTP 错误做有限重试。

## 本地安装

### Docker 启动

```bash
docker run --name agents-flex-chroma \
  -p 8000:8000 \
  -d chromadb/chroma:latest
```

验证心跳：

```bash
curl -fsS http://127.0.0.1:8000/api/v2/heartbeat
```

查看日志：

```bash
docker logs agents-flex-chroma
```

生产或需要保留本地数据时应挂载 Chroma 数据目录，并固定镜像版本。不同 Chroma 版本的 API 路径可能变化，
当前 Store 使用 `/api/v2`。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-chroma</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
ChromaVectorStoreConfig config = new ChromaVectorStoreConfig();
config.setHost("127.0.0.1");
config.setPort(8000);
config.setTenant("default_tenant");
config.setDatabase("default_database");
config.setCollectionName("knowledge");
config.setAutoCreateCollection(true);

ChromaVectorStore store = new ChromaVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

可选 Token：

```java
config.setApiKey(System.getenv("CHROMA_API_KEY"));
```

| 配置 | 默认值 |
| --- | --- |
| `host` | `localhost` |
| `port` | `8000` |
| `tenant` | `default_tenant` |
| `database` | `default_database` |
| `collectionName` | `agents-flex-store` |
| `autoCreateCollection` | `true` |

当前 `getBaseUrl()` 固定生成 `http://host:port`。HTTPS、路径前缀或自定义网关需要扩展配置或在内网代理层处理。

## 快速开始

```java
Document document = Document.of("Chroma 使用 Collection 保存向量文档。");
document.setId("chroma-001");
document.setTitle("Chroma 入门");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("Chroma 向量数据库")
    .maxResults(5)
    .eq("tenant", "demo");

List<Document> result = store.search(query);
```

标题使用内部 metadata key 保存，读取结果时再恢复为 `Document.title`。

## 高级查询

```java
SearchWrapper query = new SearchWrapper()
    .text("部署文档")
    .condition(
        "tenant = 'tenant-a' " +
        "AND category IN ('guide', 'faq') " +
        "AND year BETWEEN 2024 AND 2026"
    );
```

`ChromaConditionBuilder` 转换关系：

| Agents-Flex | Chroma |
| --- | --- |
| EQ / NE | `$eq` / `$ne` |
| GT / GE | `$gt` / `$gte` |
| LT / LE | `$lt` / `$lte` |
| IN / NIN | `$in` / `$nin` |
| BETWEEN | 两个比较组成 `$and` |
| AND / OR | `$and` / `$or` |

Chroma metadata 过滤不支持 NULL。IN/NOT IN 不能为空，值必须是字符串、数字或 Boolean 等 Chroma 支持的标量。

## Collection 路由

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
```

Store 会把 Collection 名解析为 Chroma Collection ID 后执行操作。租户、数据库来自 Config，不由
`StoreOptions` 切换；跨 tenant/database 使用不同 Store 实例。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-chroma \
  -Dtest=ChromaVectorStoreTest test
```

同时运行 `ChromaConditionBuilderTest` 可验证条件树转换。真实 HTTP 测试需要确认 Chroma 容器可访问。

## 生产建议

- 固定 Chroma 服务和客户端协议版本；
- 配置持久卷、备份、认证和网络边界；
- 控制 Collection 数量，避免为短生命周期请求创建集合；
- metadata 字段保持稳定类型；
- 对自动创建失败和重试建立监控；
- 大规模生产负载上线前进行容量和并发测试。

</div>
