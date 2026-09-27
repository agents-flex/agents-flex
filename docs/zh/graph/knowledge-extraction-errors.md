# 错误处理

## 概述

抽取系统中的“失败”不是一种情况。模型调用失败、候选质量问题、图写入失败和跨系统阶段中断，需要不同的处理方式。若把所有问题都转成一个异常，产品就无法判断是否可以重试、是否可以保留合法候选，以及是否允许推进文档状态。

本模块至少区分四层错误语义：

```text
GraphExtractionException     抽取流程无法继续
GraphExtractionIssue         候选或协议质量问题
GraphWriteResult              GraphWriter 的执行结果
GraphIngestionOperation.Stage 跨系统操作推进状态
```

## 四类错误

### `GraphExtractionException`

表示模型调用、响应解析、流水线扩展点或不可恢复协议错误，通常通过异常传播。若 `failOnChunkError=false`，单个 Chunk 的失败可能被转换为 `CHUNK_EXTRACTION_FAILED` issue，让其他 Chunk 继续处理；是否允许部分结果入图仍由业务策略决定。

常见原因：

- ChatModel 超时、限流或认证失败；
- 响应超过大小上限；
- 根 JSON 缺少 `entities` 或 `relations`；
- 自定义 Parser、Validator 或 Resolver 抛出异常；
- 必需的 Schema 或请求字段缺失。

### `GraphExtractionIssue`

表示可以被记录、统计和分流的结构化问题。单条候选格式错误、未知属性、证据不匹配、端点类型错误和低置信度通常属于这一层。

`WARNING` 不一定阻止计划，`ERROR` 通常应阻止自动接受。但 `GraphExtractionResult.hasErrors()` 只说明存在 ERROR，不会替调用方决定审核策略，也不代表合法候选不存在。

### `GraphWriteResult`

表示 `GraphWriter` 是否接受并执行了 Mutation。写入失败可能来自连接、权限、Schema、超时或后端语义差异。写入返回未知时，不能简单当作失败重跑，应先查询图状态或通过对账确认。

### `GraphIngestionOperation.Stage`

表示增量操作跨越多个系统时已经推进到哪里：

```text
PREPARED -> GRAPH_APPLIED -> STATE_COMMITTED -> COMPLETED
     \-> FAILED -> PREPARED
```

`FAILED` 只表示最近一次执行失败，不能覆盖已确认的图写入。已进入 `GRAPH_APPLIED` 后，恢复应跳过 Writer，继续实体注册和文档状态提交。

## 处理矩阵

| 错误或状态 | 是否重新调用模型 | 是否可重试写图 | 是否推进文档状态 | 推荐处置 |
| --- | --- | --- | --- | --- |
| 模型超时/限流 | 可按请求策略重试 | 否 | 否 | 指数退避，仍失败则任务失败 |
| 单条候选格式错误 | 否 | 通常不需要 | 通常否 | 保留 issue，审核合法子集 |
| 根协议损坏 | 否或重新请求结构化响应 | 否 | 否 | 转人工或修复 Parser |
| Schema/证据 ERROR | 否 | 否 | 否 | 修正候选、Schema 或审核策略 |
| Writer 明确失败 | 否 | 可按后端错误分类 | 否 | 使用原 plan 重试，不重新抽取 |
| Writer 返回未知 | 否 | 先核验再决定 | 否 | 查询图并做对账 |
| 图已成功、状态未提交 | 否 | 不应盲目重复 | 否 | 使用原 operationId 恢复 |
| revision CAS 冲突 | 否 | 不直接重放旧计划 | 否 | 读取最新状态并重新规划 |
| 状态已提交、收尾失败 | 否 | 否 | 已提交 | 从操作阶段继续收尾 |

表中的“是否推进”指自动流程的默认行为。业务若允许部分提交，必须显式记录这一策略，不能因为存在合法候选就隐式忽略错误。

## 重试原则

1. **重试同一阶段**：使用同一个 `operationId` 和原始 plan；
2. **不重试永久错误**：权限、Schema 不兼容、非法协议应转人工；
3. **未知结果先核验**：图写入超时不等于没有写入；
4. **有上限和退避**：模型、Writer、状态库分别设置超时、最大次数和退避；
5. **避免重复模型调用**：恢复和写入重试都不应无理由重新抽取；
6. **记录原因**：保存错误 code、阶段、尝试次数、时间和最终处置。

## 部分结果策略

当某些 Chunk 失败时，流水线可能仍有合法实体和关系。开发者必须在自动接受前明确选择：

- **严格模式**：有 ERROR 就不生成可执行计划或不执行；
- **审核模式**：保存合法子集，交给人工决定；
- **部分提交模式**：只写入明确安全的新增事实，禁止 stale 关系清理，并记录缺失范围。

部分提交不能被当成完整文档快照。否则下次更新会误把“本次没抽到”理解为“文档已经删除”。

## 错误边界与日志

日志中应记录 Space、documentId、chunkId、operationId、planFingerprint、阶段和稳定 issue code，但避免记录完整原文、原始模型响应和敏感实体名称。原始响应若需要用于回放，应放在受控存储并设置保留周期。

自定义扩展点应遵守异常边界：

- Parser 将可定位的候选问题转换成 issue；
- Validator 返回合法子集和问题，不静默丢弃；
- Resolver 对无法消歧的情况返回明确状态或问题；
- MutationMapper 不吞掉端点和身份冲突；
- Store 实现将后端异常转换为可诊断的写入结果或异常。

## 常见问题

### `hasErrors()` 为 false 就一定可以写入吗？

不一定。仍需检查业务审核规则、Mutation 是否为空、Schema 版本、实体冲突和当前文档 revision。

### 写入异常后重新调用 `ingest` 可以吗？

只有在确认没有持久化操作计划，或明确要重新规划时才可以。生产恢复应优先使用原 operationId 和原 plan，避免同一业务操作产生不同候选。

### 状态提交失败时能否直接重写图？

不能盲目重写。先查看操作阶段和图状态；如果图已经成功，恢复应跳过 Writer，继续提交状态或进入对账流程。

## 下一步阅读

- [故障恢复](/zh/graph/knowledge-extraction-recovery)：了解崩溃窗口和恢复入口；
- [审核工作流](/zh/graph/knowledge-extraction-review)：了解如何把质量问题转交人工；
- [生产实践](/zh/graph/knowledge-extraction-production)：了解指标、告警和对账。
