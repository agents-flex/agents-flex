# 核心类

## 概述

`agents-flex-graph-extractor` 是把文档内容变成可审核、可追溯、可持续维护的图数据变更的 SDK。它不负责实现上传页面、审批页面或具体图数据库，而是提供一组可以组合的核心类：抽取候选、校验、实体归一、生成 Mutation、规划入图、保存文档状态、恢复失败操作，以及（可选的）审核计划。

可以把一次处理理解为两条边界清晰的路径：

```text
单次抽取：文档 -> Pipeline -> ExtractionResult -> 调用方确认 -> GraphWriter
长期入图：文档 + Request -> Service.plan -> IngestionPlan -> 可选审核
                       -> Service.execute -> 图写入与文档状态提交
```

抽取结果和入图计划都不会自动修改图数据库；只有调用执行方法才会产生写入副作用。这种分层让开发者能够在自己的产品中实现自动接受、人工确认、重试、审计和对账。

## 包之间如何协作

| 包 | 解决的问题 | 常用入口 |
| --- | --- | --- |
| 根包 `com.agentsflex.graph.extractor` | 连接模型、分段和整条抽取流水线 | `GraphExtractionPipeline` |
| `model` | 表达实体、关系、证据和诊断信息 | `GraphEntityCandidate`、`GraphRelationCandidate` |
| `prompt`、`parser` | 约束模型输出并解析协议 | `JsonGraphExtractionPromptBuilder`、`JsonGraphCandidateParser` |
| `validation` | 判断候选是否符合 Schema 和质量规则 | `GraphCandidateValidator` |
| `resolution`、`registry` | 判断候选指向哪个长期实体并保存身份 | `GraphEntityResolver`、`GraphEntityRegistry` |
| `mapping` | 将合法候选投影为节点、边的 Mutation | `GraphCandidateMutationMapper` |
| `ingestion` | 管理版本、差异、写入、来源和恢复 | `GraphIngestionService` |
| `review` | 在执行计划前提供可选的审核任务服务 | `GraphReviewService` |

下面按包说明类的作用、适用场景和协作关系。类名是 SDK 的公共契约，示例中的 `space` 是目标知识库，`documentId` 是业务文档的稳定标识。

## 根包：抽取入口与结果

### `GraphExtractor`

`GraphExtractor` 是最小抽取接口：`extract(GraphExtractionRequest)` 接收一个分段和 Schema，返回 `GraphCandidateResult`。它只发现候选，不写图。需要接入规则引擎、已有 NLP 服务或非 LLM 模型时，实现这个接口即可。

### `LlmGraphExtractor`

这是调用大模型的默认抽取器。`new LlmGraphExtractor(chatModel)` 使用 JSON 提示构造器和解析器；需要领域提示或供应商专用输出格式时，用三参数构造器传入自己的组件。

通过 `setChatOptions(...)` 配置温度等模型参数。该方法替换后续调用使用的模板；不要在发布后继续修改同一个 `ChatOptions` 对象，或在并发请求中来回切换参数。实例通常作为应用级依赖复用，前提是模型客户端和扩展组件支持并发。

### `GraphExtractionRequest`

`GraphExtractionRequest` 是一次分段调用的不可变输入，包含文本、Schema、`documentId`、`chunkId`、有限的 `previousContext`、元数据和选项。上下文只帮助模型处理指代，证据必须来自当前分段；不要把上下文中的文字当作本段证据。

### `GraphExtractionOptions`

这是抽取与质量控制配置，适用于不同业务使用不同接受标准的场景。它控制最低置信度、是否必须有证据、是否包含 INFERRED/OPINION、上下文和实体/关系/响应大小上限，以及 `failOnChunkError`。默认排除推断和观点，分段异常默认中止。其 `fingerprint()` 用来识别这些选项的变化；模型、Prompt 和自定义组件版本仍需由应用通过入图请求的指纹补充。

### `GraphCandidateResult`

表示一次局部抽取的实体候选、关系候选、问题和原始响应。Parser 和 Extractor 用它返回结果，Validator 也用它承载合法子集。需要检查某段文本的模型输出或实现自定义 Extractor 时，主要操作这个对象。

### `GraphExtractionResult`

