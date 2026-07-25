<div v-pre>

# QCloudVectorStore（腾讯云向量数据库）

## 概述

`QCloudVectorStore` 通过腾讯云向量数据库 HTTP API 执行文档 upsert、删除、更新和向量搜索。它适合已经使用
腾讯云、希望采用托管向量数据库的业务。

这是云托管服务，不能在本地安装。开发环境需要访问云端测试实例或独立测试 Collection。

## 本地安装

腾讯云向量数据库是云托管服务，服务端不能安装到本地 Docker 或开发机。本地开发需要连接云端测试实例，且应
使用独立 Database 或 Collection，避免测试写入影响生产数据。

## 开通与准备

1. 在腾讯云控制台创建向量数据库实例；
2. 创建 Database 和 Collection；
3. 设置与 Embedding 模型一致的维度和距离类型；
4. 获取 host、account 和 API Key；
5. 配置安全组、私网或公网访问；
6. 为联调建立独立测试 Collection。

服务开通和 API 以[腾讯云官方文档](https://cloud.tencent.com/document/product/1709/95121)为准。

## 本地联调环境变量

```bash
export QCLOUD_VECTOR_HOST="https://your-vector-endpoint"
export QCLOUD_VECTOR_ACCOUNT="your-account"
export QCLOUD_VECTOR_API_KEY="your-api-key"
```

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-qcloud</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
QCloudVectorStoreConfig config = new QCloudVectorStoreConfig();
config.setHost(System.getenv("QCLOUD_VECTOR_HOST"));
config.setAccount(System.getenv("QCLOUD_VECTOR_ACCOUNT"));
config.setApiKey(System.getenv("QCLOUD_VECTOR_API_KEY"));
config.setDatabase("knowledge_db");
config.setDefaultCollectionName("knowledge");

if (!config.checkAvailable()) {
    throw new IllegalStateException("腾讯云向量数据库配置不完整");
}

QCloudVectorStore store = new QCloudVectorStore(config);
store.setEmbeddingModel(embeddingModel);
```

## 快速开始

```java
Document document = Document.of("腾讯云向量数据库文档");
document.setId("qcloud-001");
document.putMetadata("tenant", "demo");
document.putMetadata("category", "guide");

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("腾讯云向量检索")
    .maxResults(5);

List<Document> result = store.search(query);

document.putMetadata("status", "published");
store.update(document);
store.delete(document.getId());
```

## 更新语义

当前更新请求按文档 ID 更新 metadataMap，不会像某些 Store 一样完整覆盖 content 和 vector。业务使用更新前应
通过真实 API 验证字段行为；需要替换向量时可根据服务语义使用 upsert。

## 高级查询

当前搜索请求发送：

- database；
- collection；
- 单个查询 vector；
- `maxResults` 对应的 limit。

`SearchWrapper.condition`、`minScore`、`outputFields` 和 `outputVector` 当前没有接入请求。以下代码虽然能够构造，
但条件不会被当前 QCloud Store 使用：

```java
query.eq("tenant", "tenant-a");
```

因此不能依赖 Condition 做多租户安全隔离。需要服务端过滤时，应扩展腾讯云请求的 filter/query 参数并为全部
条件类型增加真实集成测试。

## 多 Collection

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
```

Database 固定来自 Config，Collection 可按调用覆盖。跨 Database 使用不同 Store 实例。

## 测试与验证

- 用唯一测试 ID，避免覆盖生产数据；
- 验证 API 返回 code/msg，而不仅是 HTTP 200；
- 确认搜索响应中的 metadata 和 score 字段；
- 测试不存在 ID 的更新和删除；
- 测试两个 Collection 的写入与查询隔离；
- 检查应用日志没有 Authorization 头。

## 生产建议

- 使用私网连接和最小权限 API Key；
- 不在当前条件能力下使用共享 Collection 做租户隔离；
- 监控 API 错误码、延迟、限流和配额；
- 对网络失败采用有界重试和稳定 ID；
- Collection schema、Embedding 模型和应用版本同步发布。

</div>
