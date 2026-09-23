# 原生查询

统一查询用于跨 Neo4j 和 Nebula 的可移植场景。当业务确实需要后端方言能力时，可以使用 NativeGraphQuery，把 Cypher 或 nGQL 作为明确的逃生口。

## 构造查询

~~~java
Map<String, Object> parameters = new LinkedHashMap<>();
parameters.put("tenant", "tenant-a");

NativeGraphQuery query = NativeGraphQuery.of(
    "MATCH (n:Person {tenant: $tenant}) RETURN n.id AS id, n.name AS name",
    parameters,
    GraphQueryKind.READ);

GraphResult result = graph.query().execute(query, GraphOptions.ofSpace("neo4j"));
~~~

NativeGraphQuery 会复制并冻结参数映射。参数值必须通过参数映射传递，不要把用户输入拼接进语句；标签、属性名、数据库名等结构标识符也应由应用层白名单校验。

## 查询意图

GraphQueryKind 有四种值：

| 类型 | 用途 | 只读选项 |
| --- | --- | --- |
| READ | 查询节点、边或聚合结果 | 允许 |
| WRITE | 创建、更新、删除数据 | 拒绝 |
| SCHEMA | 创建索引、标签或边类型 | 拒绝 |
| ADMIN | 数据库或空间管理 | 拒绝 |

GraphOptions.readOnly(true) 只允许执行声明为 READ 的原生查询。这个标记是调用方的安全声明，SDK 不会尝试解析任意方言文本来推断真实意图，因此权限系统仍应在上层实现。

~~~java
GraphOptions readOnly = GraphOptions.builder()
    .space("neo4j")
    .readOnly(true)
    .build();

graph.query().execute(
    NativeGraphQuery.of("CREATE (n:Person {id: $id})",
        Collections.singletonMap("id", "u-1"), GraphQueryKind.WRITE),
    readOnly); // 抛出 GraphException
~~~

## Neo4j 与 Nebula 语句

Neo4j 原生语句使用 Cypher，空间名在 GraphOptions 中映射为 database：

~~~java
NativeGraphQuery cypher = NativeGraphQuery.of(
    "MATCH (a:Person)-[r:KNOWS]->(b:Person) RETURN a, r, b",
    Collections.emptyMap());
~~~

Nebula 原生语句使用 nGQL，空间名在 GraphOptions 中映射为 Nebula space：

~~~java
NativeGraphQuery ngql = NativeGraphQuery.of(
    "MATCH (a:person)-[e:knows]->(b:person) RETURN a, e, b",
    Collections.emptyMap());
~~~

同一段文本不能在两个后端之间复用。需要跨后端运行时，应优先使用 TraversalQuery、GraphFilter、聚合和 Union 等公共 DSL；只有确实没有公共语义时才分支选择原生语句。

## 原生查询的审计边界

GraphResult.getQueryText() 会返回适配器实际执行的查询文本，便于调试和审计。不要把参数中的密码、令牌或个人信息写入日志。生产系统建议记录：

- 连接名称、空间、租户和请求 ID；
- GraphQueryKind、耗时、记录数和是否截断；
- 后端错误码和能力检查结果；
- 脱敏后的语句摘要，而不是完整敏感参数。

原生查询无法获得统一的跨后端语义保证。上线前应分别在目标数据库版本上执行契约测试，并把数据库升级纳入回归测试。

## 适用与不适用

适合使用原生查询的场景包括最短路径、复杂路径谓词、后端专有函数和数据库管理语句。不适合用原生查询替代所有公共 API，否则会失去能力矩阵、参数约束、统一分页和后端切换价值。

相关章节：[查询概览](/zh/graph/query-overview)、[Explain 执行计划](/zh/graph/explain)、[后端能力对比](/zh/graph/backend-comparison)。
