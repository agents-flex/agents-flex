# 知识入图生命周期

## 概述

假设应用从一份会议纪要中抽取出“张三任职于星河科技”。模型已经返回了候选实体、关系和原文证据，但这条关系此时还不能直接被当作知识库中的正式数据：它可能需要人工审核，也可能已经被另一份文档支持，甚至可能在图写入成功后还没有完成文档状态保存。

**知识入图生命周期**描述的就是：一条候选知识如何经过计划、审核、写入、状态提交、更新、撤回和故障恢复，最终成为 Graph Space 中可查询、可维护的数据。

本章讲的是一条知识从文本出现，到进入 Graph Space，再到后续更新、撤回和恢复的完整生命周期。它关注的是执行边界和状态变化，而不是 Chunk 如何分段、模型如何生成候选；一次抽取的具体处理步骤见[知识抽取流程](/zh/graph/extractor/extraction-process)。

## 解决什么问题

如果只调用模型并立即写图，应用很快会遇到几个问题：

- 审核前无法阻止错误候选进入图数据库；
- 同一文档重复导入时会重复调用模型和写入数据；
- 文档更新后，不知道哪些旧关系应该保留或撤回；
- 图写入成功、状态保存失败时，重试可能造成不一致；
- 文档撤回时，无法判断一条关系是否还有其他来源支持。

知识入图生命周期通过 `extract`、`plan`、`execute`、`resume` 和 `planRetraction` 这些边界，把“发现知识”和“改变图数据”分开管理。它解决的是一次知识变更如何安全、可审核、可重试地落到图数据库，而不是如何调用某个具体的大模型。

## 一条知识如何进入图数据库

以文档“林默加入青云宗”为例：

```text
文档文本
  -> 知识抽取流程得到人物、组织和关系候选
  -> 校验证据、Schema 和置信度
  -> 归一“林默”和“青云宗”的长期实体身份
  -> 生成知识入图计划
  -> 自动规则或人工审核
  -> 执行 GraphMutation
  -> 提交文档版本、事实来源和操作状态
```

只有执行计划并完成状态提交后，这条知识才算成为当前图数据的一部分。前面的抽取结果和计划都可以保存、审核或废弃，但不会自动改变图数据库。

## 先区分四个对象

为了理解后面的代码，可以先把四个对象分别看成四种记录：

| 对象 | 用简单的话说 | 是否已经改变图数据库 |
| --- | --- | --- |
| `GraphExtractionResult` | 模型和校验器认为文本中有什么 | 否 |
| `GraphIngestionPlan` | 本次确认后应该改变什么 | 否 |
| `GraphIngestionOperation` | 一次执行已经进行到哪一步 | 记录进度，不等于写图 |
| `GraphDocumentState` | 某个文档当前哪个版本已经生效 | 保存外部状态 |

可以用下面的关系理解它们：

```text
文本
  -> GraphExtractionResult
  -> GraphIngestionPlan
  -> GraphIngestionOperation
  -> GraphDocumentState
```

它们不是同一个对象的不同名称，也不应全部合并成一条“任务状态”。

## 生命周期阶段

### 1. 抽取：发现候选知识

`GraphExtractionPipeline` 根据文档、Schema 和抽取选项生成 `GraphExtractionResult`。结果包含全部候选、合法候选、证据、问题、实体归一结果和待审核 `GraphMutation`。

这一阶段可以完全脱离图数据库运行，适合离线评估、审核预览和模型回放。它不应直接调用 `GraphWriter`。

### 2. 规划：计算本次应该改变什么

`GraphIngestionService.plan(...)` 结合当前 `GraphDocumentState`、实体注册表和文档版本，判断本次请求属于：

- 首次导入；
- 同一文档的新版本；
- 内容和配置都没有变化的重复提交；
- 需要撤回的文档。

规划结果是 `GraphIngestionPlan`，其中包含新旧状态、Mutation、`staleEdgeKeys`、实体注册项和目标 Space。计划阶段不写图，也不提交文档状态，是接入审核和风险门槛的主要边界。

