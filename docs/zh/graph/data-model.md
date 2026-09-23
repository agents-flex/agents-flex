# 数据模型

## GraphNode

~~~java
GraphNode node = GraphNode.builder("user-1", "Person")
    .property("name", "Alice")
    .property("age", 30L)
    .build();
~~~

节点 ID 应由业务方稳定生成。稳定 ID 是幂等 upsert、更新、删除、断点恢复和审计关联的基础。

Neo4j 支持多标签节点；Nebula Portable Writer 当前只支持一个 Tag。跨后端共享模型时，应使用单一主标签，
或者在业务层分别定义后端模型。

## GraphEdge

~~~java
GraphEdge edge = GraphEdge.builder("user-1", "KNOWS", "user-2")
    .rank(0)
    .property("since", 2020L)
    .build();
~~~

rank 用于区分同一 source、type、target 之间的平行边。删除边时必须提供完整的 GraphEdgeKey，不能只提供
起点和终点。

## 属性类型

| 类型 | Java 常见值 | 说明 |
| --- | --- | --- |
| STRING | String | UTF-8 文本 |
| BOOLEAN | Boolean | 布尔值 |
| INT64 | Long | 64 位整数 |
| DOUBLE | Double | 双精度浮点 |
| DATE | 后端支持的日期值 | 日期类型 |
| DATETIME | 后端支持的时间值 | 日期时间类型 |

不同驱动对日期、时间、列表和 Map 的参数转换不同。复杂类型应使用目标后端真实环境测试，不要只依赖
编译器单元测试。

## 标识符

标签、边类型、属性名、别名和 Graph Space 名称需要符合 SDK 的可移植标识符规则。用户输入若要用于这些
结构位置，应先校验或映射为内部名称；用户输入的值则应作为查询参数绑定。

## 结果模型

查询返回的表格结果使用 GraphResult、GraphRecord；当结果中包含节点、边或路径时，可以通过
GraphSubgraphResult 读取结构化子图。应用不应直接依赖 Neo4j Driver 或 Nebula Client 的实体类型。

