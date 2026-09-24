# 查询概览

GraphQueryExecutor 提供 Portable Query、Native Query、分页、游标和执行计划入口。

## 公共查询字符串

如果业务侧需要让用户或配置文件传入查询条件，可以使用 Graph 公共查询字符串。SDK 会先通过
`GraphQueryParser` 解析成统一 AST（`TraversalQuery` 或 `GraphUnionQuery`），再交给 Neo4j、Nebula 等适配器编译；查询文本不会直接
拼接到后端语句中。

```java
Map<String, Object> parameters = new HashMap<>();
parameters.put("minAge", 18L);
parameters.put("statuses", Arrays.asList("ACTIVE", "TRIAL"));

GraphResult result = graph.query().execute(
    "MATCH (p:Person)-[:KNOWS]->(f:Person) "
        + "WHERE p.age >= :minAge AND f.status IN :statuses "
        + "RETURN p.name AS personName, f.name AS friendName "
        + "ORDER BY f.name DESC LIMIT 20",
    parameters,
    options);
```

当前公共 DSL 是有意限制的、跨后端的线性遍历子集，支持：

- `MATCH` 和单段 `OPTIONAL MATCH` 节点/边模式；多节点标签（`(:Person:Employee)`）、多边类型（`[:KNOWS|WORKS_WITH]`）、节点/边别名；`->`、`<-` 和无箭头双向遍历；有限变长路径，例如 `*1..3`；节点/边属性模式，例如 `{status: :status}`；
- `WHERE` 中的 `AND`、`OR`、`NOT`、括号、比较运算、`IN`/`NOT IN`、`BETWEEN`、`IS NULL`/`IS NOT NULL`、`CONTAINS`、`STARTS WITH`、`ENDS WITH` 和 `REGEX`；
- 字符串、整数、小数、布尔值、`NULL`、数组字面量和 `:name` 命名参数；
- `RETURN` 实体、属性或完整路径（`PATH AS route`）投影、`COUNT(*)`、`COUNT`/`COUNT(DISTINCT ...)`、`SUM`、`AVG`、`MIN`、`MAX`、`AS` 别名、`DISTINCT`；
- `GROUP BY` 和统一 AST 中的 `HAVING`；Neo4j 当前对 HAVING 明确报告不支持，Nebula 编译为原生分组过滤；
- 顶层 `UNION` / `UNION ALL`；各分支必须具有相同数量、名称和投影语义；
- `ORDER BY`、`SKIP` 和 `LIMIT`。

结构标识符（label、edge type、alias、property）必须是合法标识符，不能通过参数替换；与关键字冲突的合法标识符可使用反引号引用。
集合参数会在解析时复制并冻结，
调用方后续修改原集合不会改变已解析查询。

公共 DSL 不承诺完整支持 Cypher 或 nGQL 的子查询、写入语句、任意函数、聚合分组、路径算法和后端专属语法。需要这些能力时，
请显式使用 `NativeGraphQuery`，并在应用层隔离对应方言。

## 两种查询入口

| 入口 | 用途 | 可移植性 |
| --- | --- | --- |
| TraversalQuery | 节点、边、过滤、投影、排序、聚合 | 跨适配器 |
| NativeGraphQuery | Cypher、nGQL 或管理语句 | 绑定具体后端 |

~~~java
GraphResult result = graph.query().execute(query, options);
GraphResult nativeResult = graph.query().execute(
    NativeGraphQuery.of("MATCH (n:Person) RETURN n"),
    options);
~~~

## 查询校验

构建查询时 SDK 会校验：

- alias 是否重复；
- label、edge type、property 是否为合法标识符；
- filter 和 projection 是否引用已声明 alias；
- 聚合投影混用普通投影时是否声明了 `GROUP BY`；
- limit、skip 和 hop 范围是否有效。

查询参数作为参数绑定，避免字符串拼接产生注入风险。

## 结果保护

GraphOptions.maxRecords 默认限制物化记录数。结果元数据会说明是否截断。需要完整结果时使用分页或游标，
不要无上限提高 maxRecords。

## 查询边界

Portable Query 表达的是统一语义，不保证后端执行计划、索引选择、锁和延迟相同；后端不具备某项能力时会抛出
`UnsupportedGraphFeatureException`，而不是静默改变结果。复杂或后端专属语义应使用
Native Query，并在应用层隔离方言代码。

当前公共 AST 仍以线性遍历为核心，不支持一个查询中多个相互独立的 `MATCH` 模式或任意分支图模式；
这类查询请拆分为 `UNION` 分支，或使用 `NativeGraphQuery`。这样可以避免把不同后端对笛卡尔组合、变量作用域
和空匹配结果的差异隐藏在统一语义中。
