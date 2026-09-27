# 生产实践

## 概述

默认组件提供一条完整、可替换的参考流水线，但生产知识图谱通常需要领域模型、主数据、审核策略和持久化基础设施。扩展时应保持各阶段职责清晰，避免把模型调用、实体身份、数据库写入和任务调度重新耦合到一个类中。

## 扩展点

| 接口 | 适合扩展的内容 |
| --- | --- |
| `GraphExtractor` | 替换 LLM，接入规则引擎或其他 NLP 服务 |
| `GraphExtractionPromptBuilder` | 领域提示、示例和输出协议 |
| `GraphCandidateParser` | 解析厂商结构化输出 |
| `GraphCandidateValidator` | 词表、租户、合规和业务约束 |
| `GraphEntityResolver` | 指代、同名消歧和已有实体匹配 |
| `GraphEntityIdGenerator` | 接入业务主键或 ID 服务 |
| `GraphMutationMapper` | 自定义属性、证据状态和图投影 |
| `GraphEntityRegistry` | 长期实体身份存储 |
| `GraphDocumentStateStore` | 文档当前状态、历史和来源索引 |
| `GraphIngestionOperationStore` | 持久化计划和恢复状态机 |
| `GraphIngestionLockProvider` | 多实例文档级互斥 |

优先替换最小必要组件。例如，仅增加业务校验时实现 Validator，不要复制整个 Pipeline。

## 推荐部署结构

~~~text
文件/业务数据入口
  -> 文档解析与版本存储
  -> 任务调度与租约
  -> GraphExtractionPipeline
       -> ChatModel
       -> Parser / Validator / Resolver
  -> 审核或自动策略
  -> IncrementalGraphIngestionService
       -> GraphWriter
       -> DocumentStateStore
       -> EntityRegistry
       -> OperationStore
  -> 对账、监控和人工修复
~~~

GraphStore 和模型客户端通常作为长期资源复用；Document、Request、Result 和 Plan 是任务级对象。

## 审核后台需要哪些 SDK 数据

SDK 不实现 UI，但开发者可以使用：

- 全部候选与合法候选；
- 类型、属性、别名和关系端点；
- documentId、chunkId、引文和偏移；
- confidence 与 assertionType；
- GraphExtractionIssue；
- 候选到 nodeId 的归一映射；
- staleEdgeKeys；
- 最终 GraphMutation；
- previousState、nextState 和 revision；
- operationId、阶段和失败原因；
- 当前与历史事实来源。

产品层可以围绕这些数据实现接受、拒绝、修改、实体匹配、关系撤销和重试，但审核动作本身也应保存版本和审计记录。

## 持久化设计建议

### 文档状态

至少建立：

- 当前状态表：`Space + documentId` 唯一；
- 版本历史表：`Space + documentId + revision` 唯一；
- operationId 唯一索引；
- edgeKey 到活动文档的反向来源索引；
- 状态、来源更新时间和提交时间索引。

### 实体注册

至少保存 Space、类型、nodeId、规范名称、别名、创建来源和更新时间。规范化键应有唯一约束，歧义不能静默覆盖。

### 操作日志

操作记录与序列化计划应原子创建，阶段更新使用 CAS。计划格式需要版本字段，以便 SDK 升级后仍能识别或迁移历史任务。

## 可观测性

建议按 Space、模型、Schema 版本和任务类型记录：

- 文档、Chunk、候选和合法候选数量；
- 实体与关系接受率；
- issue code 和 severity；
- 模型耗时、Token、费用、限流和失败；
- 实体注册命中、新建和歧义数；
- Mutation 节点、边和删除数量；
- UNCHANGED、成功、失败和撤回数量；
- CAS 冲突、锁等待和恢复次数；
- 各操作阶段停留时间；
- 写入后对账差异。

指标中不要使用完整原文或高基数敏感名称作为标签。

## 容量与限流

并发度同时受以下系统约束：

- 模型账号 QPS 和并发；
- 单文档 Chunk 数；
- JVM 内存和响应大小；
- 文档状态数据库连接池；
- 图数据库写入能力；
- 同一实体和同一文档的热点冲突；
- 审核队列处理能力。

建议分别设置模型调用并发、文档任务并发和图写入并发，不要只用一个无限线程池。不同租户还需要配额和公平调度。

## 数据质量闭环

上线后持续维护：

