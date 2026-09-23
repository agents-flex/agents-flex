# 从文档抽取知识图谱

agents-flex-graph-extractor 把已有的 DocumentSplitter、ChatModel 与 Graph API 组合成可审核的知识抽取流水线。模块负责从文本中生成候选实体和关系、校验 GraphSchema、跨分段归一实体，并映射为 GraphMutation；它不会自动写入数据库。

## 添加依赖

~~~xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-extractor</artifactId>
    <version>\${VERSION}</version>
</dependency>
~~~

模块依赖 agents-flex-core 和 agents-flex-graph-api，但不依赖 Neo4j 或 Nebula。应用可以在审核完成后选择任意 GraphStore 后端写入。

## 定义抽取 Schema

Schema 不只是数据库 DDL，也是模型允许输出的实体、关系和属性白名单：

~~~java
GraphSchema.Property name = new GraphSchema.Property(
    "name", GraphSchema.PropertyType.STRING, true);

GraphSchema schema = GraphSchema.builder()
    .nodeType(GraphSchema.NodeType.of("Character", name))
    .nodeType(GraphSchema.NodeType.of("Organization", name))
    .nodeType(GraphSchema.NodeType.of("Event", name,
        new GraphSchema.Property("summary", GraphSchema.PropertyType.STRING, false)))
    .edgeType(GraphSchema.EdgeType.of(
        "MEMBER_OF", "Character", "Organization"))
    .edgeType(GraphSchema.EdgeType.of(
        "PARTICIPATES_IN", "Character", "Event"))
    .build();
~~~

关系端点应尽量声明 sourceLabel 和 targetLabel。对于决斗、死亡、会面等具有时间、地点和多参与者的动作，推荐建成 Event 节点，而不是把全部信息压在一条直接边上。

Schema 的 displayName、description 和属性 enumValues 会进入默认提示词，帮助模型理解业务语义；
校验器也会强制检查枚举范围。属性 defaultValue 不会进入提示词，也不会用于补写原文中不存在的事实。
DATE 和 DATETIME 属性接受对应 Java 时间对象或合法的 ISO-8601 字符串。

## 创建抽取流水线

~~~java
ChatModel chatModel = createYourChatModel();
GraphExtractor extractor = new LlmGraphExtractor(chatModel);
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor);

Document document = Document.of(novelText);
document.setId("novel-001");
document.setTitle("示例小说");
document.putMetadata("source", "novel.txt");

GraphExtractionResult result = pipeline.extract(document, schema);
~~~

默认流水线使用 1200 字符分段和 200 字符重叠。可以注入已有的 MarkdownHeaderSplitter、AIDocumentSplitter 或业务分段器：

~~~java
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    documentSplitter,
    new SchemaGraphCandidateValidator(),
    new NameAliasGraphEntityResolver(),
    new GraphMutationMapper());
~~~

流水线会把上一分段末尾作为下一分段的消歧上下文。提示词明确要求模型不能从这段前文重复抽取事实。

如果应用已经使用 DocumentSplitter 完成分段，可以直接传入分段结果，避免二次切分：

~~~java
List<Document> chunks = documentSplitter.split(document, idGenerator);
GraphExtractionResult result = pipeline.extractChunks(
    chunks, "novel-001", schema, options);
~~~

`extractChunks` 优先使用每个 Document 的 ID 作为 chunkId，缺失时按列表顺序生成 `chunk-0`、
`chunk-1`；显式传入父文档 ID 后，缺失的 chunkId 会生成成 `novel-001#chunk-1`。最终 chunkId 必须
唯一，重复 ID 会在任何模型调用前失败。未传父文档 ID 的重载会让 GraphEvidence.documentId 为空；
额外来源字段可以通过每个 Document 的 metadata 传递。

## 审核和写入

模型返回的全部候选与通过校验的候选是两个集合：

~~~java
List<GraphEntityCandidate> allEntities = result.getAllEntities();
List<GraphRelationCandidate> allRelations = result.getAllRelations();

List<GraphEntityCandidate> acceptedEntities = result.getEntities();
List<GraphRelationCandidate> acceptedRelations = result.getRelations();
List<GraphExtractionIssue> issues = result.getIssues();
~~~

每个候选包含 evidence、confidence 和来源 chunk。默认校验规则包括：