### 3. 审核：决定计划是否可以执行

开发者可以根据候选证据、质量问题、实体匹配和过期关系建立自动或人工审核规则。审核通过后，应执行审核过的原计划；不要审核一份结果，再重新调用模型生成另一份计划。

SDK 通过 `GraphReviewStore` 和 `GraphReviewService` 提供审核任务的创建、查询、修改、接受、拒绝和归档；
审核页面、审批权限和具体 HTTP 路由仍由应用负责。审核细节见[审核](/zh/graph/extractor/review)。

### 4. 执行：产生跨系统副作用

`execute(plan, writer)` 使用计划中的原始 `GraphMutation` 和 `GraphOptions` 调用 `GraphWriter`。图写入成功后，服务继续保存实体注册、使用 revision CAS 提交文档状态和版本历史，最后推进操作阶段。

执行阶段不应重新抽取、改变 Schema 或重新计算关系差异。这样才能保证审核内容、恢复内容和实际写入内容一致。

### 5. 完成与对账：确认当前状态

操作完成后，产品仍应查询关键节点、关系和来源进行对账。`COMPLETED` 表示流程步骤完成，不代表模型事实一定正确，也不代表图投影与外部状态永远不会出现差异。

生产系统应保留 operationId、planFingerprint、文档 revision 和写入结果，便于问题定位和人工修复。

### 6. 更新、撤回和恢复

同一 `documentId` 的新版本会计算新增、继续存在和过期关系。过期关系先进入 `staleEdgeKeys`，是否删除取决于其他文档是否仍然支持它。

`planRetraction(space, documentId, graphOptions)` 用于撤回整个逻辑文档。撤回不是提交空文档，而是生成 `RETRACTION` 计划并保存 `RETRACTED` 墓碑状态。

`resume(operationId, writer)` 从持久化操作中读取原计划继续执行，不重新调用模型。故障恢复细节见[故障恢复](/zh/graph/extractor/recovery)。

## 状态与副作用边界

| 对象或动作 | 回答的问题 | 是否写图 |
| --- | --- | --- |
| `GraphExtractionResult` | 文本中发现了哪些候选知识 | 否 |
| `GraphIngestionPlan` | 本次确认后应该改变什么 | 否 |
| `execute(plan, writer)` | 按计划执行哪些图变更 | 是 |
| `GraphIngestionOperation` | 跨系统执行到了哪一步 | 间接记录 |
| `GraphDocumentState` | 某文档当前哪个版本已生效 | 否，保存外部状态 |
| `resume(operationId, writer)` | 如何从中断处继续 | 按阶段可能写图 |
| `planRetraction(...)` | 如何生成文档撤回变更 | 否 |

`GraphExtractionResult.hasErrors()` 只表示存在 ERROR 级问题，不表示结果为空、Mutation 为空或已经写入数据库。

## 最小代码示例：先计划，再执行

高风险或需要人工审核的场景，建议把计划和执行拆开：

~~~java
// 1. 根据文档、Schema、版本和当前状态生成计划。
GraphIngestionPlan plan =
    ingestion.plan(document, schema, request);

// 2. 审核候选、证据和可能失效的旧关系。
review(
    plan.getExtractionResult(),
    plan.getStaleEdgeKeys(),
    plan.getMutation());

// 3. 只有确认后才产生图数据库副作用。
GraphIngestionResult result =
    ingestion.execute(plan, graphStore.writer());

if (!result.isSuccess()) {
    // 进入重试、对账或人工修复流程。
}
~~~

这段代码中：

1. `plan(...)` 只计算变更，不写图；
2. `review(...)` 代表开发者自己的自动规则或审核后台；
3. `execute(...)` 才会调用 `GraphWriter`，并继续提交实体注册和文档状态；
4. `result` 表示本次执行结果，不代表模型事实永远正确。

如果业务已经确定可以自动接受，也可以使用 `ingest(...)` 组合规划和执行。但对于高风险关系、撤回操作和需要展示证据的场景，不建议跳过计划审核边界。

## 首次导入、更新和撤回