这是整个文档或一组 Chunk 的流水线汇总结果：`getAllEntities()` / `getAllRelations()` 保留原始候选，`getValidatedEntities()` / `getValidatedRelations()` 返回通过校验的子集，同时提供 issues、实体归一映射、Mutation 和原始响应。

适合候选预览、质量分析和审核页面的数据输入。`hasErrors()` 用于判断是否存在 ERROR；通过校验不等于人工确认，也不等于事实为真。原始响应可能包含原文，保存和展示时应控制访问权限。

### `GraphExtractionException`

`GraphExtractionException` 表示无法继续的抽取异常；可恢复的候选质量问题应保留为 `GraphExtractionIssue`，不要只依赖异常文本做业务分流。

### `GraphExtractionPipeline`

流水线默认使用 1200 字符、重叠 200 字符的分段器、Schema 校验器、名称/别名归一器和 Mutation 映射器。`extract(document, schema[, options])` 适合让流水线自行分段；`extractChunks(...)` 适合调用方已经保存了 Chunk ID 的场景。自定义构造器可以替换分段、校验、归一和映射组件。

```java
// Pipeline 只返回结果，不会调用 GraphWriter。
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(new LlmGraphExtractor(chatModel));
GraphExtractionResult result = pipeline.extract(document, schema, GraphExtractionOptions.builder()
    // 生产环境通常要求每条关系带原文证据。
    .requireEvidence(true)
    // 是否把模型推断纳入候选，按业务风险决定。
    .includeInferredRelations(false)
    .build());
```

## `model`：候选、证据与诊断

### `GraphEntityCandidate`

描述尚未确认身份的实体，包括类型、名称、别名、属性、置信度和证据。例如文档里的“张三”和“张经理”最初可能是两个候选，随后才由 Resolver 判断是否是同一人。

Java 对象使用 `candidateKey`；JSON 协议中的 `mentionId` 只是当前响应内的临时标识，Parser 会转换成分段作用域的候选键。两者都不能作为长期 nodeId。

### `GraphRelationCandidate`

描述候选关系，包括关系类型、源/目标候选键、rank、属性、置信度、断言类型和证据。例如 `Person -[WORKS_FOR]-> Company` 在这一阶段引用的是候选键，而不是数据库节点 ID。

适合预览关系、展示证据和审核。端点会经过 Schema 校验和实体归一，再由 Mapper 转换成真正的节点 ID。关系是否属于多次独立事件，取决于稳定 rank 或事件节点建模。

### `GraphEvidence`

证据包含 `documentId`、`chunkId`、原文引文、字符偏移和元数据，用于回答“这条候选是从哪里得到的”。偏移相对于当前 Chunk，按 Java 字符位置计数，结束位置不包含在范围内；未知时两者均为 `-1`。提供有效偏移时，`text.substring(start, end)` 应与引文一致，便于定位和审计。应用应保存对应的原文版本，避免文档更新后引文失去位置。

### `GraphAssertionType`

断言类型分为 `EXPLICIT`（原文明确陈述）、`INFERRED`（推断）和 `OPINION`（观点）。JSON 未填写时默认为 EXPLICIT，但业务仍应根据证据和审核规则判断。置信度是模型对候选的信号，不是事实为真的统计保证。

### `GraphExtractionIssue`

表达解析、校验和分段处理中的问题，包含 WARNING/ERROR、代码、位置或候选键和消息。应用可以用它标记候选、解释拒绝原因和建立质量指标。是否进入人工审核由应用决定；ERROR 默认阻止入图计划。按 issue code 和 severity 统计比按完整消息聚合更稳定。

## `prompt`：组织模型输入

### `GraphExtractionPromptBuilder`

Prompt Builder 把 Schema、文本、上下文和选项组织成模型输入，并约束输出字段、类型和证据格式。领域提示、少量示例和术语表应在这里扩展，而不是散落在 Pipeline 中。不要把凭据或完整敏感原文写入日志。

### `JsonGraphExtractionPromptBuilder`

默认实现，将 Graph Schema 转成模型可理解的白名单，说明 JSON 字段、临时实体标识、关系端点和证据要求。适合普通文本响应模型；需要领域模板时可实现上面的接口，同时保证模板与 Parser 的协议一致。

