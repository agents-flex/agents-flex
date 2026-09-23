# Graph 图数据库模块

Graph 模块为属性图数据库提供统一 Java API，覆盖图空间管理、Schema、节点和边写入、分批导入、异步导入任务、遍历查询、原生查询、连接探活与事务边界。

## 模块划分

```text
agents-flex-graph-api       公共模型和接口
agents-flex-graph-extractor 文档分段、候选知识抽取、校验和实体归一
agents-flex-graph-neo4j     Neo4j Java Driver 适配器
agents-flex-graph-nebula    Nebula Graph SessionPool 适配器
```

公共 API 不依赖任何数据库客户端。后端通过 `GraphCapabilities` 声明能力；调用不支持的功能会抛出
`UnsupportedGraphFeatureException`，不会静默退化为全图扫描。

### 公共 API 包结构

根包 `com.agentsflex.graph` 只保留 `GraphStore`、`GraphOptions` 和公共异常。其他 API 按业务能力组织：

```text
com.agentsflex.graph
├── capability    后端能力及限制说明
├── connection    多连接注册与健康检查
├── data          节点、边和边标识
├── manager       图空间管理入口
├── schema        Schema 定义、反查、比较和迁移计划
├── query         查询 DSL、执行器和结果
├── mutation      写入命令、写入器和结果
├── importing     导入请求、报告和异步任务
├── transaction   事务接口
└── identifier    可移植标识符规则
```

Neo4j 和 Nebula 适配器根包仅保留 `GraphStore` 与配置类，具体实现继续按 `manager`、`query`、
`mutation` 和 `transaction` 分包。公共 API 不依赖任何适配器实现。

## 快速开始

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-neo4j</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

```java
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.GraphStore;
import com.agentsflex.graph.data.GraphNode;
import com.agentsflex.graph.query.GraphFilter;
import com.agentsflex.graph.query.GraphResult;
import com.agentsflex.graph.query.TraversalQuery;
import com.agentsflex.graph.neo4j.Neo4jGraphStore;
import com.agentsflex.graph.neo4j.Neo4jGraphStoreConfig;

Neo4jGraphStoreConfig config = new Neo4jGraphStoreConfig()
    .setUri("bolt://localhost:7687")
    .setUsername("neo4j")
    .setPassword("password");

try (GraphStore graph = new Neo4jGraphStore(config)) {
    graph.writer().upsert(
        GraphNode.builder("u-1", "Person").property("name", "Alice").build(),
        GraphOptions.DEFAULT);

    TraversalQuery query = TraversalQuery.from(
            TraversalQuery.NodePattern.node("person", "Person"))
        .traverse(
            TraversalQuery.EdgePattern.edge(
                "knows", "KNOWS", TraversalQuery.Direction.OUT),
            TraversalQuery.NodePattern.node("friend", "Person"))
        .where(GraphFilter.eq("person", "tenant", "tenant-a"))
        .select(TraversalQuery.Projection.entity("friend"))
        .limit(20)
        .build();

    GraphResult result = graph.query().execute(query, GraphOptions.DEFAULT);
}
```

## 统一查询语义

`TraversalQuery` 描述节点标签、边类型、方向、有限跳数、属性过滤、投影、聚合、排序和分页。值始终作为参数传递，标签、边类型、属性名和别名只允许可移植标识符。聚合投影支持 `COUNT`、`COUNT_DISTINCT`、`SUM`、`AVG`、`MIN` 和 `MAX`，适配器会将其编译为目标数据库方言。

需要使用后端特性时显式使用 `NativeGraphQuery`。Neo4j 传入 Cypher，Nebula 传入 nGQL；原生查询不承诺跨后端可移植。

## 写入与导入

`GraphMutation` 支持节点/边 upsert 和按稳定业务 ID 删除。节点 ID 由业务方提供，边的通用身份是
`sourceId + type + targetId + rank`。`GraphImportRequest` 会先导入节点，再导入边，并按批次返回 `GraphImportReport`。

管理后台不应长时间阻塞 HTTP 请求，可以通过 `GraphStore.imports()` 提交异步任务：

```java
GraphImportTask task = graph.imports().submit(request, GraphOptions.ofSpace("neo4j"));
GraphImportTask latest = graph.imports().get(task.getId());
```

