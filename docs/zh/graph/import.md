# 批量导入

GraphWriter.importData 提供在线、分批、节点优先的导入语义。

~~~java
GraphImportRequest request = GraphImportRequest.builder()
    .nodes(nodes).edges(edges).batchSize(500).stopOnError(true).build();
GraphImportReport report =
    graph.writer().importData(request, GraphOptions.ofSpace("neo4j"));
~~~

## 执行顺序

SDK 先消费节点 Iterable，再消费边 Iterable。这样可以满足多数图数据库创建边前必须存在端点的要求。
调用方应保证 Iterable 可重复消费，特别是在使用恢复点重新提交时。

## 批次和失败策略

GraphImportReport 区分 nodesImported、edgesImported、batchesCompleted、batchesFailed、batchesAttempted
和 errorDetails。

stopOnError 为 true 时，第一批失败后停止并抛出异常。为 false 时，失败批次会记录在报告中，后续批次继续执行。
这不表示失败批次自动回滚。

## 断点和数据源

GraphImportSource 可把外部数据流接入统一请求；GraphImportCheckpoint 可保存成功批次的节点、边偏移。
恢复时使用 GraphImportRequest.resumeFrom，调用方必须保证数据顺序和 ID 语义一致。

SDK 不负责 CSV、Excel、消息队列或对象存储解析。解析、去重、租户隔离和重试策略应在应用导入层实现。

