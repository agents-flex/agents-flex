<div v-pre>

# AliyunVectorStore（DashVector）

## 概述

`AliyunVectorStore` 基于阿里云官方 `dashvector-java-sdk` 访问 DashVector，通过 `DashVectorClient` 和
`DashVectorCollection` 完成文档 upsert、更新、删除和向量检索。

Store 会把 `Document.content`、`Document.title` 和业务 metadata 保存为 DashVector Field，把
`SearchWrapper.condition` 转换为 DashVector filter，并支持按 Collection 和 Partition 路由。

DashVector 是云托管服务，适合希望减少向量数据库集群运维、且应用可以稳定访问阿里云 Endpoint 的场景。

## 使用场景

- 阿里云环境中的 RAG 知识库；
- 需要托管向量检索和 metadata 条件过滤的应用；
- 通过独立 Collection 隔离知识库或租户；
- 使用 Partition 管理版本或数据分区；
- 希望使用官方 Java SDK，而不是自行维护 HTTP 协议和响应解析。

## 本地安装

DashVector 服务端不能安装到本地 Docker 或开发机。本地开发是让应用连接阿里云上的专用测试 Cluster 和测试
Collection。

不要使用生产 Collection 进行开发测试。真实集成测试会写入、更新和删除数据，应使用独立测试环境和最小权限
API Key。

## 开通与准备

1. 在阿里云控制台开通 DashVector；
2. 创建 Cluster 并获取 Cluster Endpoint；
3. 创建测试 Collection；
4. Collection 向量维度必须与 Embedding 模型一致；
5. 确认 Collection 的距离类型；
6. 创建 API Key 并配置网络访问；
7. 如需数组 Field，按 DashVector 要求预定义 schema。

参考阿里云官方文档：

