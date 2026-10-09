# 知识入图

## 概述

知识抽取完成后，我们得到的只是“这份文档里可能有哪些实体和关系”。要让这些结果成为可以长期查询和维护的图数据，还需要决定：这份文档是否已经处理过、它是不是旧文档的新版本、哪些关系需要新增或失效，以及图写入成功后要保存哪些来源和执行状态。

**知识入图（Knowledge Ingestion）**就是把一份文档的抽取结果转换为可审核的图变更，执行变更，并记录文档版本、实体身份和事实来源的完整过程。

`GraphIngestionService` 负责组织这个过程：

```text
文档 + Schema + 入图请求
  -> 判断是否重复处理
  -> 抽取并归一实体
  -> 对比该文档的上一版本
  -> 生成 GraphIngestionPlan
  -> 自动规则或人工审核
  -> GraphWriter 写入图数据库
  -> 保存实体注册、文档状态和操作进度
```

它解决的不是“怎样调用 Neo4j 或 Nebula 写入一条边”，而是“怎样让同一个知识库持续接收文档，同时保持数据可更新、可追溯、可恢复”。

## 一个具体场景

假设第一次导入会议纪要：

```text
张三负责支付系统升级项目。
```

系统抽取并写入：

```text
(张三)-[RESPONSIBLE_FOR]->(支付系统升级)
```

随后可能发生三件事：

1. 同一份内容被任务系统重复提交，不应再次调用模型和写图；
2. 会议纪要更新为“李四负责支付系统升级项目”，需要识别旧关系已经失效；
3. 另一份文档仍然写着“张三负责支付系统升级项目”，此时不能贸然删除共享关系。

仅调用 `GraphWriter` 无法回答这些问题。Writer 只负责执行已经准备好的 `GraphMutation`，并不知道 Mutation 来自哪份文档、哪个版本，也不知道同一条关系是否还有其他文档支持。知识入图服务在 Writer 之上补充了文档身份、版本差异、事实来源和恢复状态。

## 什么时候使用知识入图服务

以下场景适合使用 `GraphIngestionService`：

- 同一个知识库会持续接收新文档；
- 同一文档会修订、替换或撤回；
- 需要避免重复调用模型和重复写图；
- 需要追溯一条关系来自哪份文档和哪段证据；
- 写图前需要自动规则或人工审核；
- 服务中断后需要从原计划恢复。

如果节点和边已经由业务系统准备好，且不需要文档版本、来源追踪或撤回，可以直接使用 `GraphWriter` 或批量导入 API，不必引入知识入图状态。

## 先区分五个对象

理解 API 前，先把输入、计划和状态分开：

| 对象 | 用途 | 是否写图 |
| --- | --- | --- |
| `Document` | 本次要处理的文本内容 | 否 |
| `GraphIngestionRequest` | 指定目标 Space、稳定文档 ID 和版本策略 | 否 |
| `GraphExtractionResult` | 保存从文本中发现并通过校验的候选知识 | 否 |
| `GraphIngestionPlan` | 描述本次确认后应该执行的节点、关系和状态变化 | 否 |
| `GraphIngestionResult` | 描述计划执行后，图写入和文档状态是否都成功 | 来自执行阶段；`NO_OP` 不写图 |

其中最重要的边界是：`plan(...)` 只生成计划，不修改图数据库；`execute(...)` 才会产生写图和状态提交等副作用。

## 最小示例：先计划，再执行

下面假设应用已经准备好 `GraphExtractor`、`GraphSchema` 和目标 `graphStore`。先使用内存状态存储跑通完整流程：