- 节点和关系类型必须存在于 GraphSchema；
- 属性必须存在且 Java 值类型兼容；
- 必填属性不能缺失；
- 关系端点必须引用当前分段中通过校验的实体；
- 端点节点类型必须符合 EdgeType 约束；
- candidateKey 必须属于当前 chunkId 作用域，证据中的 documentId、chunkId 必须与当前请求一致；
- evidence 必须是当前分段中的连续原文；
- 已提供的 evidence 偏移必须满足 `0 <= startOffset <= endOffset <= text.length()`，并且
  `text.substring(startOffset, endOffset)` 与 evidence 完全一致；
- confidence 默认不低于 0.6；
- confidence 和 DOUBLE 属性必须是有限数，NaN 与正负无穷值会被拒绝；
- INFERRED 和 OPINION 默认不会进入 GraphMutation。

确认结果后才显式写入：

~~~java
if (!result.hasErrors()) {
    GraphWriteResult write = graphStore.writer().mutate(
        result.getMutation(), GraphOptions.ofSpace("novel_graph"));
}
~~~

是否存在 ERROR 不一定代表所有结果都不可用。流水线会保留合法子集；生产系统可以根据审核策略决定整批拒绝、仅写入合法项，或者让人工修正后重新提交。

## 质量选项

~~~java
GraphExtractionOptions options = GraphExtractionOptions.builder()
    .minConfidence(0.8)
    .requireEvidence(true)
    .includeInferredRelations(false)
    .includeOpinionRelations(false)
    .contextCharacters(1500)
    .maxEntitiesPerChunk(100)
    .maxRelationsPerChunk(100)
    .maxResponseCharacters(500_000)
    .failOnChunkError(false)
    .build();

GraphExtractionResult result = pipeline.extract(document, schema, options);
~~~

failOnChunkError 默认为 true，适合要求整篇原子失败的流程。设置为 false 后，模型调用或响应解析失败会记录
CHUNK_EXTRACTION_FAILED，自定义校验器异常会记录 CHUNK_VALIDATION_FAILED，流水线继续处理后续分段；
失败分段不会生成任何待写入数据。maxResponseCharacters 默认是 1,000,000，在 JSON 扫描前限制单次
模型响应规模。

默认 JSON 解析器会隔离单条候选格式错误：坏实体记录 `MALFORMED_ENTITY`，坏关系记录
`MALFORMED_RELATION`，同一响应中的其他合法候选仍会进入 Schema 校验。JSON 根对象整体损坏时无法
建立候选边界，仍按 Chunk 抽取失败处理。根对象必须同时包含数组类型的 entities 和 relations，候选必须
显式提供数值型 confidence；非法 assertionType 不会被静默转换为其他断言类型。

观点和推断关系即使被允许，也应通过 GraphAssertionType 区分。不要将角色猜测直接当作客观事实。

## 实体归一

默认 NameAliasGraphEntityResolver 按“节点类型 + Unicode 规范化名称/别名”合并候选，并通过 SHA-256 生成确定性节点 ID。属性冲突采用首次值优先，后续候选只补充缺失属性；需要按时间或可信度合并时应替换解析器。常见中英文代词即使被模型错误放入 aliases，也不会作为合并键。它可以处理：

~~~text
林默 / 林公子 → 同一个 Character
林默(Character) / 林默(Organization) → 不同节点
~~~

默认实现不会猜测“他”“黑衣人”是否一定是某个已有角色，也无法可靠区分同名不同人。复杂场景应实现 GraphEntityResolver，结合已有图谱、向量检索、章节状态或第二次模型判断。也可以实现 GraphEntityIdGenerator，对接业务已有的实体主键系统。

默认 ID 取决于本次抽取中首次出现的规范名称，不是跨批次实体注册表。增量导入同一长期图谱时，应使用已有实体库完成匹配，或提供业务自己的 GraphEntityResolver 和 GraphEntityIdGenerator。

## 模型响应协议

默认 LlmGraphExtractor 要求模型返回严格 JSON：

~~~json
{
  "entities": [
    {
      "mentionId": "m1",
      "name": "林默",
      "type": "Character",
      "aliases": ["林公子"],
      "properties": {},
      "evidence": "林默加入了青云宗",
      "startOffset": -1,
      "endOffset": -1,
      "confidence": 0.98
    }
  ],
  "relations": []
}
~~~

mentionId 只在单次模型响应中有效。JsonGraphCandidateParser 会自动添加 chunkId 作用域，避免不同分段都使用 m1 时发生冲突。模型不负责生成数据库节点 ID。

startOffset 和 endOffset 是相对于当前 Chunk 的 Java 字符偏移，endOffset 采用 exclusive 语义；
无法确定时必须同时为 -1。原始响应可能包含业务文本，不应未经脱敏直接写入生产日志。

