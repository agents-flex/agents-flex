# 状态模型

## 概述

知识抽取会产生候选、执行计划、文档版本和操作阶段。它们都可以被称为“状态”，但代表不同时间尺度和一致性边界。若把这些对象混在一张任务表中，产品很难回答“模型是否抽取完成”“计划是否审核”“图是否写入”以及“文档当前版本是否生效”。

SDK 将入图数据拆成四层；选择人工审核时，另外用 `GraphReviewTask` 管理批准过程：

```text
GraphExtractionResult
  -> GraphIngestionPlan
  -> GraphIngestionOperation
  -> GraphDocumentState
```

这不是四个连续覆盖的字段，而是四类可以独立保存和查询的业务对象。

## 四类状态对象

### 抽取结果

`GraphExtractionResult` 描述一次完整文档抽取的结果，包括全部候选、合法候选、问题、实体归一结果和待审核 Mutation。

它回答：

- 模型和规则从文本中识别到了什么；
- 哪些候选通过了当前校验；
- 是否存在 WARNING 或 ERROR；
- 候选将映射到哪些节点和边。

它不回答图是否写入，也不代表文档版本已经生效。

### 执行计划

`GraphIngestionPlan` 描述基于某个文档 revision 准备执行的变更，其状态为：

```text
UNCHANGED   内容和配置没有变化，无需写图
READY       首次导入或新版本已经形成变更计划
RETRACTION  准备撤回当前文档来源
```

计划包含 old/new state、Mutation、staleEdgeKeys、实体注册项和 GraphOptions。它回答“如果确认执行，应该改变什么”，但创建计划本身没有副作用。

### 文档状态

`GraphDocumentState` 表示某个 `Space + documentId` 当前已经提交的版本：

```text
ACTIVE <-> RETRACTED
```

它还记录 revision、operationId、内容摘要、Schema 与抽取配置版本、节点和边身份、事实来源及版本时间。当前状态用于后续判重和差异计算，历史快照用于审计和回放。

`RETRACTED` 是有效墓碑，不等于删除状态记录。保留墓碑才能阻止旧消息把已撤回版本重新激活。

### 操作状态

`GraphIngestionOperation` 描述一次执行跨越图数据库、实体注册表和状态库时推进到了哪里：

```text
PREPARED
  -> GRAPH_APPLIED
  -> STATE_COMMITTED
  -> COMPLETED

PREPARED -> FAILED -> PREPARED
```

它回答“副作用执行到哪一步”，用于恢复，不用于替代文档当前状态。一个操作完成后，文档仍然可能被后续操作更新或撤回。

### 可选的审核任务

`GraphReviewTask` 把冻结的计划与人工决策连接起来。它保存 `taskId`、计划、审核状态、
`reviewVersion`、最近操作人和理由。应用通过 `GraphReviewService` 创建、查询、修改、接受、拒绝和归档任务。

自动接受路径直接执行入图计划，不创建审核任务，也不需要 `GraphReviewStore`。
人工接受开始执行后，计划不能再修改；执行中断通过审核服务的 `resume` 恢复，并同步审核状态。

审核版本与文档 revision 不同：前者防止两位审核者覆盖彼此的修改，后者防止旧计划覆盖已经生效的新文档版本。

## 对象之间的关系

| 对象 | 关键身份 | 典型生命周期 | 是否表示当前真相 |
| --- | --- | --- | --- |
| `GraphExtractionResult` | 一次抽取上下文 | 候选产生到审核 | 否 |
| `GraphIngestionPlan` | operationId + planFingerprint | 规划到执行或废弃 | 否 |
| `GraphReviewTask` | taskId + reviewVersion | 待审核到接受/拒绝/归档 | 只表示审核与执行结果 |
| `GraphIngestionOperation` | operationId | PREPARED 到 COMPLETED | 只表示执行进度 |
| `GraphDocumentState` | Space + documentId + revision | ACTIVE/RETRACTED 版本演进 | 是，已提交版本 |

一次文档更新可能产生一个抽取结果和一个 READY 计划；执行该计划时创建操作记录；操作完成后，新 `GraphDocumentState` 才成为当前已提交版本。

## 推荐持久化方式

生产系统通常应分别保存：

- 抽取结果或审核快照：保留候选、证据、问题和人工修改；
- 审核任务：只在人工流程中保存，更新使用审核版本 CAS；历史快照可由 Store 追加保存；
- 原始执行计划：与 operationId 原子创建，供恢复使用；
- 操作日志：以 operationId 唯一，阶段更新使用 CAS；
- 文档当前状态：以 `Space + documentId` 唯一，revision 使用 CAS；
- 文档版本历史：追加式保存不可变快照；
- 事实来源和实体注册：建立独立索引，支持跨文档查询。

这些数据可以位于同一数据库，但不应因为物理存储相同就合并语义。图中的 `GraphNode/GraphEdge` 是查询投影，`GraphFactSource` 是来源声明，`GraphDocumentState` 是版本状态，三者也不是同一种记录。

## 常见问题

### `COMPLETED` 是否表示抽取结果正确？

不是。它只表示执行流程完成。业务正确性仍取决于 Schema、审核、实体归一、来源和写入后验证。

### `READY` 是否可以直接展示为已入图？

不可以。READY 只表示存在可执行计划，应在 `execute` 成功并完成状态提交后展示为生效。

### 可以只保存文档状态，不保存计划吗？

可以支持基本判重，但进程在写图后、状态提交前退出时无法按原计划恢复。生产环境建议同时保存计划和操作阶段。

### 抽取有 ERROR 时文档状态会自动失败吗？

抽取结果没有统一的任务状态机。调用方根据审核策略决定是否生成或执行计划；默认应阻止自动入图和破坏性清理。

## 下一步阅读

- [知识入图生命周期](/zh/graph/knowledge-extraction-flow)：了解对象在哪个阶段产生；
- [文档生命周期](/zh/graph/knowledge-extraction-lifecycle)：了解版本、来源和撤回；
- [故障恢复](/zh/graph/knowledge-extraction-recovery)：了解操作状态机。