```java
import com.agentsflex.core.document.Document;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionPlan;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionRequest;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionResult;
import com.agentsflex.graph.extractor.ingestion.GraphIngestionService;
import com.agentsflex.graph.extractor.ingestion.InMemoryGraphDocumentStateStore;

GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(extractor);

InMemoryGraphDocumentStateStore documentStates =
    new InMemoryGraphDocumentStateStore();

GraphIngestionService ingestion =
    new GraphIngestionService(pipeline, documentStates);

Document document = Document.of(
    "张三负责支付系统升级项目。");

GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "company_knowledge", "meeting-2026-001")
        .documentVersion("v1")
        .schemaVersion("company-schema-v1")
        .build();

// 只计算计划，不写图。
GraphIngestionPlan plan =
    ingestion.plan(document, schema, request);

System.out.println(plan.getType());
System.out.println(plan.getMutation());
System.out.println(plan.getStaleEdgeKeys());

// 审核通过后才执行图写入和状态提交。
GraphIngestionResult result =
    ingestion.execute(plan, graphStore.writer());

if (!result.isSuccess()) {
    throw new IllegalStateException(
        result.getWriteResult().getMessage());
}
```

这段代码按顺序完成：

1. 根据文档内容计算摘要；
2. 查询 `company_knowledge + meeting-2026-001` 的已提交状态；
3. 没有旧状态时执行抽取、校验和实体归一；
4. 生成类型为 `INGESTION` 的计划；
5. 调用方检查计划中的候选、问题、Mutation 和过期关系；
6. `execute(...)` 调用 Writer，成功后提交新的文档状态。

示例中的 `InMemoryGraphDocumentStateStore` 只适合学习、测试和单进程临时任务。应用重启后数据会消失，不能用于长期知识库。

## `plan`、`execute` 和 `ingest` 的区别

### 需要审核：使用 `plan` + `execute`

需要展示证据、人工确认实体身份或审核删除项时，应显式拆开：

```java
GraphIngestionPlan plan =
    ingestion.plan(document, schema, request);

if (plan.getType() != GraphIngestionPlan.Type.NO_OP) {
    GraphReviewTask task = reviewService.submit(plan);
    // 把 taskId 返回给应用自己的审核后台。
}
```

审核服务会保存冻结的计划。审核接受时应执行这份计划，而不是重新调用模型生成另一份结果。审核查询、修改、接受和拒绝见[审核](/zh/graph/extractor/review)。

### 可以自动接受：使用 `ingest`

业务已经有明确的自动接受规则时，可以把规划和执行合并：

```java
GraphIngestionResult result = ingestion.ingest(
    document,
    schema,
    request,
    graphStore.writer());
```

`ingest(...)` 并不是绕过计划；它会在内部生成计划并立即执行。高风险关系、删除变化或需要人工确认的实体不应直接自动接受。

## 如何构造入图请求

最少只需要 `space` 和 `documentId`：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "company_knowledge", "meeting-2026-001")
        .build();
```

生产场景通常还会提供业务版本和配置版本：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "company_knowledge", "meeting-2026-001")
        .documentVersion("v3")
        .schemaVersion("company-schema-v2")
        .batchId("import-2026-10-09")
        .sourceUpdatedAtMillis(sourceUpdatedAt)
        .extractionFingerprint("model-prompt-dictionary-v5")
        .graphOptions(
            GraphOptions.ofSpace("company_knowledge"))
        .build();
```

| 字段 | 含义 | 使用建议 |
| --- | --- | --- |
| `space` | 目标知识库和状态隔离边界 | 必填 |
| `documentId` | 跨版本稳定的逻辑文档身份 | 必填，不要每次重试都生成新值 |
| `contentHash` | 当前内容的可信摘要 | 可省略，由 SDK 计算 SHA-256 |
| `documentVersion` | 来源系统中的业务版本 | 有版本号时建议提供 |
| `schemaVersion` | 本次抽取使用的 Schema 版本 | 生产环境建议提供 |
| `batchId` | 上层导入批次的关联标识 | 用于追踪批任务 |
| `operationId` | 一次入图操作的稳定幂等号 | 可省略，由 SDK 根据请求生成 |
| `sourceUpdatedAtMillis` | 来源系统的更新时间 | 用于拒绝乱序到达的旧版本 |
| `extractionFingerprint` | 模型、Prompt、词典等抽取配置版本 | 配置变化时必须随之变化 |
| `graphOptions` | 写图路由、超时和上下文 | 省略时自动使用请求的 Space；显式设置时必须与其一致 |

