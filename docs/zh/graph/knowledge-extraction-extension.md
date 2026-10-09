# 扩展接口

## 概述

不同业务使用不同模型、词典、主数据和持久化设施。Graph Extractor 因此把模型调用、协议解析、质量校验、实体归一、图变更映射和长期状态拆成独立扩展接口。

扩展的目标不是复制整个流水线，而是在保持阶段契约的前提下替换一个职责。这样审核、恢复和增量语义仍然一致，也便于测试自定义实现。

## 按职责查找 SDK 类型

模块的包路径统一以 `com.agentsflex.graph.extractor` 开始。核心入口、请求、选项和结果位于根包，
扩展策略及长期状态按职责放在子包中：

| 包 | 主要职责 | 代表类型 |
| --- | --- | --- |
| 根包 | 抽取入口和完整流程编排 | `GraphExtractor`、`GraphExtractionPipeline`、`GraphExtractionResult` |
| `model` | 候选知识和原文证据 | `GraphEntityCandidate`、`GraphRelationCandidate`、`GraphEvidence` |
| `prompt` | 组织模型输入 | `GraphExtractionPromptBuilder`、`JsonGraphExtractionPromptBuilder` |
| `parser` | 解析模型输出 | `GraphCandidateParser`、`JsonGraphCandidateParser` |
| `validation` | 校验候选结构和质量 | `GraphCandidateValidator` |
| `resolution` | 决定候选对应哪个长期实体 | `GraphEntityResolver`、`GraphEntityResolutionResult` |
| `registry` | 保存和查询已确认的实体身份 | `GraphEntityRegistry`、`GraphRegisteredEntity`、`InMemoryGraphEntityRegistry` |
| `mapping` | 将候选知识转换为待写入变更 | `GraphCandidateMutationMapper` |
| `ingestion` | 生成和执行入图计划，维护文档状态和恢复记录 | `GraphIngestionService`、`GraphIngestionPlan` |
| `review` | 管理人工审核任务及后续入图执行 | `GraphReviewService`、`GraphReviewStore`、`GraphReviewTaskQuery`、`GraphReviewTaskStatus` |

注册表属于 `registry`；使用注册表执行实体归一的 `RegistryGraphEntityResolver` 仍属于 `resolution`。
应用实现持久化注册表时依赖 `registry` 包，替换实体匹配策略时依赖 `resolution` 包。

## 抽取阶段扩展

### `GraphExtractor`

负责从单个 `GraphExtractionRequest` 产生 `GraphCandidateResult`。实现可以使用 LLM、规则引擎或外部 NLP 服务。

必须保证：

- 只返回候选，不直接写 GraphStore；
- 不把 `previousContext`（前文上下文）当作当前 evidence；
- 保留 documentId、chunkId 和来源 metadata；
- 对并发调用提供明确的线程安全保证；
- 将模型超时、协议失败等包装为可诊断异常。

### `GraphExtractionPromptBuilder`

负责把 Schema、请求选项和正文组织为模型输入。它可以加入领域示例、术语表和供应商结构化输出要求，但不能绕过后续 Validator。

`LlmGraphExtractor` 默认使用 `JsonGraphExtractionPromptBuilder`，要求模型按照约定的 JSON 结构
输出候选；对应解析器是 `JsonGraphCandidateParser`。采用其他输出格式时，应配套替换提示词构建器和解析器。

Prompt 变化应进入 extraction fingerprint，否则同一内容可能在配置变化后被错误判定为 NO_OP。

### `GraphCandidateParser`

只负责把原始响应解析为候选协议。它应隔离单条坏候选、保留原始响应并产生稳定 issue code，不负责实体消歧、业务真伪判断或写图。

### `GraphCandidateValidator`

负责从全部候选中得到可以进入实体归一的合法子集，并保留解析阶段已有问题。自定义实现适合增加租户词表、属性约束、证据策略和领域合规规则。

Validator 不应静默修复不可解释的数据；若执行规范化或默认值填充，应产生可审核记录。

校验结果通过 `GraphCandidateValidationResult.getValidatedCandidates()` 返回合法候选。
“通过校验”仅表示符合 Schema 和质量规则，不代表已经审核接受或已经写入图数据库。

属性校验由 `validation` 包下的 `GraphSchemaPropertyValidator` 统一承担，候选校验和人工修改
使用同一套白名单、类型、枚举和必填规则。`findViolation(properties, definitions)` 返回首个
`GraphPropertyViolation`，没有问题时返回 `null`；问题包含 `code`、`propertyName` 和 `message`，
便于开发者在自己的 UI 中定位字段。

## 身份和图投影扩展

### `GraphEntityResolver`

负责把候选提及映射为规范实体及稳定 `nodeId`。它可以结合名称、别名、上下文、向量相似度或业务主数据，但不能把低确定性的同名匹配静默合并。

