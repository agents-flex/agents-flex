# 故障恢复

## 概述

一次知识入图不只写图数据库。它通常还要保存实体注册、文档当前版本、历史快照和操作进度。假设系统执行到下面的位置时进程退出：

```text
GraphWriter 已经写入节点和关系
  -> 进程退出
  -> 文档状态还没有提交
```

此时不能简单地认为“整个操作失败了”。图数据库里的变化可能已经生效，但外部状态仍显示文档没有入图。如果重新调用模型，模型还可能产生与首次不同的候选和 Mutation，使问题进一步扩大。

**故障恢复**就是保存首次执行的入图计划和当前执行阶段，在服务重启或依赖恢复后，继续完成同一份计划，而不是重新猜测应该写什么。

```text
首次执行：计划 A -> 写图成功 -> 中断
恢复执行：读取计划 A -> 跳过或幂等重放已完成步骤 -> 提交剩余状态
```

`GraphIngestionService.resume(operationId, writer)` 提供这个恢复入口。它不会重新调用大模型，但也不是跨数据库事务或自动回滚机制；某些无法确定图是否已经提交的窗口仍然需要幂等写入和对账。

## 先区分重试、恢复和对账

这三个动作处理的问题不同：

| 动作 | 适用情况 | 使用的数据 |
| --- | --- | --- |
| 重新尝试规划 | 尚未保存操作和计划，例如模型调用阶段失败 | 原文档、Schema 和请求 |
| 恢复执行 | 已经存在 `GraphIngestionOperation` 和冻结计划 | 原 `operationId` 和原计划 |
| 对账或补偿 | 无法确认后端结果、revision 已冲突或多个系统已经分叉 | 图数据、文档状态、注册表和操作日志 |

恢复的核心不是“再执行一次相同业务代码”，而是先确认操作已经进行到哪里，再决定哪些步骤可以跳过、重放或转人工。

## 恢复依赖的三个对象

| 对象 | 保存什么 | 解决什么问题 |
| --- | --- | --- |
| `GraphIngestionPlan` | Writer 要执行的 Mutation，以及随后要提交的状态和实体注册 | 保证恢复内容与首次审核内容一致 |
| `GraphIngestionOperation` | `operationId`、文档、预期 revision、计划指纹和当前阶段 | 判断操作执行到哪里 |
| `GraphIngestionOperationStore` | 操作记录与首次冻结的计划 | 让服务重启后仍能读取和扫描 |

文档原文和抽取请求仍应由上层任务保存，但进入执行阶段后，恢复使用的是冻结计划，而不是重新抽取原文。

## 最小示例：失败后恢复原计划

下面假设 `pipeline`、`documentStates`、`entityRegistry` 和 `graphStore` 已经准备好。先用内存操作存储演示 API：

```java
import com.agentsflex.core.document.Document;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionOperation;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionRequest;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionResult;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionService;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphIngestionOperationStore;

InMemoryGraphIngestionOperationStore operations =
    new InMemoryGraphIngestionOperationStore();

GraphIngestionService ingestion =
    new GraphIngestionService(
        pipeline,
        documentStates,
        entityRegistry,
        operations);

String operationId = "ingest-meeting-2026-001-v3";

GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "company_knowledge", "meeting-2026-001")
        .documentVersion("v3")
        .operationId(operationId)
        .build();

try {
    ingestion.ingest(
        Document.of("张三负责支付系统升级项目。"),
        schema,
        request,
        graphStore.writer());
} catch (RuntimeException failure) {
    GraphIngestionOperation operation =
        operations.get(operationId);

    if (operation != null) {
        System.out.println(operation.getStage());
        System.out.println(operation.getFailureMessage());
    }
}

// 确认依赖已恢复、错误可重试后，从冻结计划继续。
GraphIngestionResult recovered =
    ingestion.resume(operationId, graphStore.writer());
```

这个例子有两个重要前提：

