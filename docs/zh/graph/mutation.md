# 节点与边写入

GraphWriter 通过 GraphMutation 统一执行节点、边的 upsert 和删除。

## Upsert

~~~java
GraphMutation mutation = GraphMutation.builder()
    .upsertNode(GraphNode.builder("user-1", "Person")
        .property("name", "Alice").build())
    .upsertEdge(GraphEdge.builder("user-1", "KNOWS", "user-2")
        .rank(0).build())
    .build();

GraphWriteResult result =
    graph.writer().mutate(mutation, GraphOptions.ofSpace("neo4j"));
~~~

upsert 的幂等键由节点 ID 或边的完整 EdgeKey 决定。相同 ID 重复写入时，属性会按适配器规则更新；业务层仍
应明确哪些属性允许覆盖。

## 删除

~~~java
GraphMutation mutation = GraphMutation.builder()
    .deleteEdge(new GraphEdgeKey("user-1", "KNOWS", "user-2", 0))
    .deleteNode("user-1")
    .detachDeletedNodes(true)
    .build();
~~~

不使用 detach 时，删除仍有边连接的节点可能被后端拒绝。需要删除节点及其边时显式启用 detach，并将它视为
高风险操作。

## 写入结果

GraphWriteResult 提供成功状态、影响节点数、影响边数、message、error 和 GraphErrorCode。批量操作可能已经
完成部分批次后失败。不要把 Java 方法调用边界当作数据库事务边界，除非显式使用支持事务的后端事务对象。

## 后端注意事项

Nebula Portable Writer 当前只支持单 Tag 节点；无属性节点和边使用 IF NOT EXISTS 维持重复写入幂等性。
Nebula 在空间创建或 Schema 传播窗口内遇到可恢复的 leader 错误时会进行有限重试。

