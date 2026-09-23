# 结果模型

Graph 查询结果同时支持表格投影和图结构表达。调用方可以根据使用场景选择最合适的模型，不必把所有结果强行转换成 JSON 字符串。

## GraphResult

GraphResult 是 GraphQueryExecutor.execute 的返回值，包含：

~~~java
GraphResult result = graph.query().execute(query, options);
List<GraphRecord> rows = result.getRecords();
String queryText = result.getQueryText();
GraphResultMetadata metadata = result.getMetadata();
GraphSubgraphResult subgraph = result.getSubgraph();
~~~

- records 是只读的投影记录列表；
- queryText 是适配器实际执行的语句，适合调试和审计；
- metadata 包含记录数、截断标志、耗时和下一页 token；
- subgraph 在结果中包含 GraphNode 或 GraphEdge 时提供结构化子图，否则可能为 null。

## GraphRecord

GraphRecord 是一行列名到值的只读映射。列名来自 Projection、NativeGraphQuery 的返回别名或后端结果列名。

~~~java
for (GraphRecord row : result.getRecords()) {
    Object id = row.get("id");
    Object name = row.get("name");
}
~~~

不要假设所有后端都会为未命名表达式生成相同列名。统一查询应显式设置投影别名；原生查询也应使用稳定的 AS 别名。

## GraphSubgraphResult

图探索器通常需要节点和边集合，而不是行列数据。GraphSubgraphResult 会按节点业务 ID 去重，并按 GraphEdgeKey 去重，保持首次出现顺序：

~~~java
GraphSubgraphResult graphPart = result.getSubgraph();
if (graphPart != null) {
    List<GraphNode> nodes = graphPart.getNodes();
    List<GraphEdge> edges = graphPart.getEdges();
}
~~~

节点或边嵌套在 Map、Iterable 或子图结果中时也会被收集。去重不代表业务上的等价判断；边仍由 sourceId、type、targetId 和 rank 共同确定。

## 截断与安全限制

GraphOptions.maxRecords 默认限制物化记录数。达到上限时，metadata.isTruncated() 为 true，调用方必须向用户提示结果不完整。不要把截断结果当成全量结果继续进行统计或导出。

分页执行器会多取一条记录探测下一页，然后通过 GraphResult.forPage 移除探测记录。GraphResult.withNextCursor 可在不修改原对象的情况下生成带 token 的结果。

## 数据类型与空值

节点、边和属性值由适配器转换为 Java 类型。跨后端时，应用应优先使用字符串、布尔、整数、浮点数、时间和字符串列表等公共类型，并对 Nebula 与 Neo4j 的时间、数值精度和空值行为编写契约测试。

结果对象及其集合是快照或只读视图。需要缓存或异步传递时，应复制业务需要的字段，不要依赖底层驱动对象的生命周期。