1. `operationId` 在同一业务操作的首次执行和恢复期间保持不变；
2. `GraphIngestionOperationStore` 同时保存了操作记录和首次计划。

`InMemoryGraphIngestionOperationStore` 只能在当前进程内演示恢复。进程退出后其中的数据也会丢失；真正的重启恢复必须使用数据库等持久化实现。

生产代码不应捕获任何异常后立即无限调用 `resume(...)`。应先按错误类型判断是否可重试，并设置退避、次数上限和告警。

## 操作状态机

`GraphIngestionOperation` 使用下面的阶段：

```text
PREPARED
  -> GRAPH_APPLIED
  -> STATE_COMMITTED
  -> COMPLETED

PREPARED -> FAILED -> PREPARED
```

| 阶段 | 已经确认的事实 | 恢复时的主要动作 |
| --- | --- | --- |
| `PREPARED` | 操作和原计划已保存，但尚未确认图写入 | 调用或重放 Writer |
| `FAILED` | Writer 在确认成功前报告失败 | 回到 `PREPARED`，按原计划重试 Writer |
| `GRAPH_APPLIED` | Writer 已报告成功并完成阶段记录 | 跳过 Writer，继续实体注册和文档状态提交 |
| `STATE_COMMITTED` | 文档当前状态和历史快照已提交 | 只完成操作日志收尾 |
| `COMPLETED` | 全部步骤完成 | 幂等返回，不再写图 |

操作记录描述的是“系统确认到了哪里”，不一定等于外部系统的绝对真实状态。例如 Writer 已经在后端提交，但进程在写入 `GRAPH_APPLIED` 阶段前退出，日志仍是 `PREPARED`。

## 服务如何执行和恢复

一次 `execute(...)` 或 `ingest(...)` 的主要顺序是：

```text
1. 创建 PREPARED 操作并冻结计划
2. 检查文档 expected revision
3. GraphWriter 执行 Mutation
4. 操作推进到 GRAPH_APPLIED
5. 保存实体注册
6. CAS 提交文档当前状态
7. 保存文档版本历史
8. 操作推进到 STATE_COMMITTED
9. 操作推进到 COMPLETED
```

`resume(...)` 从 OperationStore 读取同一计划，再进入相同执行逻辑。服务会根据操作阶段和已经提交的文档 `operationId` 跳过完成的步骤。

如果 OperationStore 没有实现计划读取和恢复扫描，`isRecoverySupported()` 会返回 `false`，此时 `resume(...)` 会拒绝执行。调用方仍可以保留并重新执行原 `GraphIngestionPlan`，但这不等于支持服务重启后的恢复。

## 为什么必须使用原计划

大模型输出可能非确定。即使原文、Prompt 和参数都相同，再次抽取也可能得到不同候选、属性或关系。恢复时重新调用模型会造成：

```text
审核的是计划 A
首次可能写入了计划 A
恢复却执行计划 B
```

因此，OperationStore 应保存首次不可变计划。服务还会为计划计算 `planFingerprint`，覆盖影响业务结果的内容，包括：

- 计划类型、Space 和 documentId；
- 图路由相关选项；
- 待写入和删除的全部节点、边；
- 待提交的文档状态和事实来源；
- 待保存的实体注册记录。

同一个 `operationId` 如果已经绑定了不同的 `planFingerprint`、文档、Space 或 expected revision，服务会拒绝执行。`planFingerprint` 用来发现错误复用，不用来替代原计划存储。

## `operationId`、revision 和锁分别做什么

可靠恢复通常同时依赖三种机制：

### 稳定 `operationId`

标识同一次入图意图，并把操作日志、Mutation 和文档状态关联起来。相同操作的恢复必须沿用原 ID；不同计划不能复用同一个 ID。

`operationId` 不是 Writer 的通用去重键。当前公共 Neo4j/Nebula Writer 不会因为它相同就自动跳过 Mutation。

### 文档 revision CAS