调用方提供 `contentHash` 时，它必须真实代表当前内容。错误的摘要会让系统把新内容误判为已经处理。

## 稳定的 `documentId` 为什么重要

`documentId` 表示一份逻辑文档，而不是一次上传任务：

```text
新的独立文件
  -> 使用新的 documentId

原文件的修订版
  -> 沿用原 documentId
  -> 更新 documentVersion 和 sourceUpdatedAtMillis
```

如果每次上传都创建新 `documentId`，系统会把每个版本当作独立来源，无法判断旧版本中哪些关系已经失效。如果两份独立文件误用同一个 `documentId`，后一次导入又会覆盖前一份文档的状态。

`sourceUpdatedAtMillis` 应来自可信业务源，而不是消费者处理消息的当前时间。分布式消息和重试可能乱序到达，服务会拒绝来源时间早于当前活动版本的请求。

## 重复提交与 `NO_OP`

服务会综合比较：

```text
Space + documentId + contentHash + documentVersion
+ schemaVersion + extractionFingerprint
```

这些信息都没有变化时，`plan(...)` 返回 `NO_OP`，并且不会再次调用 Extractor 或 Writer：

```java
GraphIngestionPlan repeated =
    ingestion.plan(document, schema, request);

if (repeated.getType() == GraphIngestionPlan.Type.NO_OP) {
    // 当前文档版本已经生效，无需再次审核或写图。
}
```

以下变化会触发重新抽取：

- 文档内容变化；
- `documentVersion` 变化；
- `schemaVersion` 变化；
- `extractionFingerprint` 变化；
- 显式设置 `reextractUnchangedContent(true)`。

模型、Prompt、解析协议、温度或业务词典发生变化时，应更新 `extractionFingerprint`。否则正文相同的文档可能被错误跳过。

## 同一文档更新时发生什么

当相同 `documentId` 提交新版本时，服务会比较新旧关系集合：

```text
新增关系 = 新版本关系 - 旧版本关系
保留关系 = 新版本关系 ∩ 旧版本关系
过期关系 = 旧版本关系 - 新版本关系
```

过期关系会出现在 `plan.getStaleEdgeKeys()` 中，但不一定从图中删除。默认策略是 `KEEP`，只报告关系已过期：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "company_knowledge", "meeting-2026-001")
        .staleRelationPolicy(
            GraphIngestionRequest.StaleRelationPolicy.KEEP)
        .build();
```

需要自动清理时，可以改为：

```java
.staleRelationPolicy(
    GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED)
```

此策略只删除没有被同一 Space 中其他活动文档引用的关系。假如两份会议纪要都支持同一关系，其中一份更新或撤回时，关系仍会保留。

如果抽取存在部分 Chunk 失败，默认不会用不完整结果重建全部关系集合，以免把“没有抽取出来”误判为“事实已删除”。`allowPartialReconciliation(true)` 会放开这一保护，应只在业务已经评估风险后使用。

入图请求还有三个相互独立的安全开关：

| 设置 | 默认值 | 作用 |
| --- | --- | --- |
| `failOnExtractionError(true)` | `true` | 抽取结果包含 ERROR 时拒绝生成可执行计划 |
| `allowPartialReconciliation(false)` | `false` | 部分 Chunk 失败时不根据残缺结果撤销旧关系 |
| `reextractUnchangedContent(false)` | `false` | 内容和版本未变化时直接返回 `NO_OP` |

允许部分结果继续处理，并不代表它适合自动删除旧关系。应用应同时检查 `GraphExtractionResult` 中的问题、证据覆盖率和失败 Chunk，再决定是否审核或执行。

## 事实来源保存了什么

入图服务会为通过校验且带证据的关系生成 `GraphFactSource`，关联：

- Space 和关系键；
- 来源文档与 Chunk；
- 原文证据；
- 文档 revision 和 operationId；
- 置信度、断言类型和关系属性。

这些记录让应用能够回答“为什么图里有这条关系”和“还有哪些文档支持它”。`GraphDocumentStateStore.findCurrentFactSources(...)` 查询当前活动来源，`findFactSourceHistory(...)` 用于历史审计。

## 撤回一份文档

撤回不是提交空字符串，而是生成专门的 `RETRACTION` 计划：

```java
GraphIngestionPlan retraction =
    ingestion.planRetraction(
        "company_knowledge",
        "meeting-2026-001",
        GraphOptions.ofSpace("company_knowledge"));

