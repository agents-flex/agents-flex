# 审核

## 概述

假设模型从下面的句子中抽取出一条关系：

```text
张三可能在 2020 年加入了星河科技。
```

模型也许会返回“张三任职于星河科技”，但应用还需要确认：关系类型是否在 Schema 中，证据是否真的支持“任职”，年份是否可以转换成整数，以及“可能”是否意味着这是一条推断而不是明确事实。

因此，模型返回的内容只能被视为**候选知识**，不能直接当作知识库中的正式数据。即使 JSON 格式正确，也可能存在错误类型、错误端点、原文不存在的事实、过低置信度或把观点当作事实等问题。

**审核**就是在候选进入图数据库之前，使用自动规则和人工判断决定哪些内容可以接受、哪些需要修改、哪些必须拒绝。

Graph Extractor 将“模型返回了什么”和“哪些候选通过了校验”分开保存，让上层能够实现自动门槛、人工审核和离线评估，而不是把一次模型调用直接变成数据库写入。

## 审核解决什么问题

审核不是只检查 JSON 能不能解析，而是要回答：

- 这条候选的节点和关系是否符合当前 Graph Schema；
- 证据能否回到原始文档和具体 Chunk；
- 关系端点是否连接了正确类型的实体；
- 候选是明确事实、推断还是观点；
- 同名实体是否匹配到了正确的长期节点；
- 文档更新时，哪些旧关系可以安全撤回；
- 没有 ERROR 是否足以自动写入。

推荐把审核理解为三道门：

```text
结构校验 -> 质量筛选 -> 业务接受
```

前两道门可以由 SDK 和自动规则完成，最后一道门是否需要人工参与，要由业务风险和错误成本决定。

## 一次审核如何进行

```text
模型返回候选
  -> Parser 解析
  -> Validator 自动校验
  -> 展示候选、证据和问题
  -> 接受、修改、拒绝或转人工
  -> 确认 GraphMutation / 入图计划
  -> 显式写入 GraphWriter
```

审核完成前，`GraphMutation` 只是待审核变更，不代表已经写入图数据库。

审核后台可以围绕同一份候选和计划完成以下操作：

```text
展示候选与原文
  -> 接受、修改或拒绝
  -> 选择规范实体
  -> 审核待撤回关系
  -> 确认 GraphMutation / GraphIngestionPlan
  -> execute(plan, writer)
  -> 保存审核记录和写入结果
```

界面展示的内容必须和最终执行的候选、Mutation 保持一致。不能审核一份结果后重新调用模型，再执行另一份未经审核的结果。

## 审核时需要查看的四类数据

`GraphExtractionResult` 包含：

~~~java
List<GraphEntityCandidate> allEntities =
    result.getAllEntities();
List<GraphRelationCandidate> allRelations =
    result.getAllRelations();

List<GraphEntityCandidate> validatedEntities =
    result.getValidatedEntities();
List<GraphRelationCandidate> validatedRelations =
    result.getValidatedRelations();

List<GraphExtractionIssue> issues =
    result.getIssues();
GraphMutation mutation = result.getMutation();
~~~

| 内容 | 含义 |
| --- | --- |
| `allEntities/allRelations` | 解析器得到的全部候选，包括后续被校验拒绝的候选 |
| `validatedEntities/validatedRelations` | 通过 Schema 和质量校验的候选子集，不代表人工审核已接受 |
| `issues` | 解析、校验和 Chunk 容错产生的问题 |
| `mutation` | 合法候选经实体归一后生成的待审核变更 |

即使存在 ERROR，流水线仍可能生成合法子集和 Mutation。Mutation 的存在不代表应当写入，更不代表已经写入。

可以用下面的代码准备审核数据：

~~~java
List<GraphEntityCandidate> allEntities =
    result.getAllEntities();
List<GraphRelationCandidate> allRelations =
    result.getAllRelations();
List<GraphEntityCandidate> validatedEntities =
    result.getValidatedEntities();
List<GraphRelationCandidate> validatedRelations =
    result.getValidatedRelations();
List<GraphExtractionIssue> issues =
    result.getIssues();
GraphMutation mutation = result.getMutation();

// 审核系统可以展示全部候选、合法候选、证据和问题。
review(allEntities, allRelations, validatedEntities,
    validatedRelations, issues, mutation);
~~~

其中，`allEntities/allRelations` 不能省略。它们包含被拒绝的候选，便于审核者理解模型原本返回了什么，也便于后续质量评估和问题回放。

审核后台还应根据审核场景保存：

- `GraphEvidence` 的文档、Chunk、引文和偏移；
- `GraphEntityResolutionResult`、`nodeId` 和 `GraphEdgeKey`；
- 入图计划的 `previousState`、`nextState` 和 expected revision；
- `staleEdgeKeys`，以及其他 ACTIVE 文档是否仍然支持这些关系；
- `operationId`、`planFingerprint`、Schema 版本和抽取配置指纹；
- 当前和历史事实来源。

这些数据由 SDK 结果、计划对象和审核任务提供；审核页面、权限和完整的持久化审计历史由应用负责。

## 默认质量规则

默认校验器会检查：