`GraphDocumentStateStore.compareAndSet(...)` 确认计划所基于的文档版本仍然是当前版本。它防止一个较旧计划覆盖另一任务刚提交的新版本。

### 文档级锁

`GraphIngestionLockProvider` 降低同一 `Space + documentId` 被并发执行的概率。默认 `LocalGraphIngestionLockProvider` 只保护单个 JVM；多实例需要共享锁或可靠的任务分区。

锁可能超时、租约可能失效，因此不能替代稳定 ID、唯一约束和 revision CAS。

## 各故障窗口如何处理

### 规划阶段失败

如果模型、Parser、Validator 或 Resolver 在生成计划时失败，操作记录尚未创建，也没有冻结计划可以 `resume`。上层任务应保留原文档、Schema 和请求，按模型错误策略重新规划或转人工。

### `PREPARED` 后、调用 Writer 前失败

操作和计划已经保存，图尚未执行。恢复会按原计划调用 Writer。

### Writer 明确返回失败或抛出异常

服务会尝试把操作从 `PREPARED` 标记为 `FAILED`。恢复时先回到 `PREPARED`，再调用 Writer。

但“客户端收到异常”并不总能证明后端没有提交。例如网络在数据库提交响应返回前中断，操作可能是 `FAILED`，图中变化却已经生效。此时 Mutation 必须可重放，并应在恢复前后核验图状态。

### Writer 成功、`GRAPH_APPLIED` 日志尚未保存时失败

这是最窄也最危险的未知窗口：图可能已生效，操作仍是 `PREPARED`。恢复会再次调用 Writer，因此只能提供至少一次执行语义。

节点 ID、边身份和删除目标必须稳定，Upsert 和删除应可重复执行。对非幂等的自定义 Writer 或副作用，应增加后端幂等键或专门对账，不能只依赖 OperationStore。

### `GRAPH_APPLIED` 后实体注册失败

阶段已经证明 Writer 成功。恢复跳过 Writer，再次幂等保存实体注册，然后继续提交文档状态。

### `GRAPH_APPLIED` 后文档状态 CAS 失败

如果只是状态库瞬时故障，恢复会跳过 Writer，并再次提交状态。

如果 CAS 失败是因为另一版本已经推进 revision，反复恢复不会解决冲突。此时应检查最新状态和当前 `operationId`：

1. 如果当前或历史状态已经包含该 `operationId`，补齐历史和操作阶段；
2. 如果是另一版本抢先提交，停止自动重试并废弃旧计划；
3. 基于最新状态重新规划后续版本；
4. 对旧计划已经产生的图副作用进行对账或补偿。

### 当前状态已提交、历史或操作日志失败

文档当前状态保存了 `operationId`。恢复发现该操作已经提交后，不会再次写图，而是补写版本历史并将操作阶段依次推进到 `COMPLETED`。

### `COMPLETED` 后收到重复恢复请求

服务通过已提交状态和操作阶段幂等返回，不重复执行 Writer。正常扫描不会返回 `COMPLETED` 操作。

## 故障处理矩阵

| 观察到的状态 | 图是否可能已生效 | `resume` 是否可能再次调用 Writer | 推荐处置 |
| --- | --- | --- | --- |
| 没有操作记录 | 通常否 | 无法 `resume` | 重新规划或人工处理 |
| `PREPARED` | 可能 | 是 | 要求 Mutation 可重放，并对账未知结果 |
| `FAILED` | 可能，取决于后端提交结果 | 是 | 分类错误、限次重试、核验图状态 |
| `GRAPH_APPLIED` | 是 | 否 | 继续注册和状态提交 |
| `STATE_COMMITTED` | 是 | 否 | 完成操作日志收尾 |
| `COMPLETED` | 是 | 否 | 幂等返回 |

“图是否可能已生效”之所以在 `PREPARED` 和 `FAILED` 中仍然为“可能”，是因为客户端异常和阶段日志都无法消除数据库提交响应丢失的窗口。

## 扫描并恢复未完成操作