## `parser`：把响应转换成候选

### `GraphCandidateParser`

Parser 把模型响应和原请求转换成 `GraphCandidateResult`。接入工具调用或供应商结构化输出时，优先替换 Parser 与 Prompt Builder，保留统一的 Java 候选模型，后续校验和归一无需改动。Parser 负责格式转换，业务正确性仍交给 Validator 与审核策略。

### `JsonGraphCandidateParser`

默认 JSON 解析器，处理结构、默认断言类型、mentionId 到候选键的映射以及响应长度限制。格式无法识别时可能抛出异常；能够定位到个别候选的问题则保留诊断信息。自定义 Parser 应同样防止超大响应、非法端点和跨分段候选键串用。

## `validation`：把候选限制在可接受范围

### `GraphCandidateValidator`

这是候选校验接口，接收候选、Schema 和选项，返回合法子集与问题。需要增加租户词表、合规规则或领域约束时实现它；可以先调用默认校验器，再补充业务检查，避免复制整个 Pipeline。校验器不应写图或提前注册实体。

### `SchemaGraphCandidateValidator`

默认实现，检查类型白名单、必填属性、属性类型、置信度、证据、断言策略和关系端点。适合所有使用 Graph Schema 的基础场景；它能阻止结构错误，不能证明模型结论符合现实。

### `GraphCandidateValidationResult`

封装通过校验的 `GraphCandidateResult`，通过 `getValidatedCandidates()` 读取合法子集，通过 `getIssues()` 查看完整问题。被拒绝项不在合法集合中，但仍可在最终 `GraphExtractionResult` 的全部候选中查看，便于解释和审计。

### `GraphSchemaPropertyValidator`

共享属性校验工具，`findViolation(properties, definitions)` 返回首个违规项，没有违规时返回 `null`。默认 Validator 和审核 Patch 重建计划都使用它，让抽取时和人工修改后的属性采用相同基础规则。

### `GraphPropertyViolation`

属性违规结果，包含 `propertyName`、`code` 和 `message`。用于精确指出“哪个属性为什么不合法”，适合表单错误提示和自定义 Validator 的诊断转换；它只代表一个违规项，不是全部属性错误的汇总。

## `resolution`：确定实体身份

### `GraphEntityResolver`

实体归一接口，将多个候选合并为节点，并建立 `candidateKey -> nodeId` 映射。需要接入业务主数据、已有图谱、语义匹配或同名消歧时实现它。Resolver 决定身份，Registry 保存身份，两者职责不同。

### `NameAliasGraphEntityResolver`

默认实现，按同类型的规范化名称和强别名进行传递合并，排除“他”“她”等弱代词别名。属性冲突采用首次值优先，因此结果也受输入顺序影响。

适合一次文档抽取中明确的名称和别名合并；它不能判断两个“张三”是否确实是同一个人，也不能查询历史导入批次。

### `GraphEntityResolutionResult`

包含归一后的节点列表和候选键映射，`findNodeId(candidateKey)` 用于把候选关系端点转换成最终节点 ID。适合展示“这些候选最终合并到了哪个实体”，以及给 Mapper 提供一致的映射输入。

### `RegistryGraphEntityResolver`

使用注册表复用既有 nodeId，适合一个 Space 持续导入不同文档和批次。构造时必须传入 `space`、Registry 和可选的 ID Generator；读取身份的 Registry 应与入图服务保存身份的 Registry 一致。

Resolver 查询后决定身份，不提前写注册表。写入由 `GraphIngestionService` 在图成功写入后完成，避免先注册一个实际上未写入的节点。匹配歧义应明确处理，不能随意取第一条覆盖。

### `GraphEntityIdGenerator`

节点 ID 生成接口，适合把实体映射到业务主键或领域 ID 服务。若更换算法，应评估是否会让已有实体获得新的 nodeId；它不是完整实体消歧接口，复杂身份判断应放在 Resolver 中。

### `HashGraphEntityIdGenerator`

默认稳定 ID 实现，基于规范化类型和名称的 SHA-256，生成“小写类型 + 冒号 + 24 位十六进制摘要”。相同输入得到相同 ID，适合没有业务主键的一次抽取。