- 节点和关系类型存在于 Graph Schema；
- 属性名称已声明，Java 值与属性类型兼容；
- required 属性不缺失；
- 枚举属性位于允许范围；
- 关系端点引用当前 Chunk 中通过校验的实体；
- 端点类型满足 EdgeType 的 sourceLabel 和 targetLabel；
- candidateKey 属于当前 chunkId 作用域；
- evidence 的 documentId、chunkId 与请求一致；
- 引文是当前 Chunk 中的连续原文；
- 已提供偏移时，范围合法且与引文完全一致；
- confidence 不低于配置阈值；
- 浮点值不是 NaN 或无穷；
- 推断和观点符合允许策略。

这些规则可以发现结构和来源问题，无法判断所有业务事实真伪。高风险领域仍需业务审核或独立验证。

## 证据模型

`GraphEvidence` 保存：

- `documentId`：所属逻辑文档；
- `chunkId`：产生候选的分段；
- `quote`：连续原文引文；
- `startOffset`：相对当前 Chunk 的起始 Java 字符偏移；
- `endOffset`：exclusive 结束偏移；
- metadata：来源页码、章节等扩展信息。

当模型无法可靠提供偏移时，必须同时使用 `-1/-1`。如果提供偏移，则需要满足：

~~~text
0 <= startOffset <= endOffset <= chunkText.length()
chunkText.substring(startOffset, endOffset).equals(quote)
~~~

偏移是当前 Chunk 内位置，不是原始 PDF 字节位置或整篇文档偏移。需要跳转原始文件时，应在解析和分段阶段额外保存页码或源位置映射。

## 置信度不是事实概率

`confidence` 可以用于排序和门槛，但它是模型自报或抽取结果产生的评分，不应直接解释为统计意义上的正确概率。

建议通过领域标注集校准阈值：

- 统计不同类型的准确率与召回率；
- 对关键关系设置更高阈值；
- 将低置信候选送人工审核；
- 跟踪不同模型、Prompt 和 Schema 版本；
- 避免只优化总体平均值而忽略稀有高风险类型。

## 明确事实、推断和观点

`GraphAssertionType` 用于区分关系的认识论性质。默认协议把缺失类型视为明确事实以兼容早期响应，非法值会被拒绝。

默认配置不让 INFERRED 和 OPINION 进入 Mutation。即使业务允许，也建议：

- 在图中保留断言类型；
- 与明确事实使用不同展示和查询策略；
- 不让推断自动覆盖明确事实；
- 记录模型、配置和审核人；
- 为观点保留说话主体和上下文。

“角色可能背叛组织”不能因为模型高度确信就变成“角色已经背叛组织”。

## 配置质量策略

~~~java
GraphExtractionOptions options =
    GraphExtractionOptions.builder()
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
~~~

候选数量和响应字符上限既用于质量控制，也用于保护内存和解析成本。超过候选数量上限时，解析器记录警告并截断；响应超过字符上限时当前 Chunk 失败。

`GraphExtractionOptions.fingerprint()` 可以进入入图状态，用于识别抽取配置是否发生变化。

## SDK 配置和业务审核策略的边界

`GraphExtractionOptions` 配置的是抽取和自动校验行为，例如最低置信度、是否要求证据、是否允许推断关系、候选数量上限和 Chunk 失败策略：

~~~java
GraphExtractionOptions options =
    GraphExtractionOptions.builder()
        .minConfidence(0.80)
        .requireEvidence(true)
        .includeInferredRelations(false)
        .includeOpinionRelations(false)
        .failOnChunkError(true)
        .build();
~~~

这些配置会影响哪些候选能够进入合法结果，但它们不是完整的业务审核策略。接受、拒绝或转人工的业务规则由应用决定；人工任务的当前状态、审核版本、操作人和理由由 `GraphReviewService` 与 `GraphReviewStore` 管理。完整历史审计可以由持久化 Store 在每次更新时追加保存。

业务审核通常由应用在 `GraphExtractionResult` 或 `GraphIngestionPlan` 之上实现。这样不同产品可以根据关系风险、租户权限、人工规则和领域标注集使用不同门槛。

## 自动拒绝、人工确认和自动接受

### 自动拒绝

自动拒绝适合明确不满足最低条件的结果，例如：

- 存在 `ERROR` 级 issue；
- 没有必需证据；
- 候选置信度低于业务门槛；
- 包含不允许的 `INFERRED` 或 `OPINION`；
- 文档更新结果不完整，却试图删除旧关系。

自动拒绝意味着不执行 Mutation，可以把结果和原因保存到失败或待处理队列。

### 人工确认

人工确认适合结构合法但业务风险或身份不确定的结果，例如：

- 同名实体对应多个 Registry 记录；
- 关系会触发删除或撤回；
- 关系属于高影响类型；
- 多份来源对同一事实冲突；
- 候选是推断或观点；
- 候选使用了新的业务概念。

人工确认后如果修改了候选或实体映射，应重新生成或更新 Mutation，并重新计算计划指纹，不能只修改界面显示内容后继续执行旧 Mutation。

### 自动接受

自动接受只适合经过领域评估的低风险候选，例如：

- 类型和关系已经稳定纳入 Schema；
- 证据完整且可以定位原文；
- 只接受 `EXPLICIT` 关系；
- 置信度达到经标注集校准的门槛；
- 不会触发破坏性删除；
- 自动接受规则已经版本化和审计。