SDK 不会自动启动恢复线程。应用可以在服务启动时或周期任务中扫描：

```java
if (!ingestion.isRecoverySupported()) {
    throw new IllegalStateException(
        "当前 OperationStore 不支持计划恢复");
}

for (GraphIngestionOperation operation :
        ingestion.listRecoverableOperations(100)) {
    try {
        ingestion.resume(
            operation.getOperationId(),
            graphStore.writer());
    } catch (RuntimeException failure) {
        // 记录阶段和错误分类，由任务系统决定退避、告警或转死信。
    }
}
```

`listRecoverableOperations(limit)` 返回尚未 `COMPLETED` 的操作，按具体 Store 的稳定顺序扫描。任务系统还需要负责：

- 多实例任务认领或租约；
- 可恢复与永久错误分类；
- 指数退避、最大尝试次数和抖动；
- 死信、告警和人工处理；
- 单个失败不阻塞整批扫描；
- 限制恢复任务对图数据库和状态库的压力。

如果多个实例可能扫描到同一操作，生产 Store 或任务系统必须提供防重复认领；共享文档锁和阶段 CAS 仍应保留，作为并发保护的后续防线。

## OperationStore 的生产要求

`GraphIngestionOperationStore` 的持久化实现至少应满足：

- 以 `operationId` 建立全局唯一索引；
- 在同一个存储事务中原子创建操作记录和首次计划；
- 重复创建时保留首次记录，不能覆盖计划；
- 原子实现阶段 `compareAndSet`；
- 完整序列化 `GraphOptions`、Mutation、待提交状态、事实来源和实体注册；
- 实现 `getPlan(operationId)` 和稳定的待恢复操作扫描；
- 保留失败原因、更新时间、尝试次数和最终处置等运维信息；
- 支持恢复任务认领、租约或与外部任务系统配合。

`isRecoverySupported()` 只表示实现提供计划读取和扫描 API，不代表数据一定跨进程持久化。内存实现也返回 `true`，但服务重启后无法恢复任何内容。

## Mutation 为什么必须可重放

当前恢复机制能在 `GRAPH_APPLIED` 之后可靠跳过 Writer，但无法消除“后端已提交、阶段还没保存”的窗口。因此计划中的图变更应满足：

- 节点使用稳定 `nodeId`，重试收敛到同一节点；
- 边使用稳定端点、类型和 rank；
- Upsert 不依赖随机 ID 或当前时间生成身份；
- 删除同一个已不存在对象时具有幂等语义；
- 属性覆盖规则在重试时保持一致；
- 自定义外部副作用拥有自己的幂等键。

如果 Mutation 包含无法安全重放的业务动作，就不能直接使用自动恢复。应先查询后端结果，或者把该动作改造成独立 Saga 步骤并提供补偿。

## revision 冲突不能靠重试解决

假设计划 A 和计划 B 都基于 revision 3，B 先提交成 revision 4。A 即使已经写图，也不能再把自己的状态强行提交为 revision 4。

正确处理方式是：

```text
停止重试旧计划 A
  -> 读取当前 revision 4
  -> 检查 A 已产生的图副作用
  -> 按业务决定保留、补偿或人工合并
  -> 基于最新状态生成新计划
```

不要绕过 CAS 或直接修改 expected revision。那会让文档状态看似成功，却无法准确表示图中实际生效的来源和版本。

## 本地锁、分布式锁和并发

同一文档应尽量串行执行，但不同文档通常可以并行。不同文档仍可能同时：

- 注册同一个新实体；
- 更新同一个图节点属性；
- 支持或撤销同一个关系；
- 争用模型和数据库配额。

因此生产环境还需要：

- Entity Registry 的 Space/类型唯一约束和原子冲突处理；
- 图节点和关系的稳定身份；
- 并发安全的事实来源引用判断；
- 明确的节点属性合并策略；
- 线程安全的模型客户端、Resolver 和 Store 实现。

