# 事务

GraphTransaction 提供绑定到同一数据库事务的 writer 和 query。

~~~java
GraphTransaction tx =
    graph.transactions().begin(GraphOptions.ofSpace("neo4j"));
try {
    tx.writer().mutate(mutation, GraphOptions.ofSpace("neo4j"));
    GraphResult inside = tx.query().execute(query, GraphOptions.ofSpace("neo4j"));
    tx.commit();
} catch (RuntimeException error) {
    tx.rollback();
    throw error;
} finally {
    tx.close();
}
~~~

## 生命周期

- begin：创建事务和绑定资源；
- query / writer：只允许在事务打开时使用；
- commit：提交并关闭；
- rollback：回滚并关闭；
- close：幂等释放资源，未提交事务由适配器处理。

事务对象关闭后继续调用会失败。不要把事务对象跨请求、跨线程或跨异步任务传递。

## 可见性

Neo4j 事务内的写入可以被同一事务查询看到；提交前，其他会话通常不可见；提交后才对其他会话可见。
具体隔离级别和锁行为由数据库负责。

Nebula SessionPool 当前不提供统一显式事务。调用 graph.transactions() 会明确抛出
UnsupportedGraphFeatureException。需要原子业务操作时，应使用 Nebula 原生能力或上层补偿流程，并在能力矩阵中
明确展示限制。