### 首次导入

首次导入通常是：

```text
没有文档状态
  -> 抽取文档
  -> 生成 INGESTION 计划
  -> 写入节点和关系
  -> 保存 ACTIVE 文档状态
```

首次导入也必须使用稳定的 `documentId`、Schema 版本、实体注册和事实来源。否则下一批文档到来时，系统无法判断它们是在引用已有实体，还是产生了新的实体。

### 同一文档更新

同一个 `documentId` 收到新版本时，服务会比较旧版本和新版本：

```text
新增关系   = 新版本关系 - 旧版本关系
继续存在   = 新版本关系 ∩ 旧版本关系
过期关系   = 旧版本关系 - 新版本关系
```

过期关系先放入 `staleEdgeKeys`，不代表一定删除。只有确认没有其他 ACTIVE 文档支持该关系，或者业务明确采用删除策略时，才生成删除变化。

### 文档撤回

文档撤回表示它不再是当前知识来源。应使用：

~~~java
GraphIngestionPlan plan =
    ingestion.planRetraction(
        "company_knowledge",
        "meeting-2026-001",
        GraphOptions.ofSpace("company_knowledge"));

ingestion.execute(plan, graphStore.writer());
~~~

撤回不是提交一份空文档。服务需要根据其他来源判断哪些关系可以删除，并保存 `RETRACTED` 墓碑，防止延迟消息或旧版本重新激活文档。

## 为什么需要持久化计划

下面这个故障窗口很常见：

```text
图数据库写入成功
  -> 进程退出
  -> 文档状态还没有提交
```

如果恢复时重新调用模型，可能得到另一份候选和另一份 Mutation，审核结果也无法对应。生产环境应保存原始计划、`operationId` 和 `planFingerprint`，恢复时执行原计划：

~~~java
GraphIngestionResult recovered =
    ingestion.resume(
        operationId,
        graphStore.writer());
~~~

`resume(...)` 不重新抽取模型，而是根据操作阶段跳过已经确认完成的步骤，继续后续步骤。详细故障窗口和恢复策略见[故障恢复](/zh/graph/extractor/recovery)。

## 与文档状态的区别

“知识入图生命周期”关注一次知识处理操作如何执行、恢复和完成；核心类中的 `GraphDocumentState` 和 `GraphFactSource` 关注某个 `Space + documentId` 的版本、事实来源和 ACTIVE/RETRACTED 状态。

可以这样理解：

```text
知识入图生命周期 = 一次操作如何推进
文档状态与事实来源 = 文档作为知识来源如何演进
```

两者通过文档 revision、operationId 和事实来源关联，但不是同一个状态机。

## 产品接入方式

开发者可以在自己的后台中围绕这些边界实现导入任务：

```text
接收文档
  -> extract / plan
  -> 保存候选、证据、问题和计划
  -> 自动规则或人工审核
  -> execute
  -> 查询写入结果和操作阶段
  -> 对账、重试、撤回或修复
```

SDK 提供数据模型、入图计划、审核任务服务和恢复入口；文件上传、任务调度、审核 UI、审批权限、死信队列和告警由应用负责。

## 常见误区

### 把抽取结果当成已入图

抽取只产生候选。没有显式执行 `GraphWriter` 或 `execute`，图数据库不会改变。

### 审核后重新调用模型

重新调用可能得到不同结果，使审核内容和实际写入内容不一致。应持久化并执行原计划。

### 用空文档表示撤回

空文档无法表达撤回墓碑，也可能被当作不完整更新。应使用 `planRetraction`。

### 用 operationId 作为节点或边身份

operationId 标识一次业务操作；节点由实体归一产生，边由 `GraphEdgeKey` 标识，三者不能混用。

## 下一步阅读

- [核心类](/zh/graph/extractor/core-classes)：了解阶段之间传递的数据、状态和扩展类；
- [审核](/zh/graph/extractor/review)：了解如何审核候选和执行计划；
- [知识入图](/zh/graph/extractor/ingestion)：了解版本差异、写入顺序和撤回语义。