稳定不代表语义身份正确，也不自动查询注册表。名称变化或同名实体需要 Registry 与业务归一规则处理。

## `registry`：保存实体身份

### `GraphEntityRegistry`

跨批次实体身份的存储接口，可使用业务数据库、主数据服务或图数据库实现。`findMatches(space, type, names)` 与 `saveAll(space, entities)` 都按 Space 隔离。

生产实现应按 Unicode NFKC、大小写和连续空白等价语义匹配名称，保证“Space + 类型 + 规范化名称/别名”唯一。保存同一节点时合并别名和属性，整批原子提交；已指向不同 nodeId 的名称不能静默覆盖。

### `GraphRegisteredEntity`

注册记录，保存 nodeId、类型、规范名称、别名和属性。用于将历史确认的身份提供给下一次抽取，不是当前文档的一次临时候选；Space 由 Registry 方法参数限定。

### `InMemoryGraphEntityRegistry`

内存注册表，适合单进程示例、测试和身份匹配验证。进程重启后记录丢失，不能作为长期知识库的唯一实体身份存储。

## `mapping`：从候选到图变更

### `GraphCandidateMutationMapper`

Mapper 将合法候选和实体归一结果投影为节点/边 upsert，按 `GraphEdgeKey` 去重，重复关系保留较高置信度，同置信度保留首次值。

适合把候选转换成 `GraphWriter` 可执行的变更。它是可继承的具体类，可覆写 `map(resolution, relations)` 后注入 Pipeline，用于调整属性投影；默认实现不写数据库、不删除旧边、不裁决冲突真相。旧关系的来源和删除计算应留在入图阶段。

## `ingestion`：计划、写入与长期状态

### `GraphIngestionRequest`

请求用稳定的 `space + documentId` 标识逻辑文档，并携带内容摘要、业务版本、Schema 版本、批次、来源更新时间、抽取指纹、GraphOptions 和失败策略。适合区分首次导入、新版本和重复提交；业务 `documentVersion` 不等于状态 `revision`。显式提供的 `contentHash` 必须与实际内容一致，GraphOptions 的 Space 必须与请求一致。

模型、提示模板、Resolver 等配置变化可记录到 `extractionFingerprint`，避免文本未变时错误地跳过重抽。`reextractUnchangedContent(true)` 可强制重新处理。

请求的三个容错选项分别控制不同边界：

| 选项 | 控制什么 | 默认行为 |
| --- | --- | --- |
| `extractionOptions.failOnChunkError` | 某个 Chunk 异常后是否继续其他 Chunk | 中止抽取 |
| `failOnExtractionError` | 是否允许为含 ERROR 的结果生成计划 | 阻止生成 |
| `allowPartialReconciliation` | 不完整结果是否允许撤销旧来源 | 保留可能遗漏的旧来源 |

如果需要导入合法子集，可同时关闭前两个失败开关，但保持第三个为 false；不能把失败分段未返回的关系当成文档已经删除的关系。

### `GraphIngestionPlan`

这是待执行的不可变变更快照，适合预览、审核、持久化和恢复。通过 `getType()` 区分 `NO_OP`（无需改变）、`INGESTION`（导入/更新）和 `RETRACTION`（撤回）。它包含新旧状态、Mutation、过期 EdgeKey、注册项、GraphOptions、抽取结果和来源 Schema/Request。

重复的内容与相关版本/配置会得到 NO_OP；计划阶段不写图，执行阶段不重新调用模型。持久化完整计划时应保存格式版本，使用保留 `sourceSchema/sourceRequest` 的恢复方式，否则重启后的审核修改可能无法重建计划。计划指纹由执行操作记录，不是审核任务的业务版本。

### `GraphIngestionService`

这是长期入图的编排入口，适合知识库持续接收文件、更新和撤回的场景。服务本身不持有数据库连接，执行时显式传入 `GraphWriter`。

| 方法 | 用途 |
| --- | --- |
| `plan` / `planChunks` | 抽取并结合旧状态计算变更，供执行前审核 |
| `execute` | 执行冻结计划，不再次调用模型 |
| `ingest` / `ingestChunks` | 直接组合规划与执行，适合自动接受 |
| `planRetraction` | 为整个逻辑文档生成撤回计划，不用空文档伪造更新 |
| `rebuildPlan` | 根据审核后的抽取结果重建原计划 |
| `resume` | 从操作存储中的原计划继续恢复 |
| `isRecoverySupported` / `listRecoverableOperations` | 检查恢复能力并扫描未完成操作 |

