# 文档生命周期

## 概述

知识图谱中的一条关系可能同时被多份文档支持。某份文件更新或撤回时，不能简单删除它曾提到的所有实体和关系，否则会破坏其他来源仍然有效的知识。

长期维护需要区分：

~~~text
文档版本产生的事实声明
            !=
图中为了查询而物化的节点和边
~~~

事实声明描述“哪份文档、哪段证据支持什么关系”；物化边描述“当前图中可以查询到什么”。一条边可以有多个事实来源。

## 文档状态

`GraphDocumentState` 至少记录：

- Space 和逻辑 documentId；
- ACTIVE 或 RETRACTED 状态；
- 当前 revision 和 operationId；
- 内容摘要、业务版本和 Schema 版本；
- 抽取配置指纹和批次 ID；
- 来源更新时间和提交时间；
- 当前版本产生的节点 ID；
- 当前关系 `GraphEdgeKey`；
- 已被替代的关系；
- 每条关系的事实来源。

生产实现不应只覆盖当前状态，还应通过 `recordVersion` 保存不可变历史快照，用于审计和恢复。

## 文档更新如何计算差异

同一 documentId 的新版本抽取完成后，服务比较：

~~~text
新增关系 = 新版本关系 - 旧版本关系
继续存在 = 新版本关系 ∩ 旧版本关系
过期关系 = 旧版本关系 - 新版本关系
~~~

过期关系进入 `staleEdgeKeys`。它只是计划信息，不代表一定删除。

节点通常不会因当前文档不再提到就自动删除，因为它可能被其他关系、其他文档或外部系统继续引用。

## 旧关系处理策略

### KEEP

默认策略。报告 stale 关系，但不生成删除操作。

适合：

- 初次上线增量流程；
- 尚未建立完整来源索引；
- 抽取结果需要人工审核；
- 不能接受误删；
- 图中保留历史关系，由状态或时间属性区分。

### DELETE_IF_UNREFERENCED

只有同一 Space 的其他 ACTIVE 文档状态不再引用该 `GraphEdgeKey` 时，才把它加入 Mutation 删除集合。

这要求 `GraphDocumentStateStore.isReferencedByOtherDocument` 在并发环境下给出可信结果。默认实现扫描状态列表，生产数据量大时应建立 edgeKey 到活动文档的反向索引。

## 部分抽取为什么不能触发删除

假设新版本有十个 Chunk，其中两个因模型限流失败。缺失的关系可能只是没有被处理，而不是已经从文档中消失。

因此默认行为是：

- 抽取结果有 ERROR 时拒绝执行计划；
- 即使允许提交合法子集，也保留旧关系；
- 只有明确启用 `allowPartialReconciliation(true)`，部分结果才参与 stale 关系删除。

最后一个选项风险很高，只适合调用方能够证明失败 Chunk 与需要清理的关系无关。

例如，允许提交合法子集，但仍保留失败分段可能支持的旧关系：

~~~java
GraphIngestionRequest request = GraphIngestionRequest.builder("knowledge", "doc-001")
    .extractionOptions(GraphExtractionOptions.builder()
        .failOnChunkError(false).build())
    .failOnExtractionError(false)
    .allowPartialReconciliation(false)
    .build();
~~~

`failOnChunkError(false)` 让流水线在分段异常后继续处理，`failOnExtractionError(false)` 允许
入图服务为含 ERROR 的结果生成计划；`allowPartialReconciliation(false)` 则防止部分结果
撤销旧版本的来源。三个选项控制不同阶段，不能相互替代。

## 文档撤回

撤回表示整个逻辑文档不再作为当前知识来源。使用 `planRetraction` 生成撤回计划，而不是伪造一份空文档更新。

撤回流程应：

1. 读取当前 ACTIVE 状态；
2. 根据来源引用判断哪些关系可以删除；
3. 生成 RETRACTED 新状态；
4. 写入必要的边删除；
5. 保存撤回历史和操作阶段。

RETRACTED 墓碑非常重要。直接删除状态会让延迟消息或重复任务把旧版本重新导入而缺少版本防护。

