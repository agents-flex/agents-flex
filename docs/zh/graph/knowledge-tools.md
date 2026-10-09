# 渐进式知识检索 Tools

`agents-flex-graph-tools` 将 Graph SDK 的 Portable Query 暴露为一组适合 Agent 调用的只读 Tool。模型先了解
知识源和类型摘要，再按需读取少量属性定义，最后执行受限制的图查询，避免把完整 Schema 一次性放入上下文。

## Maven 依赖

~~~xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-tools</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
~~~

应用仍需引入实际使用的 Graph 后端，例如 `agents-flex-graph-neo4j` 或 `agents-flex-graph-nebula`。

## 创建知识源

知识源是显式允许模型访问的逻辑入口。它把模型可见名称映射到一个固定 GraphStore、Space 和暴露 Schema，
不会把 `listSpaces()` 返回的所有物理空间自动交给模型。

~~~java
KnowledgeGraphSource source = KnowledgeGraphSource
    .builder("company_knowledge", graphStore, exposedSchema)
    .description("企业中的人员、组织和任职关系")
    .space("company_graph")
    .defaultPageSize(20)
    .maxPageSize(100)
    .maxHops(3)
    .timeoutMillis(10_000L)
    .build();

List<Tool> tools = KnowledgeGraphTools.builder()
    .addSource(source)
    .buildTools();

prompt.addTools(tools);
~~~

`KnowledgeGraphSource` 不拥有 GraphStore 生命周期。应用容器仍应负责长期复用和关闭 GraphStore。

`exposedSchema` 不只是提示信息，也是查询与结果的允许列表。需要隐藏的节点、关系或属性不要加入这份 Schema。
推荐复用应用发布和知识抽取时使用的声明式 Schema；不要默认使用数据库 Schema 反查结果，因为反查可能不完整，
也可能包含不应暴露给模型的结构。

## 渐进式调用流程

模块构建三个 Tool：

1. `listKnowledgeGraphTypes`：列出指定知识源的节点、关系、关系端点和简短说明，不返回属性。
2. `describeKnowledgeGraphTypes`：仅展开本次查询需要的节点和关系属性；默认一次最多展开 10 个类型。
3. `queryKnowledgeGraph`：解析、校验并执行单个线性 Portable `MATCH` 查询。

第一个 Tool 的描述中只嵌入显式注册的逻辑知识源名称和描述。模型不能直接选择连接、物理 Space 或后端方言。

## 查询约束

`queryKnowledgeGraph` 当前只接受可解析为 `TraversalQuery` 的单个线性 `MATCH` 查询，并具有以下限制：

- 不接受 Cypher、nGQL 等 Native Query，也不接受写入和管理语句；
- 不接受 `OPTIONAL MATCH` 和 `UNION`；
- 每个节点必须声明暴露的 label，每条关系必须声明暴露的 edge type；
- label、edge type、属性、关系端点和方向都必须与暴露 Schema 一致；
- 最大跳数、页大小、超时和 Space 由知识源配置控制；
- 动态值使用 `:name` 命名参数，通过 `parameters` JSON 对象传入；
- 查询文本省略 `SKIP` 和 `LIMIT`，使用 Tool 的 `pageSize` 和 `cursor` 分页。

Tool 会强制构造 `readOnly=true` 的 GraphOptions。返回结果不包含后端生成的 Cypher/nGQL、连接信息或物理查询
诊断。整节点和路径结果还会按 `exposedSchema` 再次过滤标签和属性，避免数据库中的额外字段随实体投影泄露。

## 分页结果

查询成功时返回 JSON，其中包含：

- `knowledgeSourceName`：逻辑知识源名称；
- `records`：当前页投影记录；
- `metadata.recordCount`：当前页记录数；
- `metadata.truncated`：是否还有未返回记录；
- `metadata.executionTimeMillis`：执行耗时；
- `metadata.hasNext` 和 `metadata.nextCursor`：下一页状态。

请求下一页时，使用完全相同的查询和参数，并把上一页的 `nextCursor` 原样传回。cursor 与查询结构绑定，不能跨
查询复用或自行解析。

## 软失败

工具参数、Schema 和查询错误会返回 `Error: CODE: message`，让模型能够调整参数后重试。稳定错误码包括
`UNKNOWN_KNOWLEDGE_SOURCE`、`UNKNOWN_GRAPH_TYPE`、`INVALID_QUERY`、`QUERY_NOT_ALLOWED`、
`UNSUPPORTED_QUERY`、`QUERY_TIMEOUT` 和 `QUERY_FAILED`。

后端详细异常只写入服务日志，不直接返回模型。生产环境仍应使用只读数据库账号、最小权限、独立 Space 或数据库
进行租户隔离；Tool 的提示词和 Schema 校验不能代替数据库授权。
