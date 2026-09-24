# 节点与边写入

## 概述

图数据写入的目标不是向一张表追加一行，而是持续维护一张由实体和关联组成的网络。一次业务变化可能同时包含：更新人物信息、建立人物与组织的关系、删除已经失效的关系，以及清理不再存在的实体。

Graph SDK 使用 `GraphNode`、`GraphEdge` 和 `GraphMutation` 表达这些变化，再由 `GraphWriter` 转换为目标图数据库的写入语句。应用不需要分别拼接 Cypher 或 nGQL，但仍然需要负责实体身份、关系身份、Schema 兼容性和业务幂等。

如果类比 MySQL，可以把一次 `GraphMutation` 粗略理解为一组有固定执行顺序的 `DELETE`、`INSERT ... ON DUPLICATE KEY UPDATE` 操作。不过，图写入还必须处理边的两个端点、关系方向、平行边和删除节点时的关联边，因此不能直接套用表记录写入的思维。

## 适用场景

统一写入能力适合以下场景：

- 用户在业务后台新增或修改一个实体；
- 审核人员修正实体属性或关系；
- 消息消费程序把一条业务事件同步到图中；
- 知识抽取流程提交一个文档片段产生的节点和边；
- 定时任务撤销过期关系，或删除已经失效的实体；
- 应用把多项相关变化组合成一次变更提交。

当数据量很大、需要分批处理、观察进度或从中断位置恢复时，应使用[批量导入](/zh/graph/import)。当多项读写必须共同成功或共同失败时，应先确认后端能力，再使用[事务](/zh/graph/transaction)。

## 写入前需要明确什么

### 目标 Space

每次写入都发生在一个确定的 Graph Space 中。可以通过 `GraphOptions.ofSpace(...)` 显式指定；未指定时使用适配器配置的默认 Space。

生产系统不应直接信任浏览器或外部请求传入的 Space 名称。上层服务应根据租户、知识库或项目进行可信路由，并确保同一次业务操作不会因默认配置变化而写入另一空间。

### Schema 已经就绪

写入前应确保节点类型、边类型和属性已经符合目标 Space 的 [Schema](/zh/graph/schema)。这一点对 Nebula 尤其重要：Tag、Edge Type 和属性必须先创建并完成传播，写入才会成功。

Schema 声明不能代替写入校验。必填属性、枚举范围、边的合法端点类型和业务唯一性，仍应在进入 `GraphWriter` 前由应用或导入流程验证。

### 稳定的节点 ID

节点 ID 是重复写入、关系连接、删除和增量更新的基础。一个好的节点 ID 应满足：

- 同一业务实体长期保持不变；
- 不依赖名称、标题等可编辑字段；
- 不使用数据库会话内生成的临时 ID；
- 在同一 Space 内不会与其他实体冲突；
- 可以由数据源主键或确定性归一规则重复计算。

例如，企业数据可以使用 `company:91310000...`，文档可以使用 `document:<source>:<externalId>`。知识抽取场景还需要实体归一策略，避免“北京大学”和“北大”被永久写成两个无关节点。

建议把节点 ID 设计为 Space 内全局唯一，并保持主标签稳定。Neo4j 适配器按“主标签 + `__agentsflex_id`”合并节点，而 Nebula 直接以 VID 标识顶点；更严格的公共约定可以避免迁移后出现身份歧义。

## 节点 Upsert

Upsert 表示“存在则更新，不存在则创建”。下面的写入可以安全地用于同一实体的后续增量更新：

~~~java
GraphNode person = GraphNode.builder("person:1001", "Person")
    .property("name", "Alice")
    .property("status", "ACTIVE")
    .build();

GraphWriteResult result = graph.writer().upsert(
    person,
    GraphOptions.ofSpace("company_knowledge"));
~~~

`GraphNode` 包含三个核心部分：