当前服务不会自动删除共享实体节点。孤立节点回收应由独立、可预览和可审核的生命周期任务完成。

## 事实来源模型

`GraphFactSource` 描述一份文档对一条物化边的独立声明，包含：

- 稳定 factId；
- operationId；
- 文档 revision；
- 创建时间；
- GraphEdgeKey；
- GraphEvidence；
- confidence；
- GraphAssertionType；
- 来源文档和分段上下文。

同一条关系可以有多份独立来源。例如，两份不同文档都记载“张三任职于星河科技”，会各自形成一条
`GraphFactSource`，共同支持同一个 `GraphEdgeKey`。撤回其中一份文档时，SDK 可以检查剩余来源，
判断这条关系是否仍应保留。来源记录也会保存抽取时的关系属性快照；其可信程度应结合原文证据、
断言类型和审核结果判断。

factId 包含 Space 作用域，因此相同证据在不同知识库中不会被误认为同一事实。

## 查询当前与历史来源

`GraphDocumentStateStore` 提供两种语义：

- `findCurrentFactSources`：只返回当前 ACTIVE 文档版本仍然有效的来源；
- `findFactSourceHistory`：遍历当前和历史版本，供审计与回放。

大规模生产实现不应依赖默认全表扫描，应对 Space、edgeKey、documentId、status 和 revision 建立索引。

## 是否把事实来源也写入图中

SDK 默认把来源保存在外部文档状态中，物化边作为查询投影。业务如果需要在图查询中遍历来源，可以显式建模：

~~~text
(DocumentVersion)-[ASSERTS]->(Fact)
(Fact)-[SOURCE]->(Entity)
(Fact)-[TARGET]->(Entity)
~~~

这样可以查询多个来源、冲突事实和时间演变，但会增加节点、边和维护成本。是否物化取决于查询需求，不应默认把全部运行状态塞入业务图。

## 关系身份与多次事件

来源引用以完整 `GraphEdgeKey` 为单位：

~~~text
sourceId + type + targetId + rank
~~~

如果同一对实体之间确实存在多次独立事件，需要稳定 rank，或把事件建模为节点。随机 rank 会让新旧版本无法正确匹配，导致旧关系永远无法识别。

## 冲突事实

两份文档可能对同一问题给出不同结论。不要简单按最后写入覆盖所有信息。可选策略包括：

- 同时保留多个带来源的 Fact；
- 按来源权威等级决定当前投影；
- 让人工审核选择；
- 把有效时间和确认状态建模出来；
- 将争议状态暴露给查询层。

Graph Extractor 提供来源和断言类型，但不会替业务决定哪个来源是真相。

## 常见问题

### 文档删除后可以直接删除所有 nodeId 吗？

不可以。节点可能被其他文档和关系引用。默认撤回只处理可安全撤销的关系。

### staleEdgeKeys 是否已经删除？

不是。它表示新版本不再包含的旧关系；是否进入 Mutation 由 staleRelationPolicy、其他来源和部分结果策略决定。

### 其他文档也支持同一关系时会怎样？

`DELETE_IF_UNREFERENCED` 会查询其他 ACTIVE 文档，仍有引用时保留物化边，只撤销当前文档的事实声明。

### 为什么需要保留历史版本？

为了回答某条关系何时出现、为什么撤回、使用了哪个模型和 Schema，以及在恢复或审计时重建当时状态。

## 生产检查清单

- 当前状态和不可变版本历史是否分开保存；
- stale 关系是否先展示和审核，而不是默认删除；
- edgeKey 反向引用查询是否有索引且并发语义可靠；
- 部分 Chunk 失败时是否禁止关系删除；
- 撤回是否写入墓碑并拒绝乱序旧版本；
- 是否避免自动删除共享实体；
- 事实来源是否包含证据、revision、operationId 和断言类型；
- 当前来源与历史来源查询是否明确区分；
- rank 是否稳定支持新旧关系比较；
- 冲突来源是否有业务裁决策略。

这些跨系统步骤的失败恢复机制见[故障恢复](/zh/graph/knowledge-extraction-recovery)。
