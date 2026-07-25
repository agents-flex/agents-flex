<div v-pre>

# AliyunVectorStore（DashVector）

## 概述

`AliyunVectorStore` 通过阿里云 DashVector HTTP API 完成文档写入、更新、删除和向量查询。它适合希望使用
托管向量数据库、避免自行维护集群的阿里云用户。

DashVector 是云服务，不能在本地 Docker 中安装。开发机通过公网或专线访问已开通的服务实例。

## 本地安装

DashVector 是云托管服务，服务端不能安装到本地 Docker 或开发机。所谓“本地使用”是让本地应用连接云端测试
实例。不要用生产实例和生产 Collection 进行开发联调。

## 开通与准备

1. 在阿里云控制台开通 DashVector；
2. 创建 Cluster/Endpoint；
3. 创建数据库和 Collection；
4. Collection 向量维度必须与 Embedding 模型一致；
5. 创建访问凭证并限制权限；
6. 确认开发机或应用网络可以访问 Endpoint。

控制台和字段 schema 会随服务版本变化，以
[DashVector 官方文档](https://help.aliyun.com/document_detail/2510317.html)为准。

## 本地联调准备

把凭证放到环境变量：

```bash
export DASHVECTOR_ENDPOINT="your-endpoint"
export DASHVECTOR_API_KEY="your-api-key"
```

Endpoint 配置只填写主机部分。当前 Store 会自动拼接 `https://` 和 `/v1/...` 路径。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-aliyun</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
AliyunVectorStoreConfig config = new AliyunVectorStoreConfig();
config.setEndpoint(System.getenv("DASHVECTOR_ENDPOINT"));
config.setApiKey(System.getenv("DASHVECTOR_API_KEY"));
config.setDatabase("knowledge_db");
config.setDefaultCollectionName("knowledge");

if (!config.checkAvailable()) {
    throw new IllegalStateException("DashVector 配置不完整");
}

AliyunVectorStore store = new AliyunVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

## 快速开始

```java
Document document = Document.of("DashVector 是阿里云托管向量检索服务。");
document.setId("aliyun-001");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");

StoreResult stored = store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("阿里云向量数据库")
    .maxResults(5)
    .eq("tenant", "demo");

List<Document> result = store.search(query);

document.setContent("更新后的内容");
store.update(document);
store.delete(document.getId());
```

## 高级查询

当前实现使用默认 `ExpressionAdaptor` 生成过滤字符串并传给 DashVector `filter`：

```java
SearchWrapper query = new SearchWrapper()
    .text("云向量服务")
    .condition(
        "tenant = 'tenant-a' " +
        "AND category IN ('guide', 'reference') " +
        "AND year >= 2025"
    );
```

默认表达式不等于针对 DashVector 完整定制的语法适配器。上线前必须对每一种业务条件使用真实服务验证字段类型、
引号、IN、BETWEEN 和逻辑分组。

### 当前查询字段行为

- `maxResults` 映射为 `topk`；
- 查询向量映射为 `vector`；
- 当前实现用 `withVector` 填充服务端 `include_vector`；
- `minScore` 和 `outputFields` 当前没有进入请求；
- 服务端返回距离会转换为较大值更相似的 score。

需要严格输出控制或阈值过滤时，应扩展适配器或在业务层二次过滤，并添加真实测试。

## 多 Collection

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
```

Store 不自动通过控制台创建 Collection。目标 Collection 必须存在且 schema 兼容。

## 测试与验证

建议使用专用测试 Collection：

1. 写入带唯一 ID 和 tenant 标记的文档；
2. 无条件搜索确认向量召回；
3. 逐项加入过滤条件；
4. 更新同 ID 文档并确认内容；
5. 删除并确认不可检索；
6. 在第二个 Collection 重复测试隔离性。

## 生产建议

- 使用 RAM/STS 或密钥系统管理凭证，不写入源码；
- 监控配额、QPS、延迟、错误码和费用；
- 使用稳定文档 ID 保证重试幂等；
- 为网络超时和限流配置有限重试；
- 保存服务端 request ID 便于工单排查；
- Collection schema 和 Embedding 模型版本一起管理。

</div>
