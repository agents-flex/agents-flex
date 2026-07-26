<div v-pre>

# Store 向量存储模块

## 概述

Agents-Flex Store 是面向文档和向量数据的统一存储层。它把不同数据库的连接方式、集合命名、数据结构、过滤
语法和相似度计算封装在具体 Store 实现中，让上层应用使用一致的 `store`、`search`、`update` 和 `delete`
接口。

Store 最常见于 RAG 知识库：文档写入时生成向量，用户提问时生成查询向量，再从数据库召回语义相近且满足
业务条件的文档。它也可以脱离 RAG 单独使用。

```text
业务文档                       用户查询
   │                              │
   ├─ 切分、生成 ID、Embedding    ├─ 文本 Embedding 或直接传入向量
   │                              │
   ▼                              ▼
DocumentStore ─────────────── SearchWrapper
   │                              │
   └──────── 具体 VectorStore ────┘
                 │
                 ▼
      Redis / Milvus / Pgvector / MariaDB / ...
```

## 使用场景

### RAG 知识库

将产品手册、规章制度、工单和网页切分后写入向量数据库。检索时同时使用语义相似度和 `tenant`、`category`、
`status` 等元数据条件，避免把无关或无权限内容交给大模型。

### 语义搜索

传统关键词搜索依赖字面匹配，向量搜索可以召回“表达不同但含义相近”的内容。例如查询“账号无法登录”时，也能
召回标题为“身份验证失败处理”的文档。

### Agent 长期记忆

把对话摘要、任务结果或用户偏好保存为文档，通过用户、会话、时间范围等条件过滤，再按语义相关度召回。

### 相似内容推荐与去重

直接传入已有向量，查找相似商品、文章或问题。写入前也可以先搜索近邻，判断内容是否重复。

### 多租户与多知识库

通过独立 Collection/Index 做物理隔离，或者在共享集合中使用 tenant 条件做逻辑隔离。两种策略的安全边界和
运维成本不同，参见 [StoreOptions 与多集合](./store-options)。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 统一 CRUD | 单条和批量写入、更新、删除与检索 |
| 自动向量化 | 文档或查询只有文本时，可调用配置的 `EmbeddingModel` 生成向量 |
| 预计算向量 | 已有向量时直接写入或查询，不重复调用 Embedding 模型 |
| 文档预处理 | 可配置 `DocumentSplitter` 和 `DocumentIdGenerator` |
| 条件过滤 | 支持链式 API、条件树和 SQL 风格条件字符串 |
| 数据路由 | 通过 `StoreOptions` 为单次操作指定 Collection、Index 或 Partition |
| 多后端适配 | Redis、Milvus、Pgvector、MariaDB、Elasticsearch、OpenSearch、Chroma、Qdrant 和云服务 |
| 自定义扩展 | 可继承 `DocumentStore` 实现新的数据库适配器 |

::: warning 统一接口不等于能力完全一致
数据库对条件语法、分值范围、更新语义、返回字段和无向量查询的支持不同。通用对象表达的是调用意图，具体能力
必须以对应 Store 文档和真实集成测试为准。
:::

## 核心概念

### Document

`Document` 是最常用的数据对象，包含 `id`、`title`、`content`、`vector`、`score` 和业务 metadata。

- 写入时通常设置正文和 metadata，向量可以由 Store 自动补充；
- 查询结果中的 `score` 表示相似程度，但不同后端的归一化规则可能不同；
- 文档 ID 是更新、删除、幂等导入和 chunk 追踪的基础。

### VectorStore 与 DocumentStore

`VectorStore<T>` 定义统一的向量数据 CRUD 接口。`DocumentStore` 在它之上增加文档切分、ID 生成和自动
Embedding。仓库内的数据库实现通常继承 `DocumentStore`。

### SearchWrapper

`SearchWrapper` 描述一次查询，包括查询文本或向量、返回数量、最低分值、元数据条件和输出控制。它不直接执行
查询，具体 Store 会把条件树转换为目标数据库语法。

