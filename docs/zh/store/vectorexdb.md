<div v-pre>

# VectoRexStore（嵌入式 VectoRexDB）

## 概述

`agents-flex-store-vectorexdb` 使用 `vectorex-core` 在当前 JVM 内保存和检索向量，不需要启动远程服务。它适合
本地工具、Demo、单机原型和小规模嵌入式场景。

嵌入式方案与应用进程共享 CPU、内存、磁盘和生命周期，不等同于独立数据库服务。

## 本地安装

不需要 Docker。VectoRexDB 作为 Java 库运行，数据与应用位于同一台机器。

## 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-store-vectorexdb</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

该模块当前传递使用 `vectorex-core 1.5.3`。数据格式兼容性应随版本升级测试。

## 配置

```java
Path dataDirectory = Paths.get("./data/vectorexdb")
    .toAbsolutePath()
    .normalize();
Files.createDirectories(dataDirectory);

VectoRexStoreConfig config = new VectoRexStoreConfig();
config.setUri(dataDirectory.toString());
config.setDefaultCollectionName("knowledge");
config.setAutoCreateCollection(true);

VectoRexStore store = new VectoRexStore(config);
store.setEmbeddingModel(embeddingModel);
```

`uri` 传给上游 `VectoRexClient`。使用绝对、非临时、应用可写的目录，并通过实际重启测试确认目标版本的落盘
语义。不要把数据库目录放在会被容器重建清除的文件系统中。

## 快速开始

```java
Document document = Document.of("嵌入式 VectoRexDB 文档");
document.setId("local-001");

store.store(document);

SearchWrapper query = new SearchWrapper()
    .text("本地向量检索")
    .maxResults(5);

List<Document> result = store.search(query);

document.setContent("更新后的本地文档");
store.update(document);
store.delete(document.getId());
```

首次自动创建 Collection 时，向量维度取 `EmbeddingModel.dimensions()`。

## 多 Collection

```java
StoreOptions options = StoreOptions.ofCollectionName("project_a");
store.store(documents, options);
store.search(query, options);
```

与远程模块相同，当前实现使用单个 `isCreateCollection` 标记，不按 Collection 记录创建状态。动态切换多个
Collection 前应预建并验证，或为不同 Collection 创建独立 Store 实例。

## 高级查询

当前嵌入式检索调用只使用查询向量和 `maxResults`，不应用：

- `SearchWrapper.condition`；
- `minScore`；
- `outputFields`；
- `outputVector`。

需要 metadata 条件、多租户强隔离、远程并发访问或高可用时，应选择其他 Store。

## 测试与验证

```bash
mvn -pl agents-flex-store/agents-flex-store-vectorexdb test
```

建议额外编写重启测试：第一次 JVM 写入并关闭，第二次 JVM 使用相同 URI 查询，确认数据和 Collection schema
能够恢复。

## 与远程模块不能同时使用

`agents-flex-store-vectorex` 和 `agents-flex-store-vectorexdb` 都提供：

```text
com.agentsflex.store.vectorex.VectoRexStore
com.agentsflex.store.vectorex.VectoRexStoreConfig
```

不要在同一应用同时依赖两个模块，否则会出现重复类和不可预测的类加载结果。

## 生产建议

- 仅用于符合上游容量和并发边界的单机场景；
- 数据目录使用持久磁盘并纳入备份；
- 同一目录避免多个 JVM 无协调并发写入；
- 固定依赖版本并测试升级兼容性；
- 定期验证恢复，不把“文件存在”等同于“备份可用”；
- 多节点、复杂过滤和严格租户隔离优先选择服务型数据库。

</div>
