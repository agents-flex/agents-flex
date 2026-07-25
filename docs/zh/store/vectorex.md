<div v-pre>

# VectoRexStore（远程服务）

## 概述

`agents-flex-store-vectorex` 使用 `vectorex-client` 连接独立运行的 VectoRex 服务端。Store 可以在首次写入时
创建 Collection，使用余弦距离向量字段，并通过 HTTP Client 完成 CRUD 和向量查询。

它与嵌入式 `agents-flex-store-vectorexdb` 是两个不同模块，虽然 Java 包名和类名相同。

## 本地安装

Agents-Flex 仓库只包含 VectoRex 客户端适配，不包含服务端镜像、Compose 或启动脚本，因此这里不提供未经验证
的 Docker 命令。服务端请按照 [VectoRex 上游仓库](https://github.com/javpower/VectoRex) 对目标版本的说明安装。

安装时需要确认：

1. 服务端版本与模块当前依赖的 `vectorex-client 1.5.3` 兼容；
2. 服务 URI 和认证方式；
3. 数据目录、持久化和备份；
4. 服务端监听地址没有直接暴露到公网；
5. 能通过服务端提供的接口列出 Collection。

如果只是本机试用且不需要远程服务，优先使用 [VectoRexDB](./vectorexdb) 嵌入式模块。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-vectorex</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 配置

```java
VectoRexStoreConfig config = new VectoRexStoreConfig();
config.setUri(System.getenv("VECTOREX_URI"));
config.setUsername(System.getenv("VECTOREX_USERNAME"));
config.setPassword(System.getenv("VECTOREX_PASSWORD"));
config.setDefaultCollectionName("knowledge");
config.setAutoCreateCollection(true);

if (!config.checkAvailable()) {
    throw new IllegalStateException("VectoRex URI 不能为空");
}

VectoRexStore store = new VectoRexStore(config);
store.setEmbeddingModel(embeddingModel);
```

`checkAvailable()` 当前只检查 URI，不会验证凭证或远程健康状态。应用启动检查应额外执行受控的服务探测。

## 快速开始

```java
Document document = Document.of("远程 VectoRex 文档");
document.setId("vectorex-001");

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("VectoRex 向量检索")
    .maxResults(5);

List<Document> result = store.search(query);

document.setContent("更新后的文档");
store.update(document);
store.delete(document.getId());
```

自动创建 Collection 时通过 `EmbeddingModel.dimensions()` 设置向量维度，因此必须在首次写入前配置模型，或
确保实现可以从预计算向量推断维度。

## Collection 路由

```java
StoreOptions options = StoreOptions.ofCollectionName("tenant_a_knowledge");
store.store(documents, options);
store.search(query, options);
```

当前实现内部用一个 `isCreateCollection` 布尔值记录创建状态，而不是按 Collection 缓存。一个 Store 实例动态
切换多个 Collection 时，必须通过真实测试确认每个目标 Collection 已存在；更稳妥的做法是预建 Collection 或
为不同固定 Collection 使用独立 Store 实例。

## 高级查询

当前远程 VectoRex 查询只使用向量和 `maxResults`，没有把 `SearchWrapper.condition`、`minScore`、
`outputFields`、`outputVector` 转换为客户端查询参数。

```java
query.eq("tenant", "tenant-a"); // 当前不会形成服务端过滤
```

不能用它作为共享 Collection 的租户安全条件。需要 metadata 过滤时先扩展 QueryBuilder 映射并添加真实测试。

## 测试与验证

1. 用客户端列出 Collection；
2. 写入唯一 ID 文档；
3. 查询并核对 ID、content 和 score；
4. 更新后再次查询；
5. 删除后确认不再返回；
6. 使用两个 Collection 验证创建和隔离；
7. 重启服务端验证持久化。

## 生产建议

- 固定 VectoRex 服务端和 client 兼容版本；
- 预建 Collection 并验证维度；
- 对 URI、认证、网络和数据目录做安全配置；
- 当前条件能力不足时不要共享 Collection 做多租户；
- 应用关闭时管理底层 `VectorRexClient.close()`；
- 监控服务端容量、查询延迟和失败响应。

</div>