`GraphIngestionRequest.StaleRelationPolicy.KEEP` 默认报告过期边，不删除物化边；`DELETE_IF_UNREFERENCED` 仅在其他 ACTIVE 文档没有引用时删除。图写入、Registry、状态与操作存储之间没有自动分布式事务，失败窗口需用原计划恢复和对账处理。

### `GraphIngestionResult`

执行结果包含计划、`GraphWriteResult` 和 `isStateCommitted()`。`isSuccess()` 要求图写入成功且文档状态已经提交或确认无需变化。适合判断任务结果；只检查图写入成功不足以判断整个入图完成。

### `GraphDocumentState`

保存某个 Space + documentId 当前已提交的知识来源状态，包括 revision、operationId、内容摘要、业务/Schema 版本、抽取指纹、批次和时间、当前节点与 EdgeKey、被替代关系和事实来源。

`Status.ACTIVE` 表示文档仍是当前来源；`Status.RETRACTED` 表示已撤回。文档更新产生新 revision，旧版本进入历史；撤回留下墓碑，保留版本防护。不要直接删除状态来代替撤回，也不要自动删除它曾提到的共享节点。

新版本不再包含的旧关系进入计划的 `staleEdgeKeys`，但 stale 不等于已删除。最终删除取决于策略、其他有效来源，以及本次结果是否完整。

### `GraphDocumentStateStore`

文档状态的存储接口，适合接入 MySQL 等业务数据库并提供审计和来源反查。核心契约是 `compareAndSet(space, documentId, expectedRevision, newState)` 的原子乐观锁，新文档的期望版本为 0。

| 方法 | 场景与约束 |
| --- | --- |
| `findCurrent` / `list` | 查询逻辑文档当前状态或 Space 状态快照 |
| `findByOperationId` | 判断一次操作是否已提交，生产应查询历史并建立索引 |
| `recordVersion` / `listVersions` | 保存不可变历史、审计与回放；默认方法不会保存完整历史 |
| `isReferencedByOtherDocument` | 判断其他 ACTIVE 文档是否仍支持同一 EdgeKey |
| `findCurrentFactSources` | 只查询当前 ACTIVE 版本的有效来源 |
| `findFactSourceHistory` | 查询历史来源，供审计，不用于判断当前有效性 |
| `remove` | 按 revision 原子删除记录；不是入图服务的业务撤回入口 |

生产实现建议建立当前表的 Space + documentId 唯一键、历史表的 Space + documentId + revision 唯一键、operationId 索引和 edgeKey 到活动文档的反向来源索引。默认引用查询扫描状态列表，数据量大时应覆写；并发语义必须可靠，不能仅依赖缓存中的过期引用数。

### `InMemoryGraphDocumentStateStore`

内存实现，可用于单元测试和本地演示状态/来源查询。它的版本历史也只在当前进程中存在；长期运行必须替换为持久化实现。

### `GraphFactSource`

表示某份文档版本对某条物化边的独立声明，包含 factId、EdgeKey、证据、operationId、revision、时间、置信度、断言类型和关系属性快照。适合回答“哪些文档支持这条关系”“当时依据是什么”。factId 包含 Space 作用域。

例如两份文档都记载“张三任职于星河科技”，各自产生来源声明，共同支持同一个 EdgeKey。撤回一个来源后，其他 ACTIVE 来源仍可支撑边。当前来源与历史来源必须分开查询，历史声明不能证明关系现在仍然有效。

SDK 不替业务裁决冲突事实。需要保留冲突、时间演变或在图内遍历来源时，可显式建模 `DocumentVersion -> Fact -> Entity`；默认来源保存在外部文档状态里，不会自动变成图节点。

### `GraphIngestionOperation`

