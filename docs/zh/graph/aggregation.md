# 聚合查询

TraversalQuery 支持统一聚合投影。聚合查询只能包含聚合投影，不能把普通属性投影混在同一查询中充当隐式
分组。

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

## 分组边界

当前 Portable AST 没有通用 GROUP BY 投影模型。需要按属性分组、窗口函数或复杂统计时，使用 Native Query，
或者先查询明细记录在应用层聚合。