共享锁需要考虑获取超时、租约续约、持有者校验、仅持有者释放、进程暂停和网络分区。即使锁实现可靠，状态 CAS 也不能省略。

## 对账与人工补偿

OperationStore 记录的是客户端已确认阶段，不是所有外部系统的共同真相。生产系统应定期比较：

- `GraphIngestionOperation` 的阶段；
- `GraphDocumentState` 的当前 revision 和 operationId；
- 图中预期的节点、边和删除结果；
- `GraphEntityRegistry` 中的实体身份；
- 事实来源和版本历史。

对账发现不一致时，不要直接把某一边的数据覆盖到另一边。先确认哪一份计划被审核、哪些副作用已经发生，再选择补写状态、重放幂等 Mutation、执行补偿 Mutation 或转人工处理。

需要更强的跨系统业务一致性时，可以组合 Outbox、幂等消费者、Saga 补偿和人工修复入口。Java 方法处于同一个调用栈，并不意味着图数据库和状态数据库可以共同回滚。

## 监控与日志

建议至少记录并监控：

- Space、documentId、operationId 和 planFingerprint；
- expected revision 与当前 revision；
- 操作阶段、失败分类、尝试次数和下次重试时间；
- Writer 结果和状态提交结果；
- 各阶段耗时、待恢复数量和最老操作年龄；
- 自动恢复成功率、死信数量和人工处置结果。

日志应避免直接写入完整原文、模型原始响应和敏感实体属性。需要回放的数据应保存在有访问控制和保留周期的专用存储中。

## 常见问题

### 有分布式锁还需要 `operationId` 吗？

需要。锁只能减少并发，不能处理 Writer 提交后进程退出、重复消息、租约到期或阶段日志失败。

### 相同 `operationId` 会让 Writer 自动跳过吗？

不会。OperationStore 在明确进入 `GRAPH_APPLIED` 后可以让服务跳过 Writer，但当前公共 Writer 不把 `operationId` 当作数据库级去重键。

### 为什么恢复不能重新调用模型？

模型输出可能变化，而且首次计划可能已经被审核或部分执行。恢复必须继续原计划，才能让操作号、写图内容和状态提交保持一致。

### `FAILED` 是否表示图一定没有写入？

不表示。它只说明 Writer 没有向客户端确认成功。网络中断或超时可能发生在后端提交之后，因此仍要依赖幂等 Mutation 和对账。

### `InMemoryGraphIngestionOperationStore` 能用于生产吗？

不建议。它只支持当前进程内演示；进程退出后，计划和阶段都会丢失，无法覆盖最需要恢复的重启场景。

### 没有 OperationStore 可以恢复吗？

调用方如果仍持有原 `GraphIngestionPlan`，可以直接再次执行它。但服务无法通过 `operationId` 读取冻结计划或扫描未完成任务，也无法跨进程判断执行阶段。

## 生产检查清单

- `operationId` 是否稳定、全局唯一且只绑定一份计划？
- 操作记录和原计划是否在同一存储事务中创建？
- `planFingerprint` 不一致时是否拒绝复用操作号？
- 操作阶段和文档 revision 是否都使用原子 CAS？
- Mutation 的节点、边和删除目标是否具有稳定身份并可重放？
- 是否识别 Writer 已提交但阶段仍为 `PREPARED` 的未知窗口？
- 恢复是否读取冻结计划，而不是重新调用模型？
- revision 冲突是否停止自动重试并进入重新规划或补偿？
- 多实例恢复任务是否具有认领、租约、退避和次数上限？
- Entity Registry 和事实来源查询是否能处理跨文档并发？
- 是否持续对账图数据、文档状态、注册表和操作日志？
- 是否提供告警、死信和人工补偿入口？

错误类型与是否可重试的判断见[错误处理](/zh/graph/extractor/error-handling)；完整入图边界见[知识入图](/zh/graph/extractor/ingestion)。大模型调用本身的配置、评测与安全要求见[大模型知识提取](/zh/graph/extractor/model-integration)。
