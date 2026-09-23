# 快速开始

本章使用 Neo4j 展示一次完整的 Graph SDK 流程：创建 Store、应用 Schema、写入节点和边、执行统一遍历查询，
最后关闭连接。Nebula 的调用结构相同，差异集中在连接配置、Space、Schema 约束和能力矩阵。

## 前置条件

- JDK 8 或更高版本；
- Maven 3.6 或更高版本；
- 可访问的 Neo4j 5.x 或 Nebula Graph 3.x；
- 已准备数据库账号和密码。

~~~bash
docker run --name agents-flex-neo4j \
  -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/password \
  -d neo4j:5.26-community
~~~

## 添加依赖

~~~xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-neo4j</artifactId>
    <version>\${VERSION}</version>
</dependency>
~~~

## 创建 Store

~~~java
Neo4jGraphStoreConfig config = new Neo4jGraphStoreConfig()
    .setUri("bolt://127.0.0.1:7687")
    .setUsername("neo4j")
    .setPassword("password")
    .setDefaultSpace("neo4j");

try (GraphStore graph = new Neo4jGraphStore(config)) {
    if (!graph.health().isUp()) {
        throw new IllegalStateException("graph database is not reachable");
    }
}
~~~

GraphStore 实现是可关闭资源。生产代码应使用 try-with-resources，或者在应用生命周期结束时显式调用
close()。不要在每次查询时重复创建 Store；连接池和 Driver 应在进程内复用。

## 应用 Schema

~~~java
GraphSchema schema = GraphSchema.builder()
    .nodeType(GraphSchema.NodeType.of("Person",
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
        new GraphSchema.Property("age", GraphSchema.PropertyType.INT64, false)))
    .edgeType(GraphSchema.EdgeType.any("KNOWS",
        new GraphSchema.Property("since", GraphSchema.PropertyType.INT64, false)))
    .build();

graph.manager().applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);
~~~

Schema 应在启动流程或独立迁移流程中执行，而不是在每一次写入前执行。ADDITIVE 只负责创建缺失定义或兼容
的增量属性；删除和类型变化需要单独审批。

## 写入节点和边

~~~java
GraphNode alice = GraphNode.builder("user-1", "Person")
    .property("name", "Alice").property("age", 30L).build();
GraphNode bob = GraphNode.builder("user-2", "Person")
    .property("name", "Bob").property("age", 28L).build();
GraphEdge knows = GraphEdge.builder("user-1", "KNOWS", "user-2")
    .property("since", 2020L).build();

GraphWriteResult result = graph.writer().mutate(
    GraphMutation.builder().upsertNodes(Arrays.asList(alice, bob))
        .upsertEdge(knows).build(), GraphOptions.ofSpace("neo4j"));
if (!result.isSuccess()) {
    throw new IllegalStateException(result.getMessage(), result.getError());
}
~~~

节点 ID、边类型、标签和属性名应使用稳定、可移植的标识符。边的通用身份由起点、类型、终点和 rank
共同组成，因此同一对端点可以保存多条不同 rank 的平行边。

## 执行统一查询

~~~java
TraversalQuery query = TraversalQuery
    .from(TraversalQuery.NodePattern.node("person", "Person"))
    .traverse(
        TraversalQuery.EdgePattern.edge("knows", "KNOWS", TraversalQuery.Direction.OUT),
        TraversalQuery.NodePattern.node("friend", "Person"))
    .where(GraphFilter.eq("person", "name", "Alice"))
    .select(TraversalQuery.Projection.property("friend", "name", "name"))
    .limit(20).build();

GraphResult result = graph.query().execute(query, GraphOptions.ofSpace("neo4j"));
for (GraphRecord record : result.getRecords()) {
    System.out.println(record.get("name"));
}
~~~

统一查询会由后端编译器转换为 Cypher 或 nGQL。需要使用后端专属语法时，再使用 NativeGraphQuery，并接受
查询不可移植的事实。

## 接下来阅读

1. [架构与核心概念](./architecture)
2. [数据模型](./data-model)
3. [空间管理](./space-management)
4. [Schema 定义](./schema)
5. [统一查询](./query-overview)

