<div v-pre>

# SearchWrapper 查询构造

## 概述

`SearchWrapper` 是一次检索请求的描述对象。它同时表达“用什么向量召回”“最多返回多少条”“应用哪些 metadata
条件”以及“结果需要哪些字段”。

它本身不连接数据库，也不保证所有参数都被所有 Store 支持。`DocumentStore` 负责补充查询向量，具体 Store
负责把 wrapper 转换为目标数据库请求。

## 使用场景

### 语义检索

传入查询文本，由 Store 调用 `EmbeddingModel` 生成查询向量，再返回语义最相近的文档。

### 向量检索加业务过滤

在相似度召回基础上限定 tenant、状态、分类、时间范围或权限标签。这是生产 RAG 中最常见的查询方式。

### 使用预计算查询向量

查询向量已经由批处理、缓存或其他服务生成时，直接设置 vector，避免重复 Embedding。

### 纯 metadata 查询

关闭 `withVector` 后只提交条件。该模式不是所有 Store 都支持，不能把它当成统一的数据库扫描 API。

## 快速开始

```java
SearchWrapper query = new SearchWrapper()
    .text("如何部署向量数据库")
    .maxResults(10)
    .minScore(0.65)
    .eq("tenant", "tenant-a")
    .in("category", Arrays.asList("guide", "reference"))
    .ge("year", 2025)
    .outputVector(false);

List<Document> documents = store.search(query);
```

这条查询表示：对文本生成向量，在满足三个 metadata 条件的文档中召回最多 10 条，并尝试过滤低于 0.65 的
结果，同时不要求返回原始向量。

## 属性说明

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `text` | `null` | 待 Embedding 的查询文本 |
| `vector` | `null` | 直接提供的查询向量，继承自 `VectorData` |
| `maxResults` | `4` | 最大返回数量 |
| `minScore` | `null` | 最低相似度阈值 |
| `withVector` | `true` | 是否准备并执行向量查询 |
| `condition` | `null` | metadata 条件树 |
| `outputFields` | `null` | 希望返回的字段 |
| `outputVector` | `false` | 是否返回结果向量 |

## 查询文本与查询向量

### 使用文本

```java
SearchWrapper query = new SearchWrapper()
    .text("多租户知识库隔离")
    .maxResults(8);
```

当 vector 为空、Store 已配置 `EmbeddingModel` 且 `withVector=true` 时，`DocumentStore` 会自动对 text 执行
Embedding。

### 直接使用向量

```java
SearchWrapper query = new SearchWrapper().maxResults(8);
query.setVector(queryVector);
```

同时设置 text 和 vector 时，已有 vector 优先，不会再次对 text 进行 Embedding。向量必须与目标 Collection 的
模型和维度一致。

## 返回数量与最低分值

```java
query.maxResults(20).minScore(0.70);
```

- `maxResults` 类似查询 LIMIT，但候选数和实际返回数还受后端实现影响；
- `minScore` 的范围和执行位置由 Store 决定，可能在服务端过滤，也可能在结果转换时过滤；
- 余弦距离、内积和 L2 的原始数值不同，各 Store 可能进行归一化。

因此不要把 Redis 上校准出的 `0.70` 直接用于 Milvus、Elasticsearch 或其他实现。

## 链式条件

### 比较运算

```java
SearchWrapper query = new SearchWrapper()
    .eq("tenant", "tenant-a")
    .ne("status", "deleted")
    .gt("views", 100)
    .ge("rating", 4.0)
    .lt("retryCount", 3)
    .le("year", 2026);
```

不指定连接符时，每个条件使用 `AND` 追加。

### IN、NOT IN 与 BETWEEN

```java
query.in("category", Arrays.asList("guide", "reference"));
query.nin("status", Arrays.asList("deleted", "blocked"));
query.between("year", 2024, 2026);
```

集合不应为空，BETWEEN 上下界类型应一致。字段值类型必须与数据库中的 metadata 类型匹配。

### 显式连接符

```java
query.eq(Connector.OR, "category", "reference");
query.eq(Connector.AND_NOT, "visibility", "private");
```