重叠分段可能抽取出相同 source、type、target 和 rank 的关系。默认 GraphMutationMapper 只保留一条；
属性冲突时优先选择 confidence 更高的候选，置信度相同时保留首次出现值。

如果模型支持厂商特定的 JSON Schema 输出，可以配置 ChatOptions，或者替换 GraphExtractionPromptBuilder 和 GraphCandidateParser。
LlmGraphExtractor 可以作为应用单例复用，`setChatOptions` 对后续调用具备线程可见性；建议仍在应用启动或
受控配置切换阶段完成设置，不要在并发业务请求中反复切换全局模板。自定义 ChatModel、PromptBuilder 和
Parser 也必须支持并发调用。

默认提示词会把前文和当前文本标记为不可信数据，并要求模型忽略正文中改变规则或输出格式的指令。
这只能降低提示注入风险，不能替代 JSON 解析、Schema 白名单、证据校验和调用方审核。

## 真实 DeepSeek 集成测试

`agents-flex-graph-extractor` 包含一个 opt-in 的真实 DeepSeek 测试。密钥只从环境变量读取，未配置时
测试会自动跳过：

~~~bash
export DEEPSEEK_API_KEY="your-api-key"
mvn -pl agents-flex-graph/agents-flex-graph-extractor -am \
  -Dtest=DeepseekGraphExtractorIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
~~~

该测试使用合成小说文本，不上传业务文档，也不连接或写入图数据库。它真实验证 DeepSeek JSON Object
输出、严格协议解析、Schema 白名单、证据来源、实体归一和 GraphMutation 映射。测试会产生供应商调用
费用，并在密钥无效、账户余额不足、被限流或模型响应不满足协议时失败；这类失败不应通过伪造响应或
 放宽断言隐藏。生产环境同样不应把 API Key 写入源码、配置仓库或日志。

## 长期增量导入

对于同一个 Space 持续增加文件，推荐使用 `IncrementalGraphIngestionService`。它在 extractor 外面增加
了文档版本、内容哈希、跨批次实体注册、关系来源和旧版本差异处理：

~~~java
GraphDocumentStateStore documentStates = new YourPersistentDocumentStateStore();
GraphEntityRegistry entityRegistry = new YourPersistentEntityRegistry();
GraphIngestionOperationStore operations = new YourPersistentOperationStore();

// 必须把同一个长期注册表装配进 Pipeline 的 resolver，抽取时才会复用历史节点 ID。
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    splitter,
    validator,
    new RegistryGraphEntityResolver(entityRegistry),
    new GraphMutationMapper());

IncrementalGraphIngestionService ingestion = new IncrementalGraphIngestionService(
    pipeline,
    documentStates,
    entityRegistry,
    operations);

IncrementalGraphIngestionRequest request =
    IncrementalGraphIngestionRequest.builder("novel_knowledge", "book-001/chapter-008")
        .documentVersion("2026-09-23")
        .schemaVersion("novel-schema-v3")
        .batchId("import-2026-09-001")
        .staleRelationPolicy(
            IncrementalGraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED)
        .graphOptions(GraphOptions.ofSpace("novel_knowledge"))
        .build();

IncrementalGraphIngestionResult result = ingestion.ingest(
    document, schema, request, graphStore.writer());
~~~

首次导入会计算内容 SHA-256、抽取候选、生成 GraphMutation、写入图，然后提交文档状态。再次提交相同
`space + documentId + contentHash + documentVersion + schemaVersion` 时返回 `UNCHANGED`，不会再次调用
模型或写图。内容相同但 Schema 版本或业务文档版本变化时会重新抽取；需要无视哈希强制重跑时设置
`forceReextract(true)`。

`GraphEntityRegistry` 保存“节点类型 + 规范名称/稳定别名 -> 节点 ID”的长期映射。默认哈希 ID 只能保证
同名实体得到相同 ID，不能解决同名不同人；生产注册表遇到一个候选命中多个历史节点时必须报告歧义，
由上层审核或提供业务主键后再导入。实体注册应在图写入成功后保存，避免失败批次污染注册表。
仅把注册表传给 `IncrementalGraphIngestionService` 只能在写入成功后保存注册结果，不能改变 Pipeline 内部
的实体解析策略；跨批次归一必须显式使用 `RegistryGraphEntityResolver`（或开发者自己的实现）。

`GraphDocumentStateStore` 至少需要持久化以下字段：

