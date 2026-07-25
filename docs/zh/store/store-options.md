<div v-pre>

# StoreOptions 与多集合

## 概述

`StoreOptions` 描述一次 Store 操作的运行参数，主要负责选择 Collection、Index、Partition，并把本次调用的
`EmbeddingOptions` 传给 Embedding 模型。

Store Config 定义实例级默认值，`StoreOptions` 定义请求级覆盖值：

```text
Store Config
  └─ 默认数据库连接、默认 Collection/Index

StoreOptions
  └─ 本次操作使用的数据空间、Partition、EmbeddingOptions
```

这使一个长期复用的 Store 实例可以安全访问多个知识库，而不需要在运行时修改 Config。

## 使用场景

### 多个业务知识库

产品手册、内部制度和客服知识分别存放在独立 Collection，查询时根据业务上下文选择目标集合。

### 多租户隔离

每个租户映射到独立 Collection 或 Index，减少过滤条件遗漏导致的跨租户访问风险。

### Milvus 分区路由

在同一个 Collection 内按版本、时间或数据域选择 Partition，减少查询范围或管理数据生命周期。

### 知识库蓝绿发布

在新 Collection 中全量构建和验证向量，完成后原子切换业务注册表，旧版本保留一段时间用于回滚。

### 按请求传递 Embedding 参数

为写入和查询使用特定的 `EmbeddingOptions`，但必须保证同一 Collection 内模型和向量空间兼容。

## 快速开始

```java
StoreOptions options =
    StoreOptions.ofCollectionName("product_manuals_2026");

store.store(documents, options);
List<Document> result = store.search(query, options);
store.update(updatedDocuments, options);
store.delete(documentIds, options);
```

四类操作必须使用相同路由。只在写入时传 options、查询时使用默认 Collection，是“数据写入成功但查不到”最常见
的原因之一。

## 属性说明

| 属性 | 作用 | 常见后端 |
| --- | --- | --- |
| `collectionName` | 覆盖默认 Collection 或逻辑集合 | Redis、Milvus、Pgvector、Chroma、Qdrant、云服务 |
| `indexName` | 覆盖默认 Index | Elasticsearch、OpenSearch |
| `partitionNames` | 指定 Collection 内的分区 | Milvus 等支持分区的实现 |
| `embeddingOptions` | 写入或查询向量化参数 | 由 `EmbeddingModel` 解释 |
| metadata | 携带自定义 Store 扩展参数 | 由扩展实现解释 |

## Collection、Index 与 Partition

三个字段对应不同数据库概念，不能为了“都有名字”而同时设置：

```java
// Collection 类型后端
StoreOptions collectionOptions =
    StoreOptions.ofCollectionName("tenant_a_docs");

// Elasticsearch / OpenSearch
StoreOptions indexOptions = new StoreOptions();
indexOptions.setIndexName("tenant-a-docs");

// Milvus Collection 内分区
StoreOptions partitionOptions =
    StoreOptions.ofCollectionName("knowledge");
partitionOptions.partitionName("release_2026");
```

设置 `collectionName` 不会切换 Elasticsearch Index；设置 `indexName` 也不会切换 Milvus Collection。

## 默认值解析

具体 Store 通常按以下方式选择物理名称：

```java
String collection = options.getCollectionNameOrDefault(defaultCollection);
String index = options.getIndexNameOrDefault(defaultIndex);
```

未设置或空字符串时回退到 Config 的默认值。未配置分区时，`getPartitionNamesOrEmpty()` 返回不可变空列表。

如果 Config 和 options 都没有提供目标名称，具体 Store 可能使用自身默认值，也可能在运行时失败，应查看独立
后端文档。

## StoreOptions.DEFAULT

不传 options 的便捷方法使用共享默认实例：

```java
store.search(query); // 等价于使用 StoreOptions.DEFAULT
```

`StoreOptions.DEFAULT` 会拒绝修改 `collectionName`、`partitionNames` 和 `embeddingOptions`：

```java
// 错误：抛出 IllegalStateException
StoreOptions.DEFAULT.setCollectionName("tenant-a");

// 正确
StoreOptions options = StoreOptions.ofCollectionName("tenant-a");
```

需要注意，当前实现没有拦截 `setIndexName(...)`，也没有冻结继承的 metadata。因此“只读”同时是一项调用约定：
应用代码不得修改 `StoreOptions.DEFAULT` 的任何状态，也不应把它作为请求上下文容器。需要 Index 或扩展参数时
始终创建新的 `StoreOptions`。

## 请求级对象与并发

`StoreOptions` 是可变对象。推荐每个逻辑请求创建一个实例，并在该请求的全部 Store 操作中复用：

```java
final StoreOptions options =
    StoreOptions.ofCollectionName(resolvedCollection);

StoreResult stored = store.store(documents, options);
List<Document> found = store.search(query, options);
```