| 内容 | 作用 |
| --- | --- |
| `id` | 标识同一个业务实体，是 Upsert 和关系端点的基础 |
| `labels` | 描述节点类型；至少需要一个标签 |
| `properties` | 保存实体自身的属性 |

### 属性更新语义

当前公共写入语义是“更新请求中出现的属性”，没有出现的旧属性通常会保留。因此，下面的写入只表达状态变化，不应被理解为用一份完整快照替换节点：

~~~java
GraphNode statusPatch = GraphNode.builder("person:1001", "Person")
    .property("status", "INACTIVE")
    .build();
~~~

跨后端代码不应依赖 `null` 删除属性，因为 Neo4j 与 Nebula 对空值、可空属性和属性删除的处理并不等价。需要删除属性或执行完整快照替换时，应在应用层明确计算差异，并使用后端专属能力或经过验证的迁移流程。

## 边 Upsert

边表示两个节点之间有方向的关系：

~~~text
(person:1001)-[WORKS_FOR]->(company:2001)
~~~

可以这样写入：

~~~java
GraphEdge worksFor = GraphEdge.builder(
        "person:1001", "WORKS_FOR", "company:2001")
    .rank(0)
    .property("role", "Architect")
    .property("since", 2024L)
    .build();

GraphWriteResult result = graph.writer().upsert(
    worksFor,
    GraphOptions.ofSpace("company_knowledge"));
~~~

边的稳定身份由 `GraphEdgeKey` 决定：

~~~text
(sourceId, type, targetId, rank)
~~~

四项完全相同时，重复写入会更新同一条边；任意一项不同，都会被视为另一条边。

### rank 的作用

`rank` 用于区分起点、类型和终点相同的多条平行边，默认值为 `0`。如果业务上同一人物可以多次加入同一组织，可以为每次任职分配稳定 rank；如果业务只允许一条当前关系，则始终使用 `0` 更容易保持幂等。

rank 必须可以稳定重建。不要使用当前时间或随机数临时生成，否则同一批数据重试时会不断创建新关系。若多个历史事件本身需要被单独查询和关联，也可以把事件建模为节点，而不是无限增加平行边。

### 先有节点，再写边

写入边前应确保两个端点已经存在。当前适配器不会为了写边自动创建缺失节点：Neo4j 的端点匹配不到时不会创建关系，Nebula 也不应依赖悬空边行为来补偿错误的数据顺序。

## 组合一次图变更

`GraphMutation` 可以把节点、边的删除和 Upsert 放在一个有序变更中：

~~~java
GraphMutation mutation = GraphMutation.builder()
    .operationId("customer-update-20260924-0001")
    .deleteEdge(new GraphEdgeKey(
        "person:1001", "WORKS_FOR", "company:old", 0))
    .upsertNode(GraphNode.builder("person:1001", "Person")
        .property("name", "Alice")
        .build())
    .upsertNode(GraphNode.builder("company:2001", "Company")
        .property("name", "Example Corp")
        .build())
    .upsertEdge(GraphEdge.builder(
        "person:1001", "WORKS_FOR", "company:2001")
        .rank(0)
        .property("since", 2024L)
        .build())
    .build();

GraphWriteResult result = graph.writer().mutate(
    mutation,
    GraphOptions.ofSpace("company_knowledge"));
~~~

适配器按照固定顺序执行：

~~~text
删除节点 -> 删除边 -> Upsert 节点 -> Upsert 边
~~~

这个顺序允许同一变更先清理旧数据，再写入新的节点和关系。它也意味着不要仅凭 Builder 中方法的调用顺序推断执行顺序。

### operationId 的边界

`operationId` 是供审计、重放和上层幂等流程使用的稳定操作号。当前通用 Neo4j 和 Nebula 写入适配器不会持久化该值，也不会仅凭它自动跳过重复调用。

如果业务要求“同一个操作号只执行一次”，上层应建立操作记录和唯一约束，或使用 extractor 增量导入中的操作状态存储。数据库写入本身仍应采用稳定节点 ID 和 `GraphEdgeKey`，使未知结果后的重试尽量收敛到相同状态。