### StoreOptions

`StoreOptions` 描述一次操作要访问的数据空间以及 Embedding 参数。相同 Store 实例可以通过它访问不同
Collection 或 Index，但写入、查询、更新和删除必须使用一致的路由选项。

### Collection、Index 与 Partition

这些名称不能互换：

| 概念 | 常见后端 | Agents-Flex 配置 |
| --- | --- | --- |
| Collection | Redis 逻辑集合、Milvus、Chroma、Qdrant、Pgvector/MariaDB 表 | `collectionName` |
| Index | Elasticsearch、OpenSearch | `indexName` |
| Partition | Milvus 等集合内分区 | `partitionNames` |

## 模块结构

```text
agents-flex-core
└── com.agentsflex.core.store
    ├── VectorData                    基础向量和分值
    ├── VectorStore<T>                通用 CRUD 抽象
    ├── DocumentStore                 文档预处理模板
    ├── SearchWrapper                 查询描述对象
    ├── StoreOptions                  单次操作选项
    ├── StoreResult                   写操作结果
    └── condition
        ├── Condition / Group / Not   条件树
        ├── ConditionExpressionParser 条件字符串解析器
        └── ExpressionAdaptor         后端表达式适配接口

agents-flex-store
├── agents-flex-store-redis
├── agents-flex-store-milvus
├── agents-flex-store-pgvector
├── agents-flex-store-mariadb
├── agents-flex-store-elasticsearch
├── agents-flex-store-opensearch
├── agents-flex-store-chroma
├── agents-flex-store-qdrant
├── agents-flex-store-aliyun
└── agents-flex-store-qcloud
```

## 写入数据流

调用 `store.store(documents, options)` 后，`DocumentStore` 按以下顺序处理：

1. 将空 options 规范化为 `StoreOptions.DEFAULT`；
2. 配置了 `DocumentSplitter` 时切分文档；
3. 未配置切分器时，为缺少 ID 的文档生成 ID；
4. 为缺少向量的文档调用 `EmbeddingModel`；
5. 把处理后的文档交给具体 Store 写入数据库；
6. 返回 `StoreResult`，调用方检查成功状态、异常和可用的 ID。

已有向量不会被重复生成。切分器可能把一篇原文转换为多个 chunk，因此输入文档数量不一定等于实际写入数量。

## 查询数据流

调用 `store.search(wrapper, options)` 时：

1. 如果 wrapper 没有向量、已配置 Embedding 模型且 `withVector=true`，先对查询文本生成向量；
2. 具体 Store 解析 Collection 或 Index；
3. 把 `Condition` 转换为数据库原生过滤条件；
4. 执行向量召回、阈值过滤和结果字段转换；
5. 返回 `List<Document>`。

查询失败通常直接抛出异常；写操作既可能返回失败的 `StoreResult`，也可能抛出运行时异常，业务层需要同时处理。

## 使用边界

Store 负责统一访问和常用预处理，但不负责以下工作：

- 不替代数据库本身的部署、备份、扩缩容和权限控制；
- 不保证不同数据库上的 `minScore` 数值可以直接比较；
- 不自动保证 tenant 条件出现在每一条业务查询中；
- 不负责 Embedding 模型升级后的全量重建和数据迁移；
- 不把 `StoreResult.success` 解释为所有后端完全相同的更新语义。

## 推荐阅读顺序

1. [快速开始](./getting-started)：完成一次本地写入与查询；
2. [核心 API 与数据流](./core-api)：理解通用抽象和生命周期；
3. [SearchWrapper 查询构造](./search-wrapper)：学习向量查询和条件组合；
4. [SQL 风格条件表达式](./condition-expression)：掌握动态条件语法；
5. [StoreOptions 与多集合](./store-options)：设计路由和多租户隔离；
6. [存储选型与能力矩阵](./providers)：选择后端并确认能力边界；
7. 阅读目标数据库的独立文档并执行真实集成测试。

</div>