记录一次执行的 operationId、计划指纹、阶段、恢复进度、时间和失败信息，适合跟踪跨系统提交。阶段为 `PREPARED -> GRAPH_APPLIED -> STATE_COMMITTED -> COMPLETED`，失败记录 FAILED。操作阶段回答“执行到哪里”，文档 ACTIVE/RETRACTED 回答“来源是否生效”，两者不能合并成一个状态。

### `GraphIngestionOperationStore`

操作与冻结计划的存储接口，适合重启后恢复未完成任务。必须在一个存储事务中执行 `createIfAbsent(operation, plan)`，重复 operationId 保留首次记录与计划，阶段推进用 `compareAndSet(...)`。

支持恢复的实现通过 `isRecoverySupported()` 声明能力，提供 `getPlan` 和按更新时间排序的 `listRecoverableOperations`。计划序列化应有版本号；SDK 升级后不能直接接管无法解析的旧计划。

### `InMemoryGraphIngestionOperationStore`

内存操作与计划存储，适合故障注入和同一进程内恢复测试。它支持恢复 API，但不意味着进程重启后可恢复；跨进程恢复还要求底层持久化。

### `GraphIngestionLockProvider`

以 `acquire(space, documentId)` 获取可关闭的 Lease，避免同一文档同时执行。多实例使用共享租约实现时应处理超时、持有者校验和释放；锁不能代替 revision CAS，也不能提供跨系统原子事务。

### `LocalGraphIngestionLockProvider`

默认本地锁，按 Space + documentId 在当前 JVM 串行执行，不阻塞其他文档。适合单实例部署和测试；多个 JVM 必须共享锁服务，并对外部状态继续使用原子 CAS。

### `GraphIngestionPlanFingerprint`（内部类）

包内实现细节，用于识别冻结计划并防止同一操作号执行不同计划。应用无需实例化或扩展它；排查和恢复时读取 `GraphIngestionOperation` 保存的计划指纹即可。

## `review`：可选的审核计划服务

### `GraphReviewService`

人工审核的业务入口，通过审核 Store 和入图服务连接“待确认计划”和“执行原计划”。先 `submit(plan)` 创建任务，再用 `findTask`/`findTasks` 查询，`applyPatch` 修改候选，`updatePlan` 替换计划，`accept` 执行，`reject` 拒绝，`requestChanges` 退回修改，`archive` 归档，`resume` 恢复未完成执行。

UI 可以把任务 ID 和读取到的 reviewVersion 传给自己的后端，后端检查权限后调用对应方法。自动接受时直接使用入图服务，不需要配置 Review Store；审核服务也不是每个入图请求的必经路径。

### `GraphReviewStore`

审核任务快照的存储接口，包含 `create`、`findTask`、`findTasks` 和带 expectedReviewVersion 的原子 `update`。适合把待审核任务接入业务数据库；生产实现应保存完整计划、状态、操作人和原因，提供唯一 taskId、查询索引和版本冲突检查，列表按更新时间倒序。

### `InMemoryGraphReviewStore`

内存审核存储，适合验证创建、查询、修改、接受和拒绝流程。审核页面通常跨请求甚至跨天操作，因此生产环境需持久化任务，不能依赖进程内对象。

### `GraphReviewTask`

任务快照，保存 taskId、当前计划、reviewVersion、状态、原因和审核人。适合列表展示和详情页；页面保存修改前必须提交对应 reviewVersion，避免两人同时操作时互相覆盖。taskId、reviewVersion、文档 revision、operationId 是不同身份，不能互换。

### `GraphReviewTaskQuery`

审核列表查询条件，支持 Space、documentId、状态集合、offset 和 limit。适合“某知识库待审核列表”“某文档历史任务”等场景；应用分页时应使用 Store 的稳定排序，不要先读取全部任务再分页。

### `GraphReviewTaskStatus`

定义审核状态，独立于文档状态和操作阶段：

| 状态 | 含义 |
| --- | --- |
| `PENDING_REVIEW` | 等待确认，可修改、接受或拒绝 |
| `CHANGES_REQUESTED` | 退回修改，仍可修改或继续确认 |
| `EXECUTING` | 已接受，正在执行入图 |
| `COMPLETED` | 图写入和文档状态提交成功 |
| `REJECTED` | 已拒绝，不执行计划 |
| `FAILED` | 执行失败，可能已有部分副作用，需要恢复 |
| `ARCHIVED` | 已完成或已拒绝任务的逻辑归档 |