## 删除边和节点

### 删除指定边

删除边必须提供完整的 `GraphEdgeKey`：

~~~java
GraphMutation mutation = GraphMutation.builder()
    .deleteEdge(new GraphEdgeKey(
        "person:1001", "WORKS_FOR", "company:2001", 0))
    .build();
~~~

只知道起点和终点但不知道类型、rank 时，不应猜测身份。应先查询目标关系，或通过经过审核的原生查询执行范围删除。

### 删除节点及关联边

~~~java
GraphMutation mutation = GraphMutation.builder()
    .deleteNode("person:1001")
    .detachDeletedNodes(true)
    .build();
~~~

`detachDeletedNodes` 默认为 `true`，表示删除节点时同时删除其关联边。这个操作可能移除来自其他文档、来源或业务流程的关系，应作为高风险操作进行权限控制和审计。

设置为 `false` 时，仍有关联边的节点通常会被后端拒绝删除。适合由应用先检查和显式删除关系、确认没有其他引用后再删除节点。

如果一个实体可能被多个数据源共同引用，不应在某个来源消失时直接删除实体。更稳妥的做法是维护来源声明、引用计数或事实溯源，只撤销该来源贡献的边和属性；确认没有有效来源后再清理实体。

## 成功、失败和原子性

`GraphWriteResult` 提供：

| 字段 | 含义 |
| --- | --- |
| `success` | 适配器是否完整执行本次调用 |
| `nodesAffected` | 适配器报告的节点逻辑处理数 |
| `edgesAffected` | 适配器报告的边逻辑处理数 |
| `message` | 面向调用方的结果说明 |
| `errorCode` | 可供程序稳定判断的错误分类 |
| `error` | 底层异常，适合日志和诊断 |

当前适配器中的数量更接近“已执行的逻辑输入数”，不等同于数据库物理新增数。例如，重复 Upsert 可能只是更新已有节点，删除不存在的边也不一定改变数据库。需要精确对账时，应在写入后使用业务查询验证目标状态。

原子性取决于后端和调用方式：

- Neo4j 的普通 `mutate` 调用会在独立数据库事务中执行，失败时该次变更不会提交；
- 绑定到 `GraphTransaction` 的 Neo4j Writer 会加入当前显式事务；
- Nebula SessionPool 适配器按语句顺序执行，不承诺整个 `GraphMutation` 原子提交；
- Java 方法返回失败，不代表所有后端都一定没有产生任何变化。

因此，跨后端调用方必须按“可能部分成功”设计恢复逻辑，尤其是包含删除和 Upsert 的 Nebula 变更。

## 幂等、并发与重试

### 幂等来自稳定身份

Upsert 可以降低重复提交风险，但它不等于完整业务幂等。可靠写入通常需要同时满足：

- 节点 ID 稳定；
- 边的四元身份稳定；
- 属性合并规则明确；
- 删除范围可重复计算；
- 操作号在上层有持久化记录；
- 重试使用与首次调用相同的目标 Space 和 Schema 版本。

### 并发写入同一实体

两个请求同时更新同一节点时，后提交的属性可能覆盖先提交的同名属性。公共 Writer 当前不提供实体版本条件、乐观锁或属性级冲突合并。

对不能接受“最后写入者生效”的场景，应在上层维护 revision 并使用串行化、分布式锁或后端原生条件更新。知识抽取的长期增量导入还应保留文档状态和事实来源，避免不同文件互相删除对方贡献的数据。

### 只重试可恢复错误

不要对所有异常无限重试。建议根据稳定错误码和底层错误区分：

- 短暂连接故障、leader 变化等可采用有上限的退避重试；
- Schema 不匹配、非法标识符和权限不足应修正后再提交；
- 超时或连接中断可能无法立即判断数据库是否已提交，应先查询目标状态，再使用相同身份重试；
- 包含非幂等原生操作时，不能直接套用 Upsert 的重试策略。