- [Java SDK 新建 Client](https://help.aliyun.com/zh/document_detail/2572893.html)
- [Java SDK 安装](https://help.aliyun.com/zh/document_detail/2510231.html)
- [条件过滤检索](https://help.aliyun.com/zh/document_detail/2513006.html)

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

模块已经传递依赖官方 `com.aliyun:dashvector-java-sdk`。宿主应用负责选择 SLF4J 实现，模块排除了 SDK 传递的
Log4j SLF4J binding，避免覆盖应用日志配置。

## 配置

```java
AliyunVectorStoreConfig config = new AliyunVectorStoreConfig();
config.setEndpoint(System.getenv("DASHVECTOR_ENDPOINT"));
config.setApiKey(System.getenv("DASHVECTOR_API_KEY"));
config.setDefaultCollectionName("knowledge");
config.setTimeout(10.0f); // 秒，-1 表示不超时

if (!config.checkAvailable()) {
    throw new IllegalStateException("DashVector 配置不完整");
}

AliyunVectorStore store = new AliyunVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

| 配置 | 必填 | 说明 |
| --- | --- | --- |
| `endpoint` | 是 | 控制台中的 Cluster Endpoint，按官方 SDK 要求填写 |
| `apiKey` | 是 | DashVector API Key |
| `defaultCollectionName` | 是 | 默认 Collection |
| `timeout` | 是 | SDK 请求超时，默认 10 秒 |
| `database` | 否 | 已废弃；DashVector Java SDK 不使用 Database |

配置 Endpoint 时不要自行拼接 `/v1/...`。Store 不再手写 HTTP URL，连接和协议由官方 SDK 管理。

### 注入官方 Client

应用已经统一创建 `DashVectorClient` 时可以直接注入：

```java
DashVectorClient client = new DashVectorClient(apiKey, endpoint);
AliyunVectorStore store = new AliyunVectorStore(config, client);
```

关闭 Store 会同时关闭注入的 Client。不要让其他组件继续使用同一个已关闭 Client。

## 快速开始

```java
Document document = Document.of("DashVector 是阿里云托管向量检索服务。");
document.setId("aliyun-001");
document.setTitle("DashVector 入门");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");
document.putMetadata("year", 2026);

StoreResult stored = store.store(document);
if (!stored.isSuccess()) {
    throw new IllegalStateException(stored.getMessage(), stored.getException());
}

SearchWrapper query = new SearchWrapper()
    .text("阿里云向量数据库")
    .maxResults(5)
    .eq("tenant", "demo");

List<Document> result = store.search(query);

document.setContent("更新后的 DashVector 文档");
store.update(document);
store.delete(document.getId().toString());
```

`store(...)` 使用 SDK upsert，稳定 ID 便于重试和幂等导入。`update(...)` 使用 SDK update；不存在 ID 和部分失败
的具体信息会通过 `StoreResult` 返回。

## 文档字段映射

| Agents-Flex | DashVector |
| --- | --- |
| `Document.id` | Doc ID，统一转换为字符串 |
| `Document.vector` | Doc vector |
| `Document.content` | `__agentsflex_content` Field |
| `Document.title` | `__agentsflex_title` Field |
| metadata | 同名 DashVector Field |
| 查询分值 | 根据 Collection metric 转换后写入 `Document.score` |

`__agentsflex_content` 和 `__agentsflex_title` 是保留字段，业务 metadata 不能使用。检索结果会恢复 title/content，
并从 metadata 中删除这两个内部字段。

### metadata 类型

当前官方 Java SDK 1.0.18 支持写入 `Integer`、`Float`、`String`、`Boolean` 和 `Long`：

- Byte、Short 自动转换为 Integer；
- Double、BigDecimal 在有限范围内转换为 Float；
- BigInteger 在 Long 范围内转换为 Long；
- null、集合和其他对象会被明确拒绝，不会静默丢字段。

同名 Field 应保持稳定类型。范围查询字段必须以数字类型写入。

## 高级查询

### SQL 风格条件

```java
SearchWrapper query = new SearchWrapper()
    .text("云向量服务")
    .condition(
        "tenant = 'tenant-a' " +
        "AND category IN ('guide', 'reference') " +
        "AND year BETWEEN 2024 AND 2026"
    );
```

条件首先转换为通用 Condition 树，再由 `AliyunExpressionAdaptor` 转换为 DashVector filter：

```text
tenant = 'tenant-a'
and category in ('guide','reference')
and (year >= 2024 and year <= 2026)
```

当前适配支持：

- `=`、`!=`、`>`、`>=`、`<`、`<=`；
- IN、NOT IN；
- BETWEEN，转换为两个范围比较；
- AND、OR 和括号；
- 字符串、数字和 Boolean 类型保持。

DashVector filter 当前不支持通用 NULL 条件和前置 NOT 分组，适配器会明确抛出异常。需要否定集合时使用
NOT IN，不要使用 `NOT (...)`。

### 纯条件查询

官方 SDK 支持不传查询向量、只使用 filter：

```java
SearchWrapper query = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "tenant-a")
    .eq("status", "published")
    .maxResults(100);
```

`withVector(false)` 控制是否执行向量检索，不再用于控制返回向量。

### 输出字段和返回向量

```java
query.outputFields("tenant", "category", "year");
query.outputVector(true);
```

- `outputFields` 映射到 SDK `outputFields`；
- Store 会额外请求内部 title/content Field，以恢复完整 Document；
- `outputVector` 映射到 SDK `includeVector`；
- 默认不返回向量，减少响应体积。

### minScore

DashVector 返回的 `score` 与 Collection metric 有关：

| Metric | Agents-Flex 转换 |
| --- | --- |
| cosine | `1 - distance / 2`，并限制在 0 到 1 |
| euclidean | `1 / (1 + max(distance, 0))` |
| dotproduct | 保留 DashVector 原始值，数值越大越相似 |

`minScore` 在结果转换后应用。不同 metric 的数值不可直接比较，更换距离类型后必须重新校准阈值。

## Collection 与 Partition

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
options.partitionName("release_2026");

store.store(documents, options);
store.search(query, options);
store.update(documents, options);
store.delete(ids, options);
```

Store 不自动创建 Collection 或 Partition。目标数据空间必须已经存在且向量维度兼容。DashVector SDK 单次请求只
接受一个 Partition；传入多个 Partition 会被 Store 拒绝。

## 错误处理

- SDK 整体失败会保留 code、message 和 request ID；
- upsert、update 和 delete 会检查每一个 `DocOpResult`，部分失败不会被当成成功；
- 写操作返回失败的 `StoreResult`；
- 查询失败抛出 `StoreException`；
- 查询空结果返回空列表，不返回 null。

日志和外部错误响应不要包含 API Key 或完整敏感文档。

## 测试与验证

### 单元测试

```bash
mvn -pl agents-flex-store/agents-flex-store-aliyun -am \
  -Dtest=AliyunVectorStoreTest,AliyunExpressionAdaptorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

单元测试直接验证官方 SDK Request/Response 映射，不需要云端凭证。

### 真实 DashVector 测试

准备独立测试 Collection，并确保维度与环境变量一致：

```bash
export DASHVECTOR_INTEGRATION_TEST=true
export DASHVECTOR_ENDPOINT="your-cluster-endpoint"
export DASHVECTOR_API_KEY="your-api-key"
export DASHVECTOR_COLLECTION="agents_flex_test"
export DASHVECTOR_DIMENSION="4"

mvn -pl agents-flex-store/agents-flex-store-aliyun -am \
  -Dtest=AliyunVectorStoreIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试会写入唯一 ID，执行条件加向量查询、更新和删除。未设置显式开关或凭证时自动跳过，避免误操作云端数据。

## 资源释放

```java
try (AliyunVectorStore store = new AliyunVectorStore(config)) {
    store.setEmbeddingModel(embeddingModel);
    // 使用 Store
}
```

Store 会关闭官方 SDK Client 和底层连接资源。

## 生产建议

- 使用专用密钥系统管理 API Key，不写入源码；
- 固定 SDK 版本，并在升级前执行真实集成测试；
- 使用稳定文档 ID 保证重试幂等；
- Collection schema、距离类型、向量维度和 Embedding 模型一起版本化；
- 对 Collection 和 Partition 名使用服务端白名单；
- 监控请求延迟、服务端 code、部分失败、配额和费用；
- 保存 request ID 便于排查阿里云服务端问题；
- 对超时和限流使用有限重试，不无限重放非幂等操作。

</div>
