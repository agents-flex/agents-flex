# 知识入图生命周期

## 概述

知识抽取流程得到的只是“可能存在的知识”。这些候选还要经过审核、规划和写入，才能成为知识库中可以查询、更新和撤回的图数据。

本章讲的是一条知识从文本出现，到进入 Graph Space，再到后续更新、撤回和恢复的完整生命周期。它关注的是执行边界和状态变化，而不是 Chunk 如何分段、模型如何生成候选；一次抽取的具体处理步骤见[知识抽取流程](/zh/graph/knowledge-extraction-pipeline)。

## 解决什么问题

如果只调用模型并立即写图，应用很快会遇到几个问题：

- 审核前无法阻止错误候选进入图数据库；
- 同一文档重复导入时会重复调用模型和写入数据；
- 文档更新后，不知道哪些旧关系应该保留或撤回；
- 图写入成功、状态保存失败时，重试可能造成不一致；
- 文档撤回时，无法判断一条关系是否还有其他来源支持。

知识入图生命周期通过 `extract`、`plan`、`execute`、`resume` 和 `planRetraction` 这些边界，把“发现知识”和“改变图数据”分开管理。

## 一条知识如何进入图数据库

以文档“林默加入青云宗”为例：

```text
文档文本
  -> 知识抽取流程得到人物、组织和关系候选
  -> 校验证据、Schema 和置信度
  -> 归一“林默”和“青云宗”的长期实体身份
  -> 生成增量入图计划
  -> 自动规则或人工审核
  -> 执行 GraphMutation
  -> 提交文档版本、事实来源和操作状态
```

只有执行计划并完成状态提交后，这条知识才算成为当前图数据的一部分。前面的抽取结果和计划都可以保存、审核或废弃，但不会自动改变图数据库。

## 生命周期阶段

### 1. 抽取：发现候选知识

`GraphExtractionPipeline` 根据文档、Schema 和抽取选项生成 `GraphExtractionResult`。结果包含全部候选、合法候选、证据、问题、实体归一结果和待审核 `GraphMutation`。

这一阶段可以完全脱离图数据库运行，适合离线评估、审核预览和模型回放。它不应直接调用 `GraphWriter`。

### 2. 规划：计算本次应该改变什么

`IncrementalGraphIngestionService.plan(...)` 结合当前 `GraphDocumentState`、实体注册表和文档版本，判断本次请求属于：

- 首次导入；
- 同一文档的新版本；
- 内容和配置都没有变化的重复提交；
- 需要撤回的文档。

规划结果是 `IncrementalGraphIngestionPlan`，其中包含新旧状态、Mutation、`staleEdgeKeys`、实体注册项和目标 Space。计划阶段不写图，也不提交文档状态，是接入审核和风险门槛的主要边界。

### 3. 审核：决定计划是否可以执行

开发者可以根据候选证据、质量问题、实体匹配和过期关系建立自动或人工审核规则。审核通过后，应执行审核过的原计划；不要审核一份结果，再重新调用模型生成另一份计划。

SDK 提供审核所需的数据，但不实现审核页面、审批权限或产品工作流。审核细节见[审核工作流](/zh/graph/knowledge-extraction-review)。

### 4. 执行：产生跨系统副作用

`execute(plan, writer)` 使用计划中的原始 `GraphMutation` 和 `GraphOptions` 调用 `GraphWriter`。图写入成功后，服务继续保存实体注册、使用 revision CAS 提交文档状态和版本历史，最后推进操作阶段。

执行阶段不应重新抽取、改变 Schema 或重新计算关系差异。这样才能保证审核内容、恢复内容和实际写入内容一致。

### 5. 完成与对账：确认当前状态

操作完成后，产品仍应查询关键节点、关系和来源进行对账。`COMPLETED` 表示流程步骤完成，不代表模型事实一定正确，也不代表图投影与外部状态永远不会出现差异。

生产系统应保留 operationId、planFingerprint、文档 revision 和写入结果，便于问题定位和人工修复。

### 6. 更新、撤回和恢复

同一 `documentId` 的新版本会计算新增、继续存在和过期关系。过期关系先进入 `staleEdgeKeys`，是否删除取决于其他文档是否仍然支持它。

`planRetraction(space, documentId, graphOptions)` 用于撤回整个逻辑文档。撤回不是提交空文档，而是生成 `RETRACTION` 计划并保存 `RETRACTED` 墓碑状态。

`resume(operationId, writer)` 从持久化操作中读取原计划继续执行，不重新调用模型。故障恢复细节见[故障恢复](/zh/graph/knowledge-extraction-recovery)。

## 状态与副作用边界

| 对象或动作 | 回答的问题 | 是否写图 |
| --- | --- | --- |
| `GraphExtractionResult` | 文本中发现了哪些候选知识 | 否 |
| `IncrementalGraphIngestionPlan` | 本次确认后应该改变什么 | 否 |
| `execute(plan, writer)` | 按计划执行哪些图变更 | 是 |
| `GraphIngestionOperation` | 跨系统执行到了哪一步 | 间接记录 |
| `GraphDocumentState` | 某文档当前哪个版本已生效 | 否，保存外部状态 |
| `resume(operationId, writer)` | 如何从中断处继续 | 按阶段可能写图 |
| `planRetraction(...)` | 如何生成文档撤回变更 | 否 |

`GraphExtractionResult.hasErrors()` 只表示存在 ERROR 级问题，不表示结果为空、Mutation 为空或已经写入数据库。

## 同“文档生命周期”的区别

“知识入图生命周期”关注一次知识处理操作如何执行、恢复和完成；“文档生命周期”关注某个 `Space + documentId` 的版本、事实来源和 ACTIVE/RETRACTED 状态。

可以这样理解：

```text
知识入图生命周期 = 一次操作如何推进
文档生命周期     = 文档作为知识来源如何演进
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

SDK 提供数据模型、计划和恢复入口；文件上传、任务调度、审核 UI、审批权限、死信队列和告警由应用负责。

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

- [知识抽取数据模型](/zh/graph/knowledge-extraction-contract)：了解阶段之间传递的数据；
- [审核工作流](/zh/graph/knowledge-extraction-review)：了解如何审核候选和执行计划；
- [增量入图](/zh/graph/knowledge-extraction-ingestion)：了解版本差异和写入顺序；
- [文档生命周期](/zh/graph/knowledge-extraction-lifecycle)：了解来源、版本和撤回语义。
