# 审核工作流

## 概述

知识抽取的结果是模型候选，不是自动生效的业务真相。对于高风险关系、同名实体、文档撤回和属性冲突，产品通常需要让开发者自己的后台展示证据、修改候选并决定是否执行。

SDK 的职责是提供可审核的数据和确定性的执行边界；它不负责实现 UI、审批权限、用户组织、审核状态机或工作流引擎。

## 推荐流程

```text
模型候选
  -> 结构和证据校验
  -> 展示候选与原文
  -> 接受、修改或拒绝
  -> 实体匹配与关系撤回审核
  -> 确认 GraphMutation / IncrementalGraphIngestionPlan
  -> execute(plan, writer)
  -> 保存审核记录和写入结果
```

审核台可以由应用自行实现，但应始终基于同一份候选和计划执行，避免界面显示的内容与最终写入内容不一致。

## 审核者需要哪些数据

### 候选和证据

- `allEntities`、`allRelations`：包括被拒绝的候选，便于解释模型输出；
- `entities`、`relations`：通过结构和质量校验的合法子集；
- `GraphEvidence`：文档、Chunk、引文和偏移；
- `confidence` 与 `assertionType`：辅助风险排序，不是事实概率；
- `GraphExtractionIssue`：说明为什么候选未通过。

### 归一和变更

- `GraphEntityResolution`：候选提及到规范实体的映射；
- `nodeId` 和 `GraphEdgeKey`：展示将影响哪个图身份；
- `GraphMutation`：确认后真正准备交给 Writer 的节点和边变化；
- `staleEdgeKeys`：新版本不再出现的旧关系，默认不等于删除。

### 增量和操作

- `previousState`、`nextState` 和 expected revision；
- `operationId`、`planFingerprint` 和操作阶段；
- 文档版本、Schema 版本和 extraction fingerprint；
- 当前与历史事实来源。

## 审核决策建议

### 自动拒绝

以下情况通常不应进入审核后的 Mutation：

- 类型、属性或端点不在 Schema 中；
- evidence 不属于当前 Chunk，或偏移与引文不一致；
- 协议根对象损坏；
- 低于业务阈值的候选；
- 禁止的 `INFERRED` 或 `OPINION` 断言。

### 人工确认

以下情况建议进入人工队列：

- 一个名称匹配多个 Registry 实体；
- 关系会触发删除或撤回；
- 候选包含推断、观点或敏感属性；
- 多份来源对同一事实冲突；
- 新概念尚未纳入 Schema。

### 自动接受

只有低风险类型、证据完整、经过领域评估并且错误成本可接受时，才适合自动执行。自动接受规则应版本化，记录使用的模型、Schema、Prompt、Validator 和审核策略指纹。

## 修改候选时的边界

产品可以在执行前修改候选属性、选择不同实体匹配或拒绝某条关系，但修改后应重新生成或更新待执行 Mutation，并重新计算计划指纹。不能只修改界面展示字段，然后继续执行旧 Mutation。

如果修改导致 Schema 不兼容、关系端点变化或文档状态变化，应重新运行 Validator 或重新生成 plan。审核记录中应同时保存原始候选、修改后候选、操作者、时间和原因。

## stale 关系审核

`staleEdgeKeys` 表示当前文档新版本不再产生的旧关系。它可能仍被其他 ACTIVE 文档支持，因此审核者要区分：

```text
旧关系不再出现在本版本
  -> 查询其他有效来源
  -> 仍有来源：只撤销当前事实
  -> 无其他来源：按策略决定是否删除物化边
```

抽取结果存在 ERROR 或 Chunk 不完整时，默认禁止使用不完整结果清理旧关系。只有调用方明确证明失败范围与待清理关系无关，才考虑允许部分对账。

## 审核记录建议

SDK 不规定审核表结构，但产品至少应保存：

| 信息 | 用途 |
| --- | --- |
| operationId、planFingerprint | 关联一次具体执行意图 |
| 候选和证据版本 | 还原审核时看到的内容 |
| 决策人、时间、决策 | 责任和审计 |
| 修改前后差异 | 解释人工干预 |
| 拒绝或修改原因 | 质量反馈和模型评估 |
| 执行结果和操作阶段 | 关联最终写入状态 |

审核记录应是追加式或带版本的，不要直接覆盖原始模型结果。

## SDK 与产品边界

| 能力 | SDK 提供 | 产品需要提供 |
| --- | --- | --- |
| 候选、证据和问题 | 数据模型 | 展示和搜索界面 |
| Mutation 和执行计划 | 可审核执行对象 | 接受、修改、拒绝按钮 |
| operationId 和阶段 | 恢复所需状态 | 任务队列、租约和告警 |
| Entity Registry | 身份读写接口 | 业务主数据和权限 |
| 审核事实 | 原始字段和来源 | 审核人、审批链、审计存储 |

## 下一步阅读

- [知识入图生命周期](/zh/graph/knowledge-extraction-flow)：明确 plan、execute、resume 的副作用；
- [错误处理](/zh/graph/knowledge-extraction-errors)：按错误类型决定重试或转人工；
- [文档生命周期](/zh/graph/knowledge-extraction-lifecycle)：了解撤回和共享来源。