// 先检查将失效和将删除的关系，再执行。
GraphIngestionResult result =
    ingestion.execute(retraction, graphStore.writer());
```

撤回时，服务会：

- 找出该文档当前版本支持的关系；
- 只删除没有其他活动文档支持的关系；
- 不自动删除可能被其他数据共享的实体节点；
- 保存 `RETRACTED` 墓碑状态，保留版本和审计信息。

文档不存在或已经撤回时，计划类型为 `NO_OP`。

## 跨文档复用实体身份

前面的最小示例只使用默认名称/别名归一。长期知识库还应让 Pipeline 和入图服务共享同一个 `GraphEntityRegistry`：

```java
GraphEntityRegistry entityRegistry =
    new InMemoryGraphEntityRegistry();

GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(
        extractor,
        documentSplitter,
        new SchemaGraphCandidateValidator(),
        new RegistryGraphEntityResolver(
            "company_knowledge", entityRegistry),
        new GraphCandidateMutationMapper());

GraphIngestionService ingestion =
    new GraphIngestionService(
        pipeline,
        documentStates,
        entityRegistry);
```

共享注册表承担两个不同阶段的职责：

```text
RegistryGraphEntityResolver 查询注册表
  -> 抽取阶段复用已有 nodeId

GraphIngestionService 保存注册表
  -> 图写成功后登记本次确认的名称和别名
```

如果只把注册表传给入图服务，而 Pipeline 仍使用默认 Resolver，本次抽取无法复用历史身份。实体匹配规则和生产约束见[实体归一](/zh/graph/extractor/entity-resolution)。

## 生产环境需要哪些持久化组件

最小示例中的内存实现不能跨进程保存状态。生产服务通常需要四个组件：

| 组件 | 保存或控制什么 | 关键要求 |
| --- | --- | --- |
| `GraphDocumentStateStore` | 文档当前版本、历史版本、节点、关系和事实来源 | revision CAS、历史快照、来源反向查询 |
| `GraphEntityRegistry` | 跨文档复用的实体 ID、规范名称和别名 | Space/类型隔离、唯一约束、幂等保存 |
| `GraphIngestionOperationStore` | 冻结的计划和执行阶段 | operationId 唯一、阶段 CAS、可扫描恢复 |
| `GraphIngestionLockProvider` | 同一 Space + documentId 的并发执行 | 多实例共享锁或可靠任务分区 |

装配结构如下：

```java
GraphDocumentStateStore documentStates =
    new YourPersistentDocumentStateStore();
GraphEntityRegistry entityRegistry =
    new YourPersistentEntityRegistry();
GraphIngestionOperationStore operations =
    new YourPersistentOperationStore();
GraphIngestionLockProvider locks =
    new YourDistributedDocumentLockProvider();

GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(
        extractor,
        documentSplitter,
        validator,
        new RegistryGraphEntityResolver(
            "company_knowledge", entityRegistry),
        new GraphCandidateMutationMapper());

GraphIngestionService ingestion =
    new GraphIngestionService(
        pipeline,
        documentStates,
        entityRegistry,
        operations,
        locks);
```

Resolver、请求、`GraphOptions` 和各类存储使用的 Space 必须一致。SDK 的 `InMemory...` 实现和 `LocalGraphIngestionLockProvider` 适用于测试或单 JVM 临时任务；后者不能协调多个应用实例。

## 执行顺序与故障恢复

`execute(...)` 的主要顺序是：

```text
创建或确认操作记录
  -> 检查文档 revision
  -> GraphWriter 写入 Mutation
  -> 保存实体注册
  -> CAS 提交文档当前状态
  -> 保存版本历史
  -> 标记操作完成