1. 从生产问题和人工修改中采集脱敏样本；
2. 更新标注集；
3. 比较模型、Prompt、Schema 和 Resolver 版本；
4. 小流量或隔离 Space 验证；
5. 审核差异和回归指标；
6. 分批发布；
7. 对历史数据按明确计划重抽或迁移。

不能只关注“抽取接口成功率”。真正的指标是最终图数据能否回答业务问题、是否可追溯、是否能正确更新和撤回。

## 写入后的验证

任务 COMPLETED 仍不代表业务图谱正确。至少验证：

- Mutation 目标节点和边可以被查询；
- 关系端点与 Schema 类型一致；
- 文档状态中的 EdgeKey 与图中投影可对账；
- Registry nodeId 在图中存在；
- 当前来源能解释关键关系；
- 撤回后共享关系仍保留，独占关系按策略清理；
- 关键查询的结果数量和延迟符合预期。

Neo4j 与 Nebula 的写入和查询差异应在真实 Docker 或生产等价环境中回归。

## 版本升级

升级 SDK、模型、Schema 或自定义组件前应确认：

- 序列化计划是否向后兼容；
- fingerprint 算法变化是否导致全量重抽；
- 实体规范化规则是否改变 nodeId 或匹配；
- Mutation 映射是否改变 EdgeKey；
- 文档状态新增字段如何回填；
- 未完成操作由旧版本还是新版本恢复；
- 是否需要暂停接收新任务；
- 是否具备回滚和双读验证。

不要让新版本直接接管无法解析的旧计划。

## 灾难恢复

备份范围不应只有图数据库，还应包括：

- 原始文档及版本；
- Schema 和抽取配置；
- 文档当前状态与历史；
- 实体注册表；
- 操作记录及原始计划；
- 审核决策；
- 事实来源。

恢复演练应验证从这些资料重建图投影，而不只是恢复一份数据库快照。

## 明确能力边界

当前模块不会自动提供：

- 分布式任务调度；
- 持久化默认实现；
- 人工审核工作流；
- 跨系统原子事务；
- 任意实体的完美消歧；
- 模型事实正确性保证；
- 孤立实体自动回收；
- 模型费用与供应商 SLA。

这些不是缺少一个配置项就能解决的问题，而是开发者产品和基础设施的一部分。SDK 的价值是提供清晰模型、执行计划和扩展契约。

## 上线检查清单

### 模型与 Schema

- Schema 是否经过业务评审并版本化；
- 模型、Prompt、Parser 和 Resolver 是否有配置指纹；
- 是否有真实领域评估集和发布门槛；
- 推断、观点和明确事实是否区分。

### 身份与来源

- documentId、nodeId 和 EdgeKey 是否稳定；
- 实体注册是否持久化并按 Space 隔离；
- evidence 是否能定位原文；
- 当前和历史事实来源是否可查询。

### 增量与恢复

- 新文件、文档更新和撤回是否分别测试；
- 部分抽取错误是否阻止误删；
- revision、operationId、planFingerprint 和锁是否同时存在；
- 未完成操作能否在进程重启后按原计划恢复；
- 未知提交结果是否有对账。

### 安全与运维

- 模型凭据、原文和响应是否受控；
- 并发、QPS、超时、重试和费用是否有限制；
- 任务状态和质量指标是否可观察；
- 是否有死信、告警和人工修复；
- 图数据和状态数据是否共同备份并演练恢复。

## 章节导航

- [知识抽取模块概览](/zh/graph/knowledge-extraction)
- [Schema 驱动抽取](/zh/graph/knowledge-extraction-schema)
- [知识抽取流程](/zh/graph/knowledge-extraction-pipeline)
- [知识入图生命周期](/zh/graph/knowledge-extraction-flow)
- [知识抽取数据模型](/zh/graph/knowledge-extraction-contract)
- [候选质量审核](/zh/graph/knowledge-extraction-quality)
- [审核工作流](/zh/graph/knowledge-extraction-review)
- [实体归一](/zh/graph/knowledge-extraction-entity-resolution)
- [增量入图](/zh/graph/knowledge-extraction-ingestion)
- [文档生命周期](/zh/graph/knowledge-extraction-lifecycle)
- [状态模型](/zh/graph/knowledge-extraction-state)
- [故障恢复](/zh/graph/knowledge-extraction-recovery)
- [错误处理](/zh/graph/knowledge-extraction-errors)
- [模型接入](/zh/graph/knowledge-extraction-model-security)
- [扩展接口](/zh/graph/knowledge-extraction-extension)
