# 遍历查询

TraversalQuery 用线性模式描述从起点节点沿边到目标节点的查询。

## 基本遍历

~~~java
TraversalQuery query = TraversalQuery
    .from(TraversalQuery.NodePattern.node("person", "Person"))
    .traverse(
        TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT),
        TraversalQuery.NodePattern.node("friend", "Person"))
    .select(TraversalQuery.Projection.entity("friend"))
    .build();
~~~

Direction 支持 OUT、IN 和 BOTH。每个 alias 必须唯一，节点和边 alias 不能重复。

## 多跳和变长路径

~~~java
TraversalQuery path = TraversalQuery
    .from(TraversalQuery.NodePattern.node("start", "Person"))
    .traverse(
        TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT)
            .hops(1, 3),
        TraversalQuery.NodePattern.node("end", "Person"))
    .select(TraversalQuery.Projection.path("route"))
    .build();
~~~

SDK 对 Portable variable-length traversal 设置最大跳数限制。后端允许更长路径时，也不能绕过公共 API 的
限制；需要专属语义时使用 Native Query 并自行设置安全上限。

## 投影

支持：

- entity：完整节点或边；
- property：单个属性；
- path：完整路径；
- aggregate：聚合值。

排序属性在 Nebula 上必须出现在投影中，因为 nGQL 的 ORDER BY 需要使用返回列。

## 分页和去重

TraversalQuery 可以配置 skip、limit 和 distinct。生产分页应配合稳定 orderBy，否则数据变化期间不同页可能
出现重复或遗漏。