自动接受不是“模型说成功就写入”，而是应用确认候选满足一组可解释的业务条件。

## 人工确认如何落地到 UI

Agents-Flex Graph SDK 提供 `GraphReviewStore` 和 `GraphReviewService`，统一管理待审核任务的创建、查询、
修改、接受、拒绝和归档。应用不再需要自己设计一套审核任务状态机，只需要把这些 SDK 方法包装成自己的
REST API，再由管理后台调用。

审核能力是可选的。自动接受场景直接调用 `GraphIngestionService.ingest(...)` 或
`execute(plan, writer)`，不需要创建 `GraphReviewStore`，也不会产生审核任务。只有需要人工确认时，才配置
`GraphReviewStore` 并创建 `GraphReviewService`。

### 先创建可选的审核服务

自动接受不需要任何审核对象，可以直接使用：

```java
GraphIngestionResult result = ingestion.ingest(
    document, schema, request, graphStore.writer());
```

需要人工审核时再创建服务：

```java
// 演示使用内存实现。生产环境替换为自己的持久化 GraphReviewStore。
GraphReviewStore reviewStore = new InMemoryGraphReviewStore();
GraphReviewService reviewService = new GraphReviewService(reviewStore, ingestion);
```

审核 Store 与文档状态 Store 职责不同：前者保存待审核计划，后者记录已经入图的文档版本。自动接受仍需要文档状态存储来支持内容判重和后续文件更新，但不需要审核 Store。

### 先保存一份待审核任务

调用 `plan(...)` 或 `planChunks(...)` 只生成计划，不会写图。应用决定需要人工确认时，把计划交给
`reviewService.submit(plan)`；SDK 会生成 `taskId`，并通过 `GraphReviewStore` 保存完整计划快照。

| 字段 | 用途 |
| --- | --- |
| `taskId` | SDK 审核任务标识，供查询、接受、拒绝和 UI 按钮调用 |
| `operationId` | 关联一次具体的入图写入意图，重试时保持不变 |
| `space`、`documentId`、`documentVersion` | 确定知识库和来源文档 |
| `planFingerprint` | 防止 UI 展示的计划与实际执行的计划不一致 |
| `schemaVersion`、`extractionFingerprint` | 还原抽取时使用的 Schema 和抽取配置 |
| 抽取结果快照 | 展示全部候选、合法候选、证据、问题和实体归一结果 |
| Mutation 快照 | 展示最终将要 upsert 或删除的节点、边 |
| `previousState`、`nextState`、`staleEdgeKeys` | 检查 revision、版本替换和过期关系 |
| `status`、`reviewVersion` | SDK 审核状态和乐观锁版本 |

`planFingerprint` 不是 UI 的装饰字段。审核者修改候选、实体匹配或删除决定后，实际执行内容已经改变，
SDK 通过 `reviewVersion` 校验 UI 提交的版本是否仍然最新。计划指纹由执行服务内部计算；这里不是任务的独立属性。SDK 还会在
`execute` 时根据完整计划计算操作指纹，并由 `GraphIngestionOperationStore` 保存和校验；应用不应把页面上的
自定义指纹当成 SDK 操作存储中的替代品。

SDK 提供以下审核状态：

```text
PENDING_REVIEW -> EXECUTING -> COMPLETED
       |              |
       v              v
CHANGES_REQUESTED   FAILED
       |
       v
   REJECTED

FAILED / 中断的 EXECUTING -> resume -> COMPLETED 或 FAILED
COMPLETED / REJECTED -> ARCHIVED
```

这些状态由 `GraphReviewTaskStatus` 定义，`GraphReviewService` 负责合法流转。它们描述的是审核任务，
不是图数据库事务状态。

### 审核任务的增删改查到底由谁完成

`taskId` 是 `GraphReviewService` 使用的审核任务 ID，`operationId` 是 `GraphIngestionService` 使用的入图操作 ID。
调用 `accept(taskId, ...)` 时，审核服务先从 Store 取出计划，再把计划交给入图服务执行，使用者不需要自己完成这层转换。

| 操作 | SDK 方法 | 结果 |
| --- | --- | --- |
| 生成任务 | `reviewService.submit(plan)` | 返回 `GraphReviewTask` 和 `taskId` |
| 查询详情 | `reviewService.get(taskId)` | 返回任务，不存在时返回 `null` |
| 查询列表 | `reviewService.list(query)` | 按 Space、文档和状态分页查询 |
| 修改候选 | `reviewService.applyPatch(...)` | SDK 重建计划并递增审核版本 |
| 替换完整计划 | `reviewService.updatePlan(...)` | 保存重新抽取或撤回生成的计划 |
| 恢复执行 | `reviewService.resume(...)` | 恢复冻结计划并同步审核状态 |
| 接受任务 | `reviewService.accept(...)` | 执行入图并返回 `GraphReviewExecutionResult` |
| 拒绝任务 | `reviewService.reject(...)` | 保存拒绝原因，不写图 |
| 要求修改 | `reviewService.requestChanges(...)` | 标记为 `CHANGES_REQUESTED` |
| 归档任务 | `reviewService.archive(...)` | 保留任务和审计信息 |