```

图数据库、实体注册表和文档状态存储通常不在同一个事务中，因此可能出现：

```text
图写入成功
  -> 服务进程退出
  -> 文档状态尚未提交
```

生产环境应在 `GraphIngestionOperationStore` 中原子保存操作记录和原始计划。恢复时读取被冻结的计划继续执行，不重新调用模型：

```java
GraphIngestionResult recovered =
    ingestion.resume(operationId, graphStore.writer());
```

重新调用模型可能得到不同候选，导致实际写入内容与审核内容不一致。各故障窗口及恢复要求见[故障恢复](/zh/graph/extractor/recovery)。

## 与普通批量导入的区别

`GraphWriter.importData(...)` 适合已经准备好的大量节点和边，主要关注吞吐和批次写入。`GraphIngestionService` 关注的是文档知识的生命周期：

| 问题 | 批量导入 | 知识入图 |
| --- | --- | --- |
| 数据是否已经是节点和边 | 是 | 通常从文档抽取 |
| 是否识别重复文档 | 不负责 | 负责 |
| 是否比较同一文档的新旧版本 | 不负责 | 负责 |
| 是否记录事实来源 | 由调用方处理 | 纳入文档状态 |
| 是否支持审核后执行 | 由调用方处理 | 通过 Plan 边界支持 |
| 是否支持跨系统阶段恢复 | 由调用方处理 | 通过 OperationStore 支持 |

大量历史文件也可以由上层任务系统逐份调用知识入图服务。文件数量多并不意味着应该放弃文档身份和来源管理。

## 常见问题

### 后续导入新文件需要创建新 Space 吗？

通常不需要。同一业务知识库继续使用同一个 Space；不同文档使用各自稳定的 `documentId`，实体通过注册表复用。

### `contentHash` 相同就一定返回 `NO_OP` 吗？

不一定。业务版本、Schema 版本或抽取配置指纹发生变化时仍会重新抽取，也可以使用 `reextractUnchangedContent(true)` 强制重抽。

### `plan(...)` 会写数据库吗？

不会写图，也不会提交文档状态。它会读取状态并调用抽取流程，因此仍可能产生模型调用和相应成本。

### 可以只配置 `GraphDocumentStateStore` 吗？

可以完成基础入图和版本判重，但没有实体注册表就不能跨批次复用已确认身份；没有操作存储则无法使用 `resume(...)` 进行可靠的阶段恢复。

### 计划生成后可以稍后执行吗？

可以保存并审核，但执行时文档 revision 必须仍与计划基于的旧状态一致。若其他版本已经提交，服务会拒绝执行过期计划，应重新规划。

### `GraphIngestionResult.isSuccess()` 表示知识一定正确吗？

不是。它只表示图写入成功并且文档状态已经提交。知识是否正确仍取决于抽取质量、Schema、审核规则和来源可信度。

## 生产检查清单

- `documentId` 是否稳定区分“独立文件”和“同一文件的新版本”？
- 请求、Resolver、`GraphOptions` 和状态存储是否使用同一个 Space？
- Schema、模型、Prompt 和业务词典是否有可追踪的版本或指纹？
- Pipeline 是否使用生产实体注册表复用历史身份？
- 文档状态、实体注册和操作记录是否使用持久化实现？
- 高风险变更是否经过 `plan -> 审核 -> execute`？
- 旧关系策略是否明确，删除前是否检查其他活动来源？
- 是否使用来源时间或业务 revision 防止旧版本倒灌？
- 多实例是否同时具备共享锁和状态 CAS？
- 图写成功、状态提交失败时是否能用原计划恢复和对账？

如果需要进一步理解每个阶段何时产生副作用，请继续阅读[知识入图生命周期](/zh/graph/extractor/ingestion-lifecycle)；具体恢复方案见[故障恢复](/zh/graph/extractor/recovery)。
