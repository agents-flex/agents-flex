# Neo4j 适配器

Neo4j 适配器位于 agents-flex-graph-neo4j，基于官方 Java Driver 实现 GraphStore。它把 GraphOptions.space 映射为 Neo4j database。

## 连接配置

~~~java
Neo4jGraphStoreConfig config = new Neo4jGraphStoreConfig()
    .setUri("bolt://localhost:7687")
    .setUsername("neo4j")
    .setPassword("password")
    .setDefaultSpace("neo4j");

try (GraphStore store = new Neo4jGraphStore(config)) {
    GraphHealth health = store.health();
}
~~~

URI、用户名、密码和默认数据库由配置对象持有。生产环境应通过密钥管理系统注入密码，并使用 TLS URI 和最小权限账号。

## database 与空间

Neo4j 的逻辑空间对应 database。GraphManager.createSpace、spaceExists、listSpaces 和 dropSpace 会执行数据库管理操作，但能否成功取决于 Neo4j 版本、部署模式和账号权限。

Neo4j Community Edition 通常不能通过 SDK 创建或删除 database。能力声明仍会列出 CREATE_SPACE 和 DROP_SPACE，因为 Enterprise Edition 在具备管理员权限时可以支持；调用方必须把版本和权限错误展示为部署问题，不能假设所有 Neo4j 安装都可自动建库。

## Schema 和标签

Neo4j 支持节点多标签、普通索引和唯一约束。GraphSchema 的节点类型和边类型会编译为标签、关系类型及索引或约束。Schema 反查可以读取索引和约束，但关系端点标签在通用模型中可能只能返回 unconstrained，因此反查结果的 isComplete 需要被认真检查。

破坏性 DDL 不应放入应用每次启动流程。推荐使用 validateSchema、GraphSchemaDiff 和 GraphSchemaMigrationPlan 生成预览，再在发布流程中执行。

## 查询、事务与游标

Neo4j 支持 Cypher 原生查询、有限变长路径、Explain、显式事务和驱动流式游标。GraphTransaction 的 close 会回滚尚未提交的事务。

~~~java
try (GraphTransaction tx = store.transactions().begin(GraphOptions.ofSpace("neo4j"))) {
    tx.writer().upsert(node, GraphOptions.ofSpace("neo4j"));
    GraphResult check = tx.query().execute(readQuery, GraphOptions.ofSpace("neo4j"));
    tx.commit();
}
~~~

Neo4j 的 timeoutMillis 会转换为事务超时。它限制的是数据库事务执行，不等同于应用线程、网络连接或所有驱动等待阶段的全链路硬超时。

使用 executeCursor 时必须在 finally 或 try-with-resources 中关闭游标：

~~~java
try (GraphResultCursor cursor = store.query().executeCursor(query, GraphOptions.ofSpace("neo4j"))) {
    while (cursor.hasNext()) {
        GraphRecord row = cursor.next();
    }
}
~~~

## 批量导入

当前 SDK 暴露的是在线事务批次导入，能力模式为 ONLINE_BATCH，不封装 neo4j-admin 的离线导入工具。大规模离线装载应由部署脚本或独立数据工程流程负责，并在导入完成后通过 GraphManager 校验 Schema 和抽样查询。

## 运维检查

- 使用 store.health() 检查驱动连通性；
- 检查数据库是否存在、账号是否拥有目标 database 权限；
- 检查索引和约束是否与 GraphSchema 一致；
- 监控连接池、事务超时、锁等待和查询计划；
- 关闭 GraphStore 以释放 Driver 和异步导入执行器。

相关章节：[空间管理](/zh/graph/space-management)、[事务](/zh/graph/transaction)、[生产使用建议](/zh/graph/production-guidelines)。