Nebula 适配器会对部分 leader 选举和并发冲突错误执行有限重试，但这不能替代应用级重试预算、监控和对账。

## Neo4j 与 Nebula 的差异

| 维度 | Neo4j | Nebula Graph |
| --- | --- | --- |
| 节点身份 | 主 Label 与 `__agentsflex_id` 用于合并 | VID 是顶点身份 |
| 节点标签 | Portable Writer 支持多 Label | Portable Writer 当前只支持单 Tag |
| 属性 Upsert | `SET +=` 更新提供的属性 | `UPSERT ... SET` 更新提供的属性 |
| 无属性实体 | 使用 `MERGE` | 使用 `INSERT ... IF NOT EXISTS` |
| 普通 `mutate` 原子性 | 单次调用使用数据库事务 | 多条 nGQL 顺序执行，不提供整体事务保证 |
| 删除节点及关系 | `DETACH DELETE` | `DELETE VERTEX ... WITH EDGE` |
| 显式事务 | 支持 | 当前 SessionPool 适配器不支持 |
| Schema 依赖 | Label 和属性较动态，仍需约束与索引 | Tag、Edge 和属性必须预先定义并等待传播 |

Portable Writer 只提供两种后端的公共写入语义。需要条件更新、属性删除、复杂批量语句或后端专属性能优化时，可以使用原生能力，但应隔离在明确的适配层，并为每个后端单独测试。

## 常见问题

### Upsert 会覆盖整个节点吗？

不会。当前公共语义更新请求中提供的属性，未提供的旧属性通常保留。不要用缺少字段的对象表达完整快照替换。

### 重复写入会产生重复节点或边吗？

稳定节点 ID、稳定主标签和稳定 `GraphEdgeKey` 可以让 Upsert 收敛到同一实体或关系。若 ID、标签、方向或 rank 在重试时变化，仍会形成新数据。

### 为什么边写入成功，但查询不到关系？

首先检查两个端点是否已存在、Space 是否正确、边方向和 rank 是否一致。不要只依赖 `GraphWriteResult` 的逻辑处理数判断关系确实存在，应执行一次目标查询验证。

### 一个 Mutation 中同时删除和重建同一节点可以吗？

可以表达，固定顺序会先删除再 Upsert。但在 Nebula 上整个过程不是原子操作，中途失败可能只完成删除。高风险替换应使用可恢复状态机、补偿操作或支持事务的后端。

### 可以把 `operationId` 当作数据库唯一键吗？

不可以。它是提供给适配器和上层流程的稳定上下文，当前通用 Writer 不会自动保存或去重。需要应用自己的操作日志和唯一性控制。

## 生产检查清单

- 写入是否显式绑定正确的 Space、租户和 Schema 版本；
- 节点 ID 是否稳定、可重建，并在 Space 内避免冲突；
- 节点主标签是否稳定，Nebula 数据是否遵守单 Tag 限制；
- 边方向、类型和 rank 是否具有稳定业务含义；
- 写边前是否确保两个端点存在；
- 属性更新是否区分补丁、完整快照和属性删除；
- 是否避免依赖跨后端不一致的 `null` 语义；
- 删除节点前是否评估其他来源和关联边；
- 是否了解目标后端对单次 Mutation 的原子性保证；
- 超时和未知提交结果是否使用状态核验与幂等重试；
- 高并发更新是否具备 revision、锁或冲突处理策略；
- 是否记录 operationId、Space、业务来源和错误码用于审计；
- 写入后是否通过关键业务查询和数据质量规则完成验证。

少量在线变化可以直接使用 `GraphWriter`。需要装载大量节点和边时，继续阅读[批量导入](/zh/graph/import)；需要把相关读写作为一个原子单元时，继续阅读[事务](/zh/graph/transaction)。