### `GraphEntityRegistry`

负责跨文档和跨批次保存长期实体身份。生产实现应按 Space 隔离，使用唯一约束处理并发注册，并能返回同名歧义而不是最后写入覆盖。

`findMatches(space, type, names)` 和 `saveAll(space, entities)` 都要求显式传入 Space，
没有不带 Space 的查询或保存方法。查询还按实体类型隔离；批量保存应原子处理别名和属性合并，
发生名称冲突时不得只提交批次中的一部分。

Registry 是身份存储，Resolver 是身份决策，两者职责不同。常见组合是 Resolver 查询 Registry，增量服务在写图成功后幂等保存注册项。

### `GraphEntityIdGenerator`

负责在无法复用已有实体时产生 nodeId。接入业务主键优先于名称哈希；任何算法变化都可能让历史实体产生新 ID，应进行迁移或保持版本兼容。

### `GraphCandidateMutationMapper`

负责把合法候选和归一结果映射成节点 Upsert、边 Upsert，并按 `GraphEdgeKey` 去重；默认重复关系保留置信度更高的候选。

它不负责：

- 删除旧关系；
- 人工审核；
- 调用数据库；
- 判断多来源冲突的业务真相；
- 自动回收孤立节点。

旧关系删除由增量计划结合文档状态和事实来源决定。

## 长期运行扩展

### `GraphDocumentStateStore`

保存文档当前状态、版本历史和事实来源查询。生产实现必须为 revision 提供原子 CAS，并为 Space、documentId、edgeKey 和状态建立索引。

使用 `findCurrent(space, documentId)` 查询当前状态，`findCurrentFactSources(space, edgeKey)`
查询当前有效来源，`findFactSourceHistory(space, edgeKey)` 查询包含历史版本的来源记录。
当前有效来源和历史来源用途不同，不能用历史记录判断一条关系现在是否仍有文档支持。

### `GraphIngestionOperationStore`

保存 operationId、planFingerprint、原始计划和操作阶段。操作和计划应原子创建，阶段推进使用 CAS，并支持稳定扫描未完成操作。

实现必须提供 `createIfAbsent(operation, plan)`，并通过 `isRecoverySupported()` 明确声明恢复能力。
支持恢复的实现提供 `getPlan(operationId)` 和 `listRecoverableOperations(limit)`；内存实现的恢复
能力仅在当前进程内有效。完整要求见[故障恢复](/zh/graph/knowledge-extraction-recovery)。

### `GraphIngestionLockProvider`

限制同一 `Space + documentId` 的并发执行。默认本地实现只适合单 JVM；分布式实现需要租约、持有者校验和超时，但锁仍不能替代 revision CAS。

## 并发与异常契约

生产自定义组件应满足：

1. 服务级组件可被多个任务并发调用，或明确由调用方按任务创建；
2. 返回集合和状态对象不被调用方后续修改；
3. 不吞掉异常，也不依赖异常 message 做程序分流；
4. 持久化操作具有唯一约束、幂等语义和明确的 CAS 结果；
5. 不在一个扩展点中偷偷调用下游阶段；
6. 配置变化能通过版本或 fingerprint 被识别；
7. 日志不泄露完整原文、模型响应或凭据。

## 选择最小扩展面

| 需求 | 优先扩展 |
| --- | --- |
| 更换模型或规则引擎 | `GraphExtractor` |
| 使用供应商工具调用格式 | `GraphCandidateParser` 和 Prompt Builder |
| 增加业务校验 | `GraphCandidateValidator` |
| 接入主数据或别名库 | `GraphEntityResolver` / Registry |
| 使用业务节点主键 | `GraphEntityIdGenerator` |
| 调整图属性和投影 | `GraphCandidateMutationMapper` |
| 多实例长期增量 | 三个 Store/Lock 接口 |

仅增加业务校验时，不应复制 Pipeline；仅改变图投影时，也不应把数据库写入放进 Mapper。

## 测试建议

每个自定义接口至少验证：

- 正常输入、空结果、局部坏候选和异常输入；
- 同一输入重复执行是否稳定；
- 并发调用和唯一约束冲突；
- Schema、Space、Chunk 和身份边界；
- 敏感数据是否进入日志；
- 与 Pipeline、增量计划和恢复流程的契约集成；
- Neo4j、Nebula 等真实后端中的最终投影是否符合预期。

## 下一步阅读

- [数据模型](/zh/graph/knowledge-extraction-contract)：了解扩展组件传递的数据；
- [错误处理](/zh/graph/knowledge-extraction-errors)：了解异常和 issue 边界；
- [生产实践](/zh/graph/knowledge-extraction-production)：了解部署、指标和升级。