特别要区分两个列表：`ingestion.listRecoverableOperations(limit)` 返回的是“图写入已经开始但还没有完成”的
恢复操作，供后台任务调用 `resume(operationId, writer)`；它不是“等待人工审核任务列表”，不能直接拿来填充审核页面。

### 1. 如何生成一条待审核任务

应用接收到文档后，先生成计划，再根据自动审核策略决定是直接执行还是创建人工任务：

```java
GraphIngestionPlan plan = ingestion.plan(document, schema, request);
if (plan.getStatus() == GraphIngestionPlan.Status.UNCHANGED) {
    // 内容未变，无抽取结果，也无需人工审核。
    ingestion.execute(plan, graphStore.writer());
} else {
    ReviewDecision decision = reviewPolicy.decide(
        plan.getExtractionResult(), plan.getStaleEdgeKeys());
    switch (decision) {
        case HUMAN_REVIEW:
            GraphReviewTask task = reviewService.submit(plan);
            // 保存/返回 task.getTaskId()，不写图。
            break;
        case AUTO_REJECT:
            // 保存拒绝原因到业务日志，不写图、不创建人工任务。
            break;
        case AUTO_ACCEPT:
            ingestion.execute(plan, graphStore.writer());
            break;
    }
}
```

生成任务时，`GraphReviewStore` 保存完整的计划快照，而不是只保存 `taskId` 和页面摘要。生产环境只需实现
`GraphReviewStore` 的持久化版本；审核状态、版本校验和计划关联由 `GraphReviewService` 负责。

### 2. 如何查询任务列表和详情

审核页面可以直接调用 SDK 的查询方法：

```java
List<GraphReviewTask> tasks = reviewService.list(
    GraphReviewTaskQuery.builder()
        .space("novel_knowledge")
        .status(GraphReviewTaskStatus.PENDING_REVIEW)
        .offset(0)
        .limit(20)
        .build());

// 详情页：读取任务、候选快照和当前待执行 Mutation。
GraphReviewTask task = reviewService.get(taskId);
GraphIngestionPlan plan = task.getPlan();
GraphExtractionResult extraction = plan.getExtractionResult();
GraphMutation mutation = plan.getMutation();
```

UI 可以把 `tasks` 展示在审核列表，把 `task` 中的计划和抽取结果展示在详情页。这里仍然要区分
`reviewService.list(...)` 和 `ingestion.listRecoverableOperations(...)`：前者是人工审核任务列表，后者是图写入失败后的恢复操作列表。

### 3. 审核者修改任务时调用什么

常见修改直接通过 `GraphReviewPatch` 表达，应用不用手动拼装 Mutation 和文档状态：

```java
GraphReviewTask task = reviewService.get(taskId);
GraphExtractionResult extraction = task.getPlan().getExtractionResult();
String candidateKey = extraction.getValidatedEntities().get(0).getCandidateKey();

GraphReviewPatch patch = GraphReviewPatch.builder()
    // 匹配已有实体：提供已确认的完整节点标签和属性。
    .resolveEntity(candidateKey,
        GraphNode.builder("person-42", "Person")
            .property("name", "张三").build())
    // 0 表示当前 extraction.getValidatedRelations() 的第一个关系候选。
    .rejectRelation(0)
    .build();

GraphReviewTask updated = reviewService.applyPatch(
    taskId, task.getReviewVersion(), patch, "人工确认实体，拒绝证据不足的关系", currentUser());
```

关系候选没有独立的 candidateKey，因此关系操作使用当前合法关系列表的下标，并必须携带 `reviewVersion`；
不能把上一版本的下标用在修改后的任务上。实体操作使用 SDK 结果返回的完整候选键，不自行拼接。

还可以调用 `rejectEntity(candidateKey)` 拒绝实体及其关联关系，调用
`entityProperties(candidateKey, properties)` 或 `relationProperties(index, properties)`
替换完整属性映射。属性修改会重新检查 Schema 白名单、类型和必填约束；匹配节点必须具有与候选相同的类型。

SDK 会重建批准候选、实体映射、Mutation、文档状态、事实来源和实体注册记录，并递增审核版本。
拒绝关系不会直接删除共享关系：是否删除旧事实仍取决于原计划的旧关系策略及其他文档来源。
原始模型候选和问题保留在 allEntities/allRelations/issues 中；审核 Patch 不会把原始 ERROR 自动清除。

此过程不调用模型，也不写图。UI 重新展示 updated 中的变更预览，用户确认后再接受。
高级场景如重新抽取、撤回文档，可通过入图服务生成完整计划，再使用 `updatePlan` 替换尚未执行的任务计划。

### 4. 点击“接受并入图”调用什么

接受接口只需要把 `taskId` 和当前版本交给审核服务：

```java
public GraphReviewExecutionResult accept(String taskId, long expectedReviewVersion) {
    return reviewService.accept(
        taskId, expectedReviewVersion, graphStore.writer(), currentUser());
}
```

每次操作提交当前 `reviewVersion`。重复提交旧版本会返回版本冲突，UI 应重新读取任务；对已完成任务提交当前版本会得到零影响成功结果，不重复写图。执行中的任务不允许再次接受或修改。

如果进程退出后任务滞留在 EXECUTING，或任务显示 FAILED，先读取最新版本，再恢复原计划：