任务状态包含 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED` 和 `CANCELLED`。当前默认实现是进程内任务执行器，
适用于单实例应用和 SDK 场景；需要跨实例恢复、持久化进度或分布式调度时，产品服务应实现自己的任务存储和执行器。

导入请求也可以使用 `GraphImportSource` 接入任意外部数据流，并通过 `GraphImportListener` 监听批次和状态，
通过 `GraphImportCheckpoint` 保存自己的偏移量。`GraphImportTaskStore` 是任务快照存储扩展点，SDK 默认提供
`InMemoryGraphImportTaskStore`，开发者可以替换为自己的持久化实现；SDK 不负责数据源解析和任务调度。
如果需要在进程重启后继续处理，调用方可以持久化 `GraphImportCheckpoint` 的偏移，并通过
`GraphImportRequest.resumeFrom(...)` 重新提交同一顺序的数据源；任务调度、重复数据处理和最终一致性仍由上层负责。

Schema 应在应用启动或迁移流程中显式执行：

```java
graph.manager().applySchema("neo4j", schema, GraphManager.SchemaMode.ADDITIVE);
```

在执行 DDL 前，可以比较期望状态与实际状态并展示迁移预览：

```java
GraphSchemaDiff diff = GraphSchemaComparator.compare(expectedSchema, actualSchema);
if (diff.hasDestructiveChanges()) {
    // 要求更高权限或人工确认
}
```

`GraphSchemaComparator` 只负责结构化比较，不会自动读取数据库或执行 DDL。后续的产品服务应负责 Schema introspection、
版本存储、审批和迁移执行。

### Schema 反查与迁移预览

可以从后端读取当前 Schema，再与应用声明的期望 Schema 比较：

```java
GraphSchemaInspection actual = graph.manager().inspectSchema("neo4j");
GraphSchemaMigrationPlan plan = GraphSchemaMigrationPlanner.plan(expected, actual.getSchema());
if (!actual.isComplete()) {
    // 反查存在后端能力差异，先展示 warnings，再决定是否允许迁移。
}
if (plan.requiresApproval()) {
    // 删除或结构变更应进入人工审批，而不是随应用启动自动执行。
}
```

`GraphSchemaInspection` 会明确返回完整性和 warning。Neo4j 当前无法从关系数据中可靠推断端点标签，
Nebula 当前不返回可移植的索引元数据，因此这些结果必须按“不完整反查”处理。历史数据库中包含连字符等
不可移植名称时，适配器会跳过对应项并保留 warning，不会让整个连接配置页面失败。

`GraphSchema` 构建时会拒绝重复的节点标签、边类型、类型内属性、索引名和索引字段，避免比较器或 DDL
编译器因为 Map 覆盖而产生静默错误。

Schema 还可以携带版本、显示名称、属性默认值和枚举等开发工具元数据；这些元数据不会被适配器强行编译
为数据库 DDL。`GraphManager.applySchemaResult(...)` 提供结构化成功、警告、错误码和耗时结果，原有
`applySchema(...)` 仍保持兼容。

### 多连接注册表

直接使用 SDK 时，`new GraphConnectionRegistry()` 默认拥有连接生命周期：移除或关闭注册表会关闭
对应 `GraphStore`。Spring Boot Starter 会自动创建一个不拥有生命周期的注册表，并按 Bean 名称注册所有
已启用的后端：

```java
@Autowired
private GraphConnectionRegistry graphConnections;

