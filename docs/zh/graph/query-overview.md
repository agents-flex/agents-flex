# 查询概览

GraphQueryExecutor 提供 Portable Query、Native Query、分页、游标和执行计划入口。

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
- 聚合投影是否混用了非聚合投影；
- limit、skip 和 hop 范围是否有效。

查询参数作为参数绑定，避免字符串拼接产生注入风险。

## 结果保护

GraphOptions.maxRecords 默认限制物化记录数。结果元数据会说明是否截断。需要完整结果时使用分页或游标，
不要无上限提高 maxRecords。

## 查询边界

Portable Query 表达的是统一语义，不保证后端执行计划、索引选择、锁和延迟相同。复杂或后端专属语义应使用
Native Query，并在应用层隔离方言代码。