```java
GraphReviewTask task = reviewService.get(taskId);
GraphReviewExecutionResult recovered = reviewService.resume(
    taskId, task.getReviewVersion(), graphStore.writer(), currentUser());
```

恢复要求入图服务配置 `GraphIngestionOperationStore`，并在多实例环境使用共享文档锁。只对确认已中断的任务恢复；
SDK 会按操作阶段补齐写入与状态提交，同时同步审核状态，不重新调用模型。

### 5. 点击“拒绝”和“删除”分别做什么

“拒绝”直接调用审核服务，不会调用图写入方法：

```java
public GraphReviewTask reject(String taskId, long expectedReviewVersion, String reason) {
    return reviewService.reject(
        taskId, expectedReviewVersion, reason, currentUser());
}
```

拒绝后的候选、证据、原始响应和理由仍由 `GraphReviewStore` 保留，方便质量评估和问题回放。对审核任务做物理删除也通常不推荐，
因为会破坏审计链；页面上的“删除”按钮可以映射为 `reviewService.archive(...)`，但归档只允许已完成或已拒绝任务。失败任务可能已部分执行，应先恢复。SDK 保存当前快照；长期版本历史由持久化 Store 实现追加记录。

### 审核列表页显示什么

列表页的目标是让审核者快速判断“先处理哪一项”，而不是把整个原文塞进表格。每行可以显示：

- 文档名称、`documentId` 和文档版本；
- 目标 Space；
- 抽取时间、提交人或来源批次；
- 候选实体数、候选关系数；
- `ERROR` 和 `WARNING` 数量；
- 最低置信度和高风险关系数量；
- 是否存在待撤回的 `staleEdgeKeys`；
- 当前审核状态、最后操作人和更新时间；
- `taskId`、`operationId` 和计划版本。

列表上的“查看”“接受”“拒绝”“退回修改”按钮只改变应用的审核任务，不应在列表页直接绕过版本检查写图。
批量接受也必须逐项检查文档 revision 和计划指纹，不能把一批过期计划当成一个无条件事务。

### 审核详情页的三块内容

详情页应让审核者能够从候选一路追溯到原文，并看清点击“接受”后究竟会写什么。推荐分成三块：

1. **原文证据区**：展示文档、Chunk、`quote`、`startOffset`、`endOffset`，以及页码、章节和来源 metadata。
   点击证据可以跳转到原文位置；偏移是当前 Chunk 内的 Java 字符偏移，不是 PDF 字节位置。
2. **候选知识区**：展示实体名称、类型、属性、别名、关系类型、source/target、confidence、
   `assertionType`、问题列表、实体归一候选和最终 `nodeId`。应同时提供“全部候选”和“通过自动校验的候选”，
   这样审核者不会误以为页面只展示了模型认为正确的内容。
3. **变更预览区**：展示当前 Mutation 中的新增节点、新增关系、属性更新、待删除边、
   `staleEdgeKeys`、旧状态与新状态和 expected revision。预览区必须来自即将执行的 Mutation 快照，
   不能只根据页面表单临时拼接一份与执行对象不同的摘要。

### 问题卡片与“解决”按钮

详情页还应把 `GraphExtractionIssue` 按候选和字段分组显示，而不是只在页面顶部显示“存在错误”。一张问题卡片
至少包含问题级别、问题代码、可读消息、关联的 `candidateKey` 或关系、原文证据和当前处理状态。例如：

```text
ERROR    RELATION_ENDPOINT_TYPE_MISMATCH
关系 WORKS_FOR 的 source 类型应为 Person，当前候选是 Company
关联候选：relation-17

[修改端点] [匹配已有实体] [拒绝关系] [重新抽取]
```

不同问题对应的“解决”动作可以是：

| 问题类型 | UI 动作 | 点击后必须完成的事情 |
| --- | --- | --- |
| 缺少必填属性 | 修改属性 | 校验新值的 Schema 类型和必填约束，再重建节点 |
| 端点类型或实体身份不确定 | 匹配已有实体/创建新实体 | 更新 Registry 映射、关系端点和实体注册快照 |
| 引文或偏移不正确 | 修正证据/重新抽取 | 重新验证引文位于当前 Chunk，不能只把问题标记为已解决 |
| 低置信度或推断关系 | 接受为业务事实/拒绝关系 | 保存审核理由，并从批准候选中保留或移除该关系 |
| 过期关系待撤回 | 确认撤回/保留关系 | 检查其他 ACTIVE 文档来源，再决定是否加入删除变更 |
| 模型协议或 Schema 错误 | 重新抽取/退回修改 | 产生新的抽取版本或让上游修复，不能伪造一个通过状态 |

“标记已解决”只能表示应用已经完成了相应修正并重新校验。它不是 SDK 的写入操作，也不能单独消除
`GraphExtractionResult.getIssues()` 中的原始问题。应用应保留原始问题、解决动作、解决人和解决后的新快照，
对于合法候选的属性、实体匹配和拒绝操作，应用调用 `applyPatch`，由 SDK 重建计划；引文、类型或协议错误则需要重新抽取并调用 `updatePlan`。只有新快照通过业务规则后，“接受并入图”按钮才应变为可用。

### UI 按钮如何触发后续流程

