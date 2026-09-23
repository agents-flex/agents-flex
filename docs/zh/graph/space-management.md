# 空间管理

空间管理由 GraphManager 提供。它属于控制面，通常在应用启动、租户开通或迁移任务中执行。

## 创建空间

~~~java
GraphSpaceDefinition definition = GraphSpaceDefinition.builder("tenant_graph")
    .partitionCount(1).replicaFactor(1).build();
graph.manager().createSpace(definition, GraphManager.CreateMode.IF_ABSENT);
~~~

CreateMode：

| 模式 | 行为 |
| --- | --- |
| IF_ABSENT | 不存在时创建，存在时复用 |
| FAIL_IF_EXISTS | 已存在时失败 |
| VALIDATE_ONLY | 只检查，不执行 DDL |

## 列出和检查空间

~~~java
List<String> spaces = graph.manager().listSpaces();
boolean exists = graph.manager().spaceExists("tenant_graph");
~~~

空间存在不一定表示已经可以立即写入。Nebula 的 MetaD、GraphD、StorageD 之间存在传播窗口，应用应等待
数据面就绪后再执行 Schema 和写入。

## 删除空间

~~~java
graph.manager().dropSpace("tenant_graph");
~~~

删除空间是破坏性操作。生产服务应在上层执行权限校验、二次确认、审计和备份策略。SDK 不负责恢复被删除
的数据。

## 后端映射

| SDK 概念 | Neo4j | Nebula |
| --- | --- | --- |
| Graph Space | database | Space |
| 创建参数 | database name | partition / replica |
| 空间传播 | 取决于数据库状态 | MetaD 到 StorageD 的异步传播 |

Neo4j Community Edition 不支持通过 SDK 创建和删除数据库；调用会转换为 UnsupportedGraphFeatureException，并在
能力说明中标注 Enterprise 限制。