GraphStore neo4j = graphConnections.require("neo4jGraphStore");
GraphStore nebula = graphConnections.require("nebulaGraphStore");
Map<String, GraphHealth> health = graphConnections.health();
```

这样可以同时启用 Neo4j 和 Nebula，而不要求业务代码按 `GraphStore` 类型做有歧义的注入。应用自定义
`GraphConnectionRegistry` Bean 时，Starter 不会覆盖它。

### 能力矩阵与结果保护

使用 `store.capabilities().matrix()` 可取得所有统一能力的支持状态和后端限制说明；例如 Nebula 的多标签、
统一事务、可移植唯一索引和单查询硬超时均存在限制。调用不支持的能力会抛出
`UnsupportedGraphFeatureException`，不会静默退化。

`GraphOptions.maxRecords` 默认限制一次查询最多物化 10,000 条记录。`GraphResult.getMetadata()` 会返回实际
记录数、是否因上限截断以及执行耗时，适合在管理后台展示“结果不完整”提示。`timeoutMillis` 会下推到 Neo4j
事务；Nebula 的 SessionPool 客户端没有等价的单查询超时参数，不能把它宣传为同等级别的硬超时。

`GraphQueryExecutor` 提供可关闭的 `GraphResultCursor` 契约；默认实现包装物化结果，支持原生流式游标的适配器
可以覆写该入口。Neo4j 适配器使用驱动原生流式结果，调用方必须关闭游标；Nebula SessionPool 当前返回物化结果，能力矩阵会明确这一限制。`GraphSubgraphResult` 用节点/边结构表达图探索结果，和面向表格的 `GraphRecord` 分离。
原生查询通过 `GraphQueryKind` 标记 READ、WRITE、SCHEMA 或 ADMIN 意图；`GraphOptions.readOnly(true)` 会拒绝
非只读原生查询。执行计划可通过 `explain(...)` 获取，Neo4j 返回计划树，Nebula 返回 EXPLAIN 行；后端不支持时会明确抛出能力异常。
`GraphPageRequest` 和 `GraphPageResult` 提供统一 offset 分页及 opaque 下一页 token；后端可以覆写为更高效的 keyset 分页。
默认分页执行器会在请求页大小之外多取一条记录来判断是否存在下一页，并在返回前移除这条探测记录；opaque
token 会交给支持它的适配器解释，通用默认实现会明确报告不支持。
多个独立遍历分支可以通过 `GraphUnionQuery.union(...)` 或 `unionAll(...)` 组合；最短路径等后端差异较大的语义仍通过 `NativeGraphQuery` 使用，并会在能力矩阵中明确标记。

异步导入任务快照除了状态和累计报告，还暴露已成功确认的节点、边偏移以及 `getResumePoint()`，调用方可以把
该恢复点持久化后重新提交同一顺序的数据源。失败批次不会推进 checkpoint；取消或关闭服务时，SDK 会幂等释放
通过 `GraphImportSource` 提供的外部资源。`GraphImportReport` 同时提供成功、失败和尝试批次数，避免上层自行从错误列表推导统计。

能力矩阵除了支持状态和说明，还可以提供参数化限制（如最大跳数）以及支持模式（如 `ONLINE_BATCH`）。
错误统一提供 `GraphErrorCode`，调用方不应依赖具体数据库异常文本判断错误类别。

生产环境不建议把破坏性 Schema 删除放入自动初始化。数据库部署、TLS、备份、权限和高可用仍由数据库运维负责。

## 产品控制面边界

当前 Graph 模块是可嵌入的 SDK 和后端适配器，已经提供连接探活、Schema 读写/反查、统一查询、批量和进程内
异步导入等基础契约。面向最终用户的产品仍应在独立控制面实现：租户与 RBAC、凭据加密和轮换、持久化/分布式
导入任务、审计日志、Schema 版本和审批流、REST API、Schema 设计器、数据映射导入向导、图探索器、查询编辑器
以及执行计划展示。控制面可以把本模块作为执行层，并将 `GraphCapabilities`、`GraphSchemaInspection` 和
`GraphResultMetadata` 直接映射为 UI 的能力提示、风险确认和结果状态。

## 健康检查与超时

`GraphStore.health()` 会执行真实的后端连通性探测，返回状态、后端名称、耗时和错误说明，适合用于连接配置页面的
“测试连接”操作。Neo4j 查询和写入会将 `GraphOptions.timeoutMillis` 下推为事务超时；Nebula SessionPool 客户端
没有等价的单查询超时参数，因此当前无法提供同级别的强超时保证。

## Spring Boot

Starter 提供以下配置前缀：

```yaml
agents-flex:
  graph:
    neo4j:
      enabled: true
      uri: bolt://localhost:7687
      username: neo4j
      password: password
      default-space: neo4j
    nebula:
      enabled: true
      host: 127.0.0.1
      port: 9669
      username: root
      password: nebula
      default-space: agents_flex
```

## 文档导航

第一次接入建议依次阅读：

1. [快速开始](/zh/graph/getting-started)：完成连接、Schema、写入和查询闭环；
2. [架构设计](/zh/graph/architecture)与[核心概念](/zh/graph/concepts)：理解 SDK 边界和统一模型；
3. [数据模型](/zh/graph/data-model)、[GraphStore](/zh/graph/graph-store)与[GraphOptions](/zh/graph/graph-options)：掌握公共 API；
4. [Schema 定义](/zh/graph/schema)、[节点与边写入](/zh/graph/mutation)与[查询概览](/zh/graph/query-overview)：实现主要业务流程；
5. [后端能力对比](/zh/graph/backend-comparison)：处理 Neo4j 与 Nebula 的能力差异。

准备生产上线时，继续阅读[单元测试与契约测试](/zh/graph/testing)、[Docker 真实环境测试](/zh/graph/docker-integration-testing)、
[故障排查](/zh/graph/troubleshooting)和[生产使用建议](/zh/graph/production-guidelines)。

需要从小说、报告等非结构化文档构建图谱时，阅读[从文档抽取知识图谱](/zh/graph/knowledge-extraction)。