下表左侧是 UI 动作，右侧是应用把请求转给 SDK 的方法。HTTP 路由仍由应用决定。

| UI 操作 | 应用层动作 | SDK 动作 |
| --- | --- | --- |
| 接受并入图 | `reviewService.accept(taskId, version, writer)` | 执行计划并更新为 `COMPLETED` 或 `FAILED` |
| 拒绝 | `reviewService.reject(taskId, version, reason)` | 更新为 `REJECTED`，不写图 |
| 修改后接受 | `applyPatch` 后重新预览，再 `accept` | SDK 重建并执行新计划 |
| 匹配已有实体 | `GraphReviewPatch.resolveEntity` + `applyPatch` | 重建端点、来源和注册记录 |
| 创建新实体 | 同上，指定新的稳定节点 ID | 写成功后注册实体 |
| 拒绝关系 | `GraphReviewPatch.rejectRelation` + `applyPatch` | 重建批准关系和文档来源 |
| 确认撤回 | 提交撤回计划后调用 `updatePlan` 和 `accept` | 执行已确认的删除变更 |
| 要求重新抽取 | 重新调用 `GraphIngestionService.plan(...)` 后 `updatePlan` | 替换任务当前快照；历史由 Store 保存 |

最常见的“接受并入图”链路如下：

```text
点击“接受并入图”
  -> POST /review/tasks/{taskId}/accept
  -> 读取审核任务和冻结的计划
  -> 校验 reviewVersion、planFingerprint、document revision
  -> 标记 EXECUTING
  -> ingestion.execute(plan, graphStore.writer())
  -> 检查 GraphIngestionResult.isSuccess()
  -> 保存写入结果和审计记录
  -> 标记 COMPLETED 或 FAILED
```

如果页面没有修改候选，应该执行最初保存的冻结计划，而不是重新调用模型。这样审核者看到的内容和实际写入
内容完全一致，也能通过 `operationId` 做幂等重试。

### 应用层后端接口示例

应用可以把 SDK 方法包装成类似下面的接口：

```text
GET  /review/tasks/{taskId}
POST /review/tasks/{taskId}/accept
POST /review/tasks/{taskId}/reject
POST /review/tasks/{taskId}/request-changes
POST /review/tasks/{taskId}/resolve-entity
POST /review/tasks/{taskId}/retract
```

“接受”接口的伪代码可以写成：

```java
GraphReviewExecutionResult result = reviewService.accept(
    taskId,
    expectedReviewVersion,
    graphStore.writer(),
    currentUser());
```

这里的 HTTP 接口和权限校验属于应用代码，审核任务和计划存储由 `GraphReviewStore` 提供。`result.isSuccess()`
表示图写入和入图状态提交均已成功；如果执行过程中发生可恢复故障，应依据操作日志调用
`reviewService.resume(taskId, currentVersion, writer)`，而不是重新抽取或让浏览器重复提交另一份计划。

### 修改后为什么必须重建计划

`GraphExtractionResult`、`GraphMutation` 和 `GraphIngestionPlan` 都是不可变快照。人工点击“拒绝关系”、
修改属性或选择另一个实体时，不能只改数据库中的审核 JSON，也不能直接从 `getValidatedEntities()` 或 `getValidatedRelations()`
列表中删除元素后继续执行旧计划。

对于拒绝候选、属性修改和实体匹配，`applyPatch` 会自动重建批准结果，再交给入图服务计算
新计划。调用方只负责传入审核意图和业务理由，不应自行同步 nodeIds、edgeKeys 或 factSources。

审核任务存储必须保存完整计划。如果要在进程重启后继续修改，还应保存计划中的抽取结果、
`reviewSchema` 和 `reviewRequest`；恢复时使用 `GraphIngestionPlan.restore(..., schema, request)`
和 `GraphReviewTask.restore(...)`，保留原审核版本。只保存执行字段的计划可以执行，但不能使用 Patch。

Patch 仅修改已经通过自动校验的候选，不能新增未经校验的证据或把无效候选强行转为合法。
需要修正引文、候选类型或模型协议时，先修复上游输入和配置，再重新抽取生成计划，通过 `updatePlan` 替换。
完整审计历史、业务权限和实体主数据合并仍由应用负责。

### 过期计划、并发点击和失败重试

审核页面可能打开数小时，期间同一文档也可能被其他任务更新。执行前至少检查：

- 审核任务仍处于 `PENDING_REVIEW` 或允许执行的状态；
- `reviewVersion` 没有被其他审核者更新；
- 计划的 expected revision 仍等于文档状态的当前 revision；
- Schema、抽取配置和 Space 没有被错误替换；
- 实际 Mutation 与审核者确认的 fingerprint 相同。

任何一项不满足，都应把任务置为 `CHANGES_REQUESTED` 或 `FAILED`，让 UI 重新加载，而不是强行覆盖新版本。
“接受”按钮需要幂等：浏览器超时后再次点击应复用同一个 `operationId`。写图成功但应用进程在状态提交前退出时，
使用 `reviewService.resume(taskId, currentVersion, writer)` 完成原计划的恢复；`resume` 不会重新调用大模型，也不会替人工做新的决定。

### 一个完整的审核按钮映射

