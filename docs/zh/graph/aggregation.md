# 聚合查询

TraversalQuery 支持统一聚合投影，以及显式 `GROUP BY` 分组。混合普通属性投影和聚合投影时，普通属性必须
出现在 `GROUP BY` 中；实体投影不能与聚合投影混用。

## 计数

~~~java
TraversalQuery query = TraversalQuery
    .from(TraversalQuery.NodePattern.node("person", "Person"))
    .select(TraversalQuery.Projection.count("person", "total"))
    .build();
~~~

## 属性聚合

~~~java
TraversalQuery query = TraversalQuery
    .from(TraversalQuery.NodePattern.node("person", "Person"))
    .select(
        TraversalQuery.Projection.aggregate(
            TraversalQuery.AggregateFunction.AVG,
            "person", "age", "averageAge"))
    .build();
~~~

支持 COUNT、COUNT_DISTINCT、SUM、AVG、MIN 和 MAX。不同数据库对 null、整数除法、浮点精度和空集合的结果
可能不同，重要指标应在目标后端建立真实数据测试。

## 分组与 HAVING

~~~java
TraversalQuery query = TraversalQuery
    .from(TraversalQuery.NodePattern.node("person", "Person"))
    .select(
        TraversalQuery.Projection.property("person", "city", "city"),
        TraversalQuery.Projection.count("person", "total"))
    .groupBy(new TraversalQuery.GroupKey("person", "city"))
    .build();
~~~

字符串 DSL 对应 `GROUP BY person.city`。Neo4j 使用 Cypher 的隐式聚合分组；Nebula 3.8 的 `MATCH` 语法不支持
`GROUP BY`，SDK 会显式抛出 `UnsupportedGraphFeatureException`，需要使用原生 nGQL 或在应用层聚合。
`HAVING` 已进入统一 AST；Neo4j 当前不能安全地把任意 HAVING 条件移植为 Cypher，因此会显式抛出
`UnsupportedGraphFeatureException`，需要改写为聚合前 `WHERE` 或使用原生 Cypher。

窗口函数、复杂统计和后端专属聚合仍应使用 Native Query，或者先查询明细记录在应用层聚合。