EXECUTING/FAILED 不能通过归档丢弃恢复线索；不要对仍由另一实例执行的任务主动调用 resume。

### `GraphReviewPatch`

审核修改说明，使用 Builder 的 `rejectEntity`、`rejectRelation`、`replaceEntityProperties`、`replaceRelationProperties` 和 `resolveEntity` 表达拒绝、属性修改或指定实体身份。

关系索引只在同一 reviewVersion 的 validatedRelations 中有效；修改后的属性必须继续符合 Schema。应用 `applyPatch` 会重建计划，不重新调用模型、不直接写图，并保留原始诊断信息。适合审核页面一次提交多项修改。

### `GraphReviewExecutionResult`

接受或恢复任务后的结果，`getTask()` 返回审核任务快照，`getIngestionResult()` 返回入图结果。`isSuccess()` 同时要求任务 COMPLETED 和入图成功，便于页面显示正式结果。调用抛出异常时应另行处理异常并查询任务状态，不能假设所有失败都会以结果对象返回。

## 一段组合示例

下面示例用内存组件说明类之间的连接。`chatModel` 是应用配置的 `ChatModel`，`graphWriter` 是 `graphStore.writer()` 返回的 `GraphWriter`，`schema` 是事先定义的 `GraphSchema`。服务和 Store 在应用初始化阶段创建并复用，不能每次导入重新创建，否则会失去历史状态。

::: details 示例用到的类型

```java
import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.splitter.SimpleDocumentSplitter;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.graph.GraphOptions;
import com.agentsflex.graph.extractor.GraphExtractionPipeline;
import com.agentsflex.graph.extractor.LlmGraphExtractor;
import com.agentsflex.graph.extractor.ingestion.*;
import com.agentsflex.graph.extractor.mapping.GraphCandidateMutationMapper;
import com.agentsflex.graph.extractor.registry.GraphEntityRegistry;
import com.agentsflex.graph.extractor.registry.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;
import com.agentsflex.graph.extractor.review.*;
import com.agentsflex.graph.extractor.validation.SchemaGraphCandidateValidator;
import com.agentsflex.graph.mutation.GraphWriter;
import com.agentsflex.graph.schema.GraphSchema;
```

:::

```java
// 应用初始化：为 company 知识库创建同一套实体注册表和状态组件。
String space = "company";
GraphEntityRegistry entityRegistry = new InMemoryGraphEntityRegistry();
GraphDocumentStateStore documentStateStore = new InMemoryGraphDocumentStateStore();
GraphIngestionOperationStore operationStore = new InMemoryGraphIngestionOperationStore();
GraphIngestionLockProvider lockProvider = new LocalGraphIngestionLockProvider();

// 抽取、校验和归一依次执行。Resolver 和入图服务使用同一 Registry、同一 Space。
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    new LlmGraphExtractor(chatModel),
    new SimpleDocumentSplitter(1200, 200),
    new SchemaGraphCandidateValidator(),
    new RegistryGraphEntityResolver(space, entityRegistry),
    new GraphCandidateMutationMapper());

// ingestion 的实际类型是 GraphIngestionService：负责规划、执行和保存来源状态。
GraphIngestionService ingestion = new GraphIngestionService(
    pipeline, documentStateStore, entityRegistry, operationStore, lockProvider);

// 每次导入创建新 Request，保留同一逻辑文档的 documentId。
Document document = Document.of("张三任职于星河科技。");
GraphIngestionRequest request = GraphIngestionRequest.builder(space, "doc-001")
    .documentVersion("2026-10-09")
    .schemaVersion("company-schema-2")
    .graphOptions(GraphOptions.ofSpace(space))
    .build();

// plan 会调用抽取流程并计算差异，但不会改变图数据库。
GraphIngestionPlan plan = ingestion.plan(document, schema, request);

// 自动接受路径：不需要 GraphReviewStore，execute 也不会再次调用模型。
GraphIngestionResult result = ingestion.execute(plan, graphWriter);
if (!result.isSuccess()) {
    // 图写入或状态提交未成功，应用应保留计划并安排恢复/对账。
    throw new IllegalStateException("知识入图未完成");
}
```