以“张三可能在 2020 年加入星河科技”为例，详情页可以显示：

```text
候选关系：张三 -[WORKS_FOR]-> 星河科技
证据：张三可能在 2020 年加入了星河科技。
风险：assertionType=INFERRED，关系置信度=0.86，年份需要确认

[匹配已有实体] [创建新实体] [修改属性] [拒绝关系]
[接受并入图]   [拒绝整批]   [要求重新抽取]
```

审核者选择“匹配已有实体”后，应用先展示 Registry 候选；选择完成并点击“接受并入图”时，后端应执行“更新
端点 -> `applyPatch` 重建计划 -> `accept` 校验 revision 并执行”这条链路。审核者选择“拒绝关系”时，通过 Patch 从批准候选中移除这条边，
仍可保留同一批次中已经确认的实体或其他关系；最终执行的 Mutation 必须在变更预览区重新展示给审核者。

## 一个审核策略示例

下面示例演示“有结构错误就拒绝，存在高风险关系就转人工，其余低风险结果自动接受”的基本思路。自动接受路径只需要入图服务；人工审核路径才需要 `GraphReviewService`。

~~~java
enum ReviewDecision {
    AUTO_ACCEPT,
    AUTO_REJECT,
    HUMAN_REVIEW
}

ReviewDecision decide(GraphExtractionResult result) {
    // 严格模式：任何 ERROR 都不自动写入。
    if (result.hasErrors()) {
        return ReviewDecision.AUTO_REJECT;
    }

    for (GraphRelationCandidate relation : result.getValidatedRelations()) {
        // 观点和推断需要人工确认。
        if (relation.getAssertionType() != GraphAssertionType.EXPLICIT) {
            return ReviewDecision.HUMAN_REVIEW;
        }
        // 高影响关系可以使用业务维护的关系白名单。
        if (isHighRiskRelation(relation.getType())) {
            return ReviewDecision.HUMAN_REVIEW;
        }
        if (relation.getConfidence() < 0.90D
            || relation.getEvidence() == null) {
            return ReviewDecision.HUMAN_REVIEW;
        }
    }

    for (GraphEntityCandidate entity : result.getValidatedEntities()) {
        if (entity.getConfidence() < 0.90D
            || entity.getEvidence() == null) {
            return ReviewDecision.HUMAN_REVIEW;
        }
    }

    return ReviewDecision.AUTO_ACCEPT;
}

GraphIngestionPlan plan = ingestion.plan(document, schema, request);
// UNCHANGED 计划没有抽取结果，先处理内容判重。
if (plan.getStatus() == GraphIngestionPlan.Status.UNCHANGED) {
    ingestion.execute(plan, graphStore.writer());
    return;
}
GraphExtractionResult result = plan.getExtractionResult();
ReviewDecision decision = decide(result);
switch (decision) {
    case AUTO_REJECT:
        // 保存业务日志即可，不创建审核任务，也不执行 Mutation。
        logRejected(result);
        break;
    case HUMAN_REVIEW:
        reviewService.submit(plan);
        break;
    case AUTO_ACCEPT:
        GraphIngestionResult write = ingestion.execute(
            plan, graphStore.writer());
        if (!write.isSuccess()) {
            handleWriteFailure(write);
        }
        break;
}
~~~

这个示例是“整份抽取结果”的审核策略。如果产品需要只拒绝某一条候选、保留同一批次的其他候选，应在 `GraphCandidateValidator` 阶段实现候选级规则，或者通过 `GraphReviewPatch` 和 `applyPatch` 修改批准候选；不能直接修改 `getValidatedEntities()` 或 `getValidatedRelations()` 返回的只读列表。

## 修改候选和审核待撤回关系

产品可以在执行前修改候选属性、选择不同的 Registry 实体或拒绝某条关系。调用 `applyPatch` 后，SDK 重建待执行计划并递增 `reviewVersion`；UI 重新加载预览，确认时提交该版本。只修改界面展示字段，然后继续执行旧 Mutation，会导致审核记录与实际图数据不一致。应用无需另外设计审核指纹机制。

文档新版本还可能产生 `staleEdgeKeys`。它表示当前文档不再产生的旧关系，不等于立即删除：

```text
旧关系不再出现在本版本
  -> 查询其他 ACTIVE 文档的来源
  -> 仍有来源：只撤销当前事实
  -> 没有来源：按策略决定是否删除物化边
```

抽取结果存在 ERROR 或 Chunk 不完整时，默认禁止使用不完整结果清理旧关系。关系撤回、高影响关系和来源冲突通常应进入人工队列。

## 审核记录和 SDK 边界

`GraphReviewStore` 负责审核任务快照和状态查询；生产实现至少应能够持久化：

| 信息 | 用途 |
| --- | --- |
| `taskId`、`operationId`、`reviewVersion` | 关联审核任务和具体执行意图 |
| 候选、证据和 Mutation 版本 | 还原审核者当时看到和确认的内容 |
| `GraphReviewTask` 的 actor、时间、状态和 reason | 责任追踪和当前决策 |
| 修改前后的差异 | 解释人工干预 |
| 拒绝或修改原因 | 质量反馈和模型评估 |
| 执行结果和操作阶段 | 关联最终写入状态 |