- Space、逻辑 `documentId`、内容哈希和业务版本；
- Schema 版本、抽取配置指纹、导入 `batchId`、幂等 `operationId`、提交时间和乐观锁 `revision`；
- 来源更新时间 `sourceUpdatedAtMillis` 和文档状态（`ACTIVE`/`RETRACTED`）；
- 当前文档版本产生的节点 ID、关系 `GraphEdgeKey`；
- 每条关系的 `GraphFactProvenance`，包括 documentId、chunkId、原文证据、confidence 和 assertionType。

生产实现还应保留不可变的文档版本历史，而不是只覆盖当前状态。撤回应写入 `RETRACTED` 墓碑，避免
延迟任务或重复消息把已经撤回的文档重新导入。`sourceUpdatedAtMillis` 用于拒绝来源时间倒退的乱序版本。

文档更新后，服务会把旧版本中不再出现的关系放入 `staleEdgeKeys`。默认策略 `KEEP` 只报告这些关系，不
产生破坏性删除；显式使用 `DELETE_IF_UNREFERENCED` 时，仅当同一 Space 的其他活动文档状态不再引用该
关系，才把它加入 `GraphMutation.deleteEdge`。撤回文档使用 `planRetraction`，同样不会自动删除共享实体
节点。实体孤立回收应由独立、可审核的生命周期任务处理。

计划和写入可以分开，以便人工审核：

~~~java
IncrementalGraphIngestionPlan plan = ingestion.plan(document, schema, request);
// 审核 plan.getExtractionResult()、plan.getStaleEdgeKeys() 和 plan.getMutation()
IncrementalGraphIngestionResult result = ingestion.execute(plan, graphStore.writer());
~~~

状态存储使用乐观 `revision`。图数据库写入和外部状态存储无法组成跨系统事务：写图成功但状态 CAS 失败
时，服务会抛出异常，调用方应按相同 `operationId` 重试幂等 upsert，并使用 documentId 维度的分布式锁或任务
队列避免多实例同时处理同一文档。`GraphMutation.operationId` 会传递给适配器，适配器应使用它实现幂等。
SDK 提供的 `InMemoryGraphDocumentStateStore` 和
`InMemoryGraphEntityRegistry` 只适合测试，进程重启后数据会丢失。

生产环境建议同时实现 `GraphIngestionOperationStore`。服务会持久化
`PREPARED -> GRAPH_APPLIED -> STATE_COMMITTED -> COMPLETED` 状态；若进程在图写成功后退出，使用同一
`operationId` 重试时可以跳过重复 GraphWriter 调用并继续提交状态。`FAILED` 操作允许用同一操作号重试。
SDK 的 `InMemoryGraphIngestionOperationStore` 同样只适合测试，不能用于多实例恢复。

`GraphFactProvenance` 表示文档对事实的独立声明，不等同于图中的物化边。它包含稳定 `factId`、
`operationId`、文档 revision、创建时间和原文证据；同一 `GraphEdgeKey` 可以由多个事实声明共同支持。
业务需要在图中查询来源时，可以把这些事实记录映射为独立 Fact 节点，物化边则作为可重建的查询投影。

默认情况下，抽取结果包含 Chunk 错误时不会允许关系删除；即使通过
`rejectExtractionErrors(false)` 提交部分结果，也会保留旧关系。只有明确设置
`allowPartialReconcile(true)` 才允许部分结果参与 stale relation 删除。这样可以避免模型限流、临时网络错误
导致旧知识被误删。

如果使用跨 Space 的共享实体注册表，应通过带 Space 参数的 `GraphEntityRegistry.find/saveAll` 和
`new RegistryGraphEntityResolver(space, registry)`，避免不同知识库之间的名称和别名互相污染。

## 扩展点

| 接口 | 用途 |
| --- | --- |
| GraphExtractor | 替换 LLM，接入规则引擎或其他 NLP 服务 |
| GraphExtractionPromptBuilder | 自定义领域提示词和输出协议 |
| GraphCandidateParser | 解析厂商结构化输出 |
| GraphCandidateValidator | 增加词表、租户或业务约束 |
| GraphEntityResolver | 处理指代、同名消歧和已有实体匹配 |
| GraphEntityIdGenerator | 生成业务侧稳定节点 ID |
| GraphMutationMapper | 自定义证据、版本和审核状态的入图方式 |

## 能力边界

该模块不负责模型训练、人工审核 UI、分布式任务调度、跨请求实体注册表和数据库写入事务。GraphExtractionResult 保留候选、证据、问题及原始响应，开发者可以在自己的后台中实现审核、重试、版本管理和审计流程。

分段重叠可能重复抽取同一条关系。默认映射器会按 sourceId、type、targetId 和 rank 去重；同一对端点确实存在多次关系时，应提供不同 rank，或者优先建模为独立 Event 节点。