不要把同一个实例放进全局变量后由不同线程修改 Collection 或 Partition。Store 实例可以长期复用，options 不应
作为可变的应用级单例。

## 多租户设计

### 独立 Collection 或 Index

```text
tenant-a -> tenant_a_documents
tenant-b -> tenant_b_documents
```

优点：隔离边界清晰，可以独立删除、备份和重建。缺点：租户很多时会增加 Collection、索引和 schema 管理成本。

### 共享 Collection 加 tenant 条件

```text
knowledge
├── tenant = tenant-a
└── tenant = tenant-b
```

优点：集合数量少，统一运维。缺点：每一条查询、更新和删除路径都必须强制 tenant 条件，而且目标 Store 必须真正
支持条件下推。当前未接入 `condition` 的 Store 不适合用这种方式实现安全隔离。

### 混合策略

按租户等级或区域划分多个 Collection，每个 Collection 内再使用 tenant 条件。它在集合数量和故障域之间折中，
但路由注册表和授权逻辑更复杂。

## 安全路由

物理名称必须由服务端根据已认证租户映射：

```java
String physicalName = collectionRegistry.resolve(authenticatedTenantId);
StoreOptions options = StoreOptions.ofCollectionName(physicalName);
```

不要直接把 HTTP 参数作为 Collection 或 Index 名：

- 不同数据库允许的字符和大小写规则不同；
- 恶意用户可能访问其他租户的数据空间；
- 无限制创建名称可能耗尽数据库资源；
- SQL 表名、Index 名通常不能像普通条件值一样参数化。

注册表应限制可用名称、租户归属、状态和最大集合数量。

## 防止数据串库

建议把路由解析集中在应用服务层，而不是让调用点自行拼接：

```java
public StoreOptions optionsFor(KnowledgeBase knowledgeBase) {
    String physicalName = registry.requireActiveCollection(
        knowledgeBase.getTenantId(),
        knowledgeBase.getId()
    );
    return StoreOptions.ofCollectionName(physicalName);
}
```

集成测试至少覆盖：

1. 向 A、B 两个新 Collection 写入不同唯一文档；
2. 分别查询只能看到各自数据；
3. 在 A 更新和删除，B 不受影响；
4. 不传 options 时只访问明确配置的默认 Collection；
5. 并发访问多个 Collection 时缓存和索引 schema 不互串；
6. 非法或不属于当前租户的名称被路由层拒绝。

## EmbeddingOptions

```java
StoreOptions options = StoreOptions.ofCollectionName("knowledge_v2");
options.setEmbeddingOptions(embeddingOptions);

store.store(documents, options);
store.search(query, options);
```

`DocumentStore` 会把同一个 options 中的 `EmbeddingOptions` 传给文档写入和查询文本 Embedding。它适合传递
模型支持的调用参数，但不适合在同一 Collection 内随意切换模型。

以下信息应与 Collection 版本一起管理：

- 模型提供商和模型名；
- 模型版本；
- 向量维度；
- 文本预处理和切分策略；
- 距离度量方式。

## Partition 使用建议

Partition 是 Collection 内部的数据组织方式，不自动等同于安全隔离。使用前确认：

- Store 是否支持单分区还是多分区查询；
- 未传 Partition 时查询全部还是默认分区；
- Partition 是否自动创建和加载；
- 删除 Partition 的数据生命周期；
- Partition 与 metadata 条件如何组合。

这些语义依赖后端，不能只根据 `partitionNames` 属性推断。

## 蓝绿知识库发布

```java
StoreOptions building =
    StoreOptions.ofCollectionName("knowledge_v2_building");
store.store(allDocuments, building);

// 完成数量、召回率和权限隔离验证后，原子切换注册表。
collectionRegistry.activate("knowledge", "knowledge_v2_building");

StoreOptions active = StoreOptions.ofCollectionName(
    collectionRegistry.current("knowledge")
);
List<Document> result = store.search(query, active);
```

切换时不要修改 Store Config 的默认集合。通过注册表生成请求级 options，才能实现可审计的切换和快速回滚。
旧 Collection 应在确认没有在途请求、回滚窗口结束并完成备份后再清理。

## 生产建议

- 为 Collection、Index 和 Partition 建立统一命名规范；
- Store Config 在启动后保持不变，动态路由只使用 `StoreOptions`；
- 路由来源必须经过认证、授权和白名单；
- 监控每个物理数据空间的文档数、向量维度、索引状态和容量；
- Embedding 模型升级时创建新 Collection，不混写不兼容向量；
- 在真实数据库中执行多 Collection 并发隔离测试；
- 清理数据空间前执行引用检查、延迟删除和可恢复备份。

</div>