如果业务需要完整的多次审核历史、多人审批链或字段级审计，可以在 `GraphReviewStore` 之外增加审计存储；
但创建任务、查询任务、接受、拒绝和归档本身由 `GraphReviewService` 提供。

审核记录应采用追加式或带版本的保存方式，不要覆盖原始模型结果。

| 能力 | SDK 提供 | 应用需要提供 |
| --- | --- | --- |
| 候选、证据和问题 | 数据模型 | 展示和筛选界面 |
| Mutation 和入图计划 | 可审核执行对象和计划重建 | 业务决策和变更预览 |
| 人工审核任务 | Store 接口、查询、接受、拒绝、修改和归档 | 持久化 Store 实现、HTTP 接口和权限 |
| operationId 和阶段 | 恢复所需数据 | 任务队列、租约和告警 |
| Entity Registry | 身份读写接口 | 业务主数据和权限 |
| 审核事实 | 原始字段和来源 | 审核人、审批链和审计存储 |

## JSON 响应协议

默认 LLM Extractor 要求包含 `entities` 和 `relations` 数组的 JSON 对象：

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
      "startOffset": 0,
      "endOffset": 9,
      "confidence": 0.98
    }
  ],
  "relations": [
    {
      "sourceMentionId": "m1",
      "type": "MEMBER_OF",
      "targetMentionId": "m2",
      "properties": {},
      "evidence": "林默加入了青云宗",
      "confidence": 0.97,
      "assertionType": "EXPLICIT"
    }
  ]
}
~~~

根对象损坏或缺少两个数组时，当前 Chunk 无法建立候选边界并失败。单个实体或关系格式错误时，只记录 `MALFORMED_ENTITY` 或 `MALFORMED_RELATION`，同一响应中的其他候选仍继续校验。

原始响应会保存在结果中，可能包含业务正文和敏感信息。不要未经脱敏写入普通生产日志，也应为持久化设定权限与保留周期。

## 审核结果到写入

一个保守流程可以是：

~~~java
GraphIngestionPlan plan = ingestion.plan(document, schema, request);
if (plan.getStatus() != GraphIngestionPlan.Status.UNCHANGED
    && needsHumanReview(plan)) {
    // 只有转人工的分支需要审核服务。
    GraphReviewTask task = reviewService.submit(plan);
    return;
}

GraphIngestionResult write = ingestion.execute(plan, graphStore.writer());
if (!write.isSuccess()) {
    handleWriteFailure(write);
}
~~~

`needsHumanReview` 和 `handleWriteFailure` 是应用的业务方法。文档版本管理场景通过入图服务执行完整计划，才能同时维护事实来源、文档状态和实体注册信息。

仅检查 `hasErrors()` 仍不等于完成业务审核。它只说明没有 ERROR 级结构化问题。

## 常见问题

### SDK 有没有 `autoAccept` 或 `autoReject` 开关？

没有统一的业务判断开关。`GraphExtractionOptions` 负责抽取过程和结构质量门槛，应用按业务风险决定自动接受、自动拒绝还是转人工。自动接受直接调用入图服务；转人工后的任务管理由 `GraphReviewService` 提供，只有这个分支需要 `GraphReviewStore`。

### `minConfidence` 设置为 0.9 后就会自动接受吗？

不会。它只会筛掉低于阈值的候选，仍然需要检查证据、断言类型、实体匹配、关系风险、文档 revision 和业务审核规则。

### 能不能只拒绝一条关系，保留同一批次的其他关系？

可以。人工审核调用 `GraphReviewPatch.rejectRelation(index)` 和 `reviewService.applyPatch(...)`，由 SDK 重建计划；自动筛选可以在 Validator 中实现。`GraphExtractionResult` 中的列表是只读结果，不能直接删除元素后继续执行原 Mutation。

### 有一个坏候选，整篇都会失败吗？

单条格式或 Schema 错误通常被隔离并记录 issue；根 JSON 损坏、模型调用失败或校验器异常是否中止，取决于 `failOnChunkError`。

### evidence 在原文中出现多次怎么办？

偏移可以定位具体出现位置；没有偏移时只能证明 Chunk 中包含该引文。高要求场景应让解析链路保留更精确的源位置。

### confidence 很高可以跳过审核吗？

不能仅凭模型自报置信度决定。应根据业务类型、标注评估、证据完整性和错误成本制定策略。

### 有 ERROR 时可以写合法子集吗？

技术上 Mutation 仍可能包含合法子集，但文档版本更新中会带来关系集合不完整风险。默认应拒绝；只有明确理解影响时才允许部分提交。

## 质量检查清单

- 是否区分全部候选、合法候选和最终批准项；
- 是否要求关键类型提供连续原文证据；
- documentId、chunkId 和偏移能否定位原文；
- 置信阈值是否通过领域标注集校准；
- 是否区分明确事实、推断和观点；
- Chunk 失败时是否阻止破坏性关系清理；
- 原始响应是否按敏感数据管理；
- 自动接受和人工审核规则是否版本化；
- 写入前是否检查问题、Mutation 和业务风险；
- 写入后是否有抽样、对账和错误反馈闭环。

审核完成后，还需要通过[实体归一](/zh/graph/knowledge-extraction-entity-resolution)解决跨段、跨文件的实体重复问题。