如果该文档需要人工确认，**用下面的路径替代最后的 execute**：

```java
// 应用初始化时创建；仅选择人工审核的应用需要这个 Store。
GraphReviewStore reviewStore = new InMemoryGraphReviewStore();
GraphReviewService review = new GraphReviewService(reviewStore, ingestion);

// 提交原计划。应用可把 taskId 和 reviewVersion 返回给审核页面。
GraphReviewTask task = review.submit(plan);

// 用户点击“接受”后：后端验证权限，传入页面读取的任务 ID 和版本。
// 此时才会执行该任务的计划；用户点击“拒绝”时改用 review.reject(...)。
GraphReviewExecutionResult accepted = review.accept(
    task.getTaskId(), task.getReviewVersion(), graphWriter, "reviewer-001");
```

实际产品中“提交任务”和“接受任务”通常是不同请求。不要在上传请求中同时执行自动接受和提交同一计划。完整的查询、修改和拒绝示例见[审核](/zh/graph/extractor/review)。

## 身份与版本速查

| 标识 | 作用域和含义 |
| --- | --- |
| `mentionId` | 模型 JSON 响应内的临时实体标识 |
| `candidateKey` | 当前抽取结果内的候选标识 |
| `nodeId` | Space 内长期实体身份 |
| `factId` | 一份来源声明的稳定身份 |
| `operationId` | 一次入图执行的身份 |
| `taskId` / `reviewVersion` | 审核任务及其并发版本 |
| `revision` | 某个 Space + documentId 的文档状态版本 |
| `GraphEdgeKey` | sourceId + type + targetId + 稳定 rank 的关系身份 |

混用这些标识会导致重复实体、错误撤回或审核覆盖。多次同类事件应使用稳定 rank，或将事件建模为节点，不能每次随机生成关系身份。

## 生产使用原则

- Pipeline、Resolver、Service 和客户端可作为长期依赖复用；Request、Result、Plan 是任务级不可变对象。
- 状态、注册表、操作和审核数据必须有唯一键、CAS、索引和完整计划序列化；多个实例必须使用共享锁。
- 监控模型耗时/Token、候选与 issue、注册命中和歧义、Mutation 与 stale 数、NO_OP、CAS/锁冲突、恢复阶段和写入后对账。
- 不把原文、完整模型响应或凭据写入普通日志；对历史计划和来源实施访问控制。
- 升级模型、Prompt、Schema、Resolver 或 Mapper 前评估 fingerprint、nodeId、EdgeKey 和旧计划的可恢复性。
- 备份原文、Schema、状态、注册表、操作计划、审核记录、事实来源和图数据，并演练从这些资料重建投影。

应用还应分别限制模型调用、文档任务和图写入的并发，结合模型 QPS、响应大小、连接池及审核积压控制容量。SDK 不提供任务调度、持久化默认实现、自动节点回收或事实正确性保证；它提供审核服务，审核 UI 和业务权限仍由应用实现。

升级前用脱敏领域样本比较合法候选率、身份匹配、来源定位和最终查询结果，必要时在隔离 Space 小流量验证。执行 COMPLETED 只是流程完成；仍应在真实后端查询节点/边，核对 Registry nodeId、状态 EdgeKey 与当前来源，验证撤回后共享关系保留、独占关系按策略清理，并安排周期性对账。

自定义扩展组件至少验证正常/空/异常输入、同输入重复执行的稳定性、Space/类型/候选键隔离、并发唯一约束和 CAS 冲突，以及与计划、审核和恢复的集成契约。图投影在 Neo4j、Nebula 等实际后端回归，避免仅用内存 Mock 判断行为正确。

更多场景说明见[Schema 驱动抽取](/zh/graph/extractor/schema-driven-extraction)、[知识抽取流程](/zh/graph/extractor/extraction-process)、[知识入图生命周期](/zh/graph/extractor/ingestion-lifecycle)、[审核](/zh/graph/extractor/review)、[实体归一](/zh/graph/extractor/entity-resolution)、[知识入图](/zh/graph/extractor/ingestion)、[故障恢复](/zh/graph/extractor/recovery)和[错误处理](/zh/graph/extractor/error-handling)。