复杂逻辑优先使用分组，不建议依赖不同数据库表达式对未分组 AND/OR 的隐式优先级。

## 条件分组

例如业务条件为：tenant 必须匹配，并且文档满足“公开”或“由当前用户拥有”：

```java
SearchWrapper query = new SearchWrapper()
    .eq("tenant", "tenant-a")
    .andCriteria(group -> group
        .eq("visibility", "public")
        .eq(Connector.OR, "ownerId", currentUserId)
    );
```

对应逻辑：

```text
tenant = 'tenant-a'
AND (visibility = 'public' OR ownerId = 'current-user')
```

可用的分组方法：

| 方法 | 行为 |
| --- | --- |
| `group(...)` | 使用 AND 追加分组 |
| `andCriteria(...)` | `group(...)` 的语义化别名 |
| `orCriteria(...)` | 使用 OR 追加分组 |

Consumer 没有创建任何条件时，不会添加空分组。

## SQL 风格条件字符串

动态规则或配置化查询可以使用：

```java
SearchWrapper query = new SearchWrapper()
    .eq("tenant", "tenant-a")
    .condition(
        "year >= 2025 " +
        "AND category NOT IN ('hidden', 'deleted')"
    );
```

字符串会先解析成 `Condition` 树，再作为一个分组用 AND 追加，不会把原始字符串直接交给数据库。

| 方法 | 对现有条件的影响 |
| --- | --- |
| `condition(expression)` | 使用 AND 追加解析后的分组 |
| `condition(connector, expression)` | 使用指定连接符追加分组 |
| `setConditionExpression(expression)` | 替换全部已有条件 |
| `setCondition(condition)` | 直接替换为已有条件树 |

解析发生在修改 wrapper 之前。表达式非法时会抛出异常，已有条件保持不变。完整语法参见
[SQL 风格条件表达式](./condition-expression)。

## 输出控制

```java
query.outputFields("id", "title", "category", "year");
query.outputVector(true);
```

`outputVector=true` 会增加数据库响应、网络传输和 JVM 内存占用，只在向量调试、迁移或二次计算时开启。
`outputFields` 和 `outputVector` 是否能下推到服务端取决于 Store；部分实现会忽略这些参数。

## 纯条件查询

```java
SearchWrapper filterOnly = new SearchWrapper()
    .withVector(false)
    .eq("tenant", "tenant-a")
    .eq("status", "published")
    .maxResults(100);
```

`withVector(false)` 的直接作用是阻止 `DocumentStore` 自动生成查询向量。具体 Store 仍需要实现无向量查询路径。
如果后端只实现了 KNN 搜索，这段代码可能失败或不符合预期。

## 条件与数据路由的区别

`SearchWrapper` 决定“在目标数据空间内查什么”，`StoreOptions` 决定“访问哪个数据空间”：

```java
SearchWrapper query = new SearchWrapper()
    .text("部署说明")
    .eq("tenant", "tenant-a");

StoreOptions options = StoreOptions.ofCollectionName("knowledge_2026");
store.search(query, options);
```

tenant 条件不能自动替代 Collection 隔离，Collection 名也不能表达 metadata 条件。

## 后端兼容性

在切换 Store 前逐项确认：

1. 是否接入 `condition`；
2. 是否支持 IN、NOT IN、BETWEEN、NULL 和嵌套逻辑；
3. `minScore` 的计算和过滤位置；
4. 是否支持 `withVector(false)`；
5. 是否执行 `outputFields` 和 `outputVector`；
6. metadata 字段路径和类型如何映射。

详情见[存储选型与能力矩阵](./providers)和各后端独立文档。

## 生命周期与安全建议

- `SearchWrapper` 是可变对象，每次请求新建，不在并发线程间共享；
- 不要复用并手工连接同一个 `Condition` 根节点；
- tenant、权限等安全条件应由服务端根据认证上下文强制添加；
- 不允许客户端任意控制内部字段名或绕过固定安全条件；
- 对用户输入的表达式限制长度和复杂度，并在执行前完成解析；
- 在真实数据库上测试特殊字符、NULL、混合类型和嵌套条件。

</div>
