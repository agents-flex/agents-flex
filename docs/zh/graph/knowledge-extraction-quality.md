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
  -> 确认 GraphMutation / 增量计划
  -> 显式写入 GraphWriter
```

审核完成前，`GraphMutation` 只是待审核变更，不代表已经写入图数据库。

审核后台可以围绕同一份候选和计划完成以下操作：

```text
展示候选与原文
  -> 接受、修改或拒绝
  -> 选择规范实体
  -> 审核待撤回关系
  -> 确认 GraphMutation / IncrementalGraphIngestionPlan
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

List<GraphEntityCandidate> acceptedEntities =
    result.getEntities();
List<GraphRelationCandidate> acceptedRelations =
    result.getRelations();

List<GraphExtractionIssue> issues =
    result.getIssues();
GraphMutation mutation = result.getMutation();
~~~

| 内容 | 含义 |
| --- | --- |
| `allEntities/allRelations` | 解析器得到的全部候选，包括后续被校验拒绝的候选 |
| `entities/relations` | 通过 Schema 和质量校验的合法子集 |
| `issues` | 解析、校验和 Chunk 容错产生的问题 |
| `mutation` | 合法候选经实体归一后生成的待审核变更 |

即使存在 ERROR，流水线仍可能生成合法子集和 Mutation。Mutation 的存在不代表应当写入，更不代表已经写入。

可以用下面的代码准备审核数据：

~~~java
List<GraphEntityCandidate> allEntities =
    result.getAllEntities();
List<GraphRelationCandidate> allRelations =
    result.getAllRelations();
List<GraphEntityCandidate> acceptedEntities =
    result.getEntities();
List<GraphRelationCandidate> acceptedRelations =
    result.getRelations();
List<GraphExtractionIssue> issues =
    result.getIssues();
GraphMutation mutation = result.getMutation();

// 审核系统可以展示全部候选、合法候选、证据和问题。
review(allEntities, allRelations, acceptedEntities,
    acceptedRelations, issues, mutation);
~~~

其中，`allEntities/allRelations` 不能省略。它们包含被拒绝的候选，便于审核者理解模型原本返回了什么，也便于后续质量评估和问题回放。

审核后台还应根据审核场景保存：

- `GraphEvidence` 的文档、Chunk、引文和偏移；
- `GraphEntityResolution`、`nodeId` 和 `GraphEdgeKey`；
- 增量计划的 `previousState`、`nextState` 和 expected revision；
- `staleEdgeKeys`，以及其他 ACTIVE 文档是否仍然支持这些关系；
- `operationId`、`planFingerprint`、Schema 版本和抽取配置指纹；
- 当前和历史事实来源。

这些数据由 SDK 结果和计划对象提供，审核页面、权限和持久化审计记录由应用负责。

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

`GraphExtractionOptions.fingerprint()` 可以进入增量状态，用于识别抽取配置是否发生变化。

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

这些配置会影响哪些候选能够进入合法结果，但它们不是完整的业务审核策略。SDK 当前没有统一的 `autoAccept`、`autoReject` 配置，也不会替应用保存审核状态、审核人和审批记录。

业务审核通常由应用在 `GraphExtractionResult` 或 `IncrementalGraphIngestionPlan` 之上实现。这样不同产品可以根据关系风险、租户权限、人工规则和领域标注集使用不同门槛。

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

前面的“转人工”不是调用 SDK 中某个现成的按钮或审批接口。Agents-Flex Graph SDK
提供的是候选、证据、问题、实体归一结果和增量执行计划；审核列表、详情页、按钮、权限、审核任务表和
审计记录需要由接入 SDK 的应用实现。应用可以用自己的 REST API、管理后台或其他交互方式承载这套流程。

### 先保存一份待审核任务

调用 `plan(...)` 或 `planChunks(...)` 只生成计划，不会写图。应用决定需要人工确认时，应把计划和展示所需的
抽取快照保存到自己的审核任务存储中，再返回一个 `taskId` 给 UI。至少保存以下字段：

| 字段 | 用途 |
| --- | --- |
| `taskId` | 应用层审核任务标识，供页面和按钮调用 |
| `operationId` | 关联一次具体的增量写入意图，重试时保持不变 |
| `space`、`documentId`、`documentVersion` | 确定知识库和来源文档 |
| `planFingerprint` | 防止 UI 展示的计划与实际执行的计划不一致 |
| `schemaVersion`、`extractionFingerprint` | 还原抽取时使用的 Schema 和抽取配置 |
| 抽取结果快照 | 展示全部候选、合法候选、证据、问题和实体归一结果 |
| Mutation 快照 | 展示最终将要 upsert 或删除的节点、边 |
| `previousState`、`nextState`、`staleEdgeKeys` | 检查 revision、版本替换和过期关系 |
| `reviewStatus`、`reviewVersion` | 应用层状态和乐观锁版本 |

`planFingerprint` 不是 UI 的装饰字段。审核者修改候选、实体匹配或删除决定后，实际执行内容已经改变，
应用必须重新生成审核快照指纹，并在执行前校验 UI 提交的版本仍然是最新版本。SDK 的执行服务还会在
`execute` 时根据完整计划计算操作指纹，并由 `GraphIngestionOperationStore` 保存和校验；应用不应把页面上的
自定义指纹当成 SDK 操作存储中的替代品。

建议的审核状态由应用定义，例如：

```text
PENDING_REVIEW -> APPROVED -> EXECUTING -> COMPLETED
       |             |            |
       v             v            v
   REJECTED   CHANGES_REQUESTED  FAILED
```

这些状态不是 SDK 的枚举。它们描述的是应用的审核任务，而不是图数据库事务状态；应用仍需根据
`IncrementalGraphIngestionResult.isSuccess()` 和操作存储记录更新最终状态。

### 审核任务的增删改查到底由谁完成

先记住一个边界：`taskId` 是应用审核系统生成的 ID，不是 Graph SDK 的 ID。SDK 的
`plan(...)`、`execute(...)` 和 `resume(...)` 都不接收 `taskId`，它们接收的是增量计划或
`operationId`。因此，应用需要有一张自己的 `review_task` 表（或其他持久化仓储），把 `taskId` 和计划快照关联起来。

| 操作 | 应用层要做什么 | Graph SDK 是否提供直接方法 |
| --- | --- | --- |
| 生成任务 | 调用 `plan`，把需要人工审核的计划保存为 `PENDING_REVIEW` | 不提供 `createReviewTask` |
| 查询详情 | 按 `taskId` 从应用仓储读取任务和快照 | 不提供 `getReviewTask` |
| 查询列表 | 按 Space、状态、时间和风险分页查询应用仓储 | 不提供 `listReviewTasks` |
| 修改任务 | 保存人工决策、候选差异和新的计划快照，递增 `reviewVersion` | 不提供 `updateReviewTask` |
| 接受任务 | 应用校验版本后调用 `ingestion.execute(plan, writer)` | 不提供 `approve(taskId)` |
| 拒绝任务 | 应用保存拒绝原因和审计记录，不执行计划 | 不提供 `reject(taskId)` |
| 删除任务 | 通常做软删除或归档，以保留审计记录 | 不提供 `deleteReviewTask` |

特别要区分两个列表：`ingestion.listRecoverableOperations(limit)` 返回的是“图写入已经开始但还没有完成”的
恢复操作，供后台任务调用 `resume(operationId, writer)`；它不是“等待人工审核任务列表”，不能直接拿来填充审核页面。

### 1. 如何生成一条待审核任务

应用接收到文档后，先生成计划，再根据自己的自动审核策略决定是直接执行还是创建人工任务：

```java
IncrementalGraphIngestionPlan plan = ingestion.plan(
    document, schema, request);

ReviewDecision decision = reviewPolicy.decide(
    plan.getExtractionResult(), plan.getStaleEdgeKeys());

if (decision == ReviewDecision.HUMAN_REVIEW) {
    String taskId = reviewTaskRepository.insert(
        ReviewTask.pending(
            newTaskId(),
            plan,
            planSnapshotSerializer.serialize(plan),
            currentUserOrSystem()));
    return taskId; // 返回给应用后台或消息队列，不写图
}

// 自动接受路径：没有人工任务，直接执行冻结计划。
return ingestion.execute(plan, graphStore.writer());
```

生成任务时，应用应保存完整的计划快照，而不是只保存 `taskId` 和一段页面摘要。至少要能恢复
`GraphOptions`、`GraphMutation`、`previousState`、`nextState`、实体注册和 `staleEdgeKeys`。
如果应用已经配置 `GraphIngestionOperationStore`，也可以把操作计划交给该存储；但人工审核任务本身仍要在
应用仓储中保存状态、审核人和审计记录。

### 2. 如何查询任务列表和详情

审核页面查询的代码属于应用服务，例如：

```java
// 列表页：只查询应用自己的审核任务表。
Page<ReviewTaskSummary> page = reviewTaskRepository.findPage(
    ReviewTaskQuery.builder()
        .space("novel_knowledge")
        .status(ReviewTaskStatus.PENDING_REVIEW)
        .page(1)
        .pageSize(20)
        .build());

// 详情页：读取任务、候选快照和当前待执行 Mutation。
ReviewTask task = reviewTaskRepository.require(taskId);
ReviewTaskDetail detail = reviewTaskAssembler.toDetail(task);
```

UI 可以把 `page.items` 展示在审核列表，把 `detail` 展示在审核详情页。这里没有一个等价的
`ingestion.listReviewTasks(...)` SDK 调用；如果把 `listRecoverableOperations(...)` 当成审核列表，
页面会漏掉尚未开始执行的 `PENDING_REVIEW` 任务，也会把失败恢复操作误显示成新审核任务。

### 3. 审核者修改任务时调用什么

例如审核者点击“拒绝关系”“匹配已有实体”或“修改属性”，前端提交的是应用接口：

```text
POST /review/tasks/{taskId}/resolve
{
  "reviewVersion": 3,
  "actions": [
    {"type": "RESOLVE_ENTITY", "candidateKey": "chunk-1:m1", "nodeId": "person-42"},
    {"type": "REJECT_RELATION", "candidateKey": "chunk-1:r2", "reason": "证据不足"}
  ]
}
```

后端处理顺序是：

```text
按 taskId 读取任务
  -> 校验 reviewVersion 和任务状态
  -> 应用 actions 到批准候选快照
  -> 重新校验 Schema、证据、实体端点和业务规则
  -> 重新构造 GraphMutation 和 nextState
  -> 保存新快照，reviewVersion + 1
  -> 返回新的详情和变更预览
```

这里仍然不调用 `execute`，因为审核者可能还要继续修改。只有用户点击“接受并入图”时，才进入执行流程。

### 4. 点击“接受并入图”调用什么

接受接口使用 `taskId` 找到任务，但传给 SDK 的不是 `taskId`，而是任务中冻结或重建后的
`IncrementalGraphIngestionPlan`：

```java
public IncrementalGraphIngestionResult accept(String taskId, long expectedReviewVersion) {
    ReviewTask task = reviewTaskRepository.lockForAccept(
        taskId, expectedReviewVersion);

    // task.loadPlanSnapshot() 是应用自己的反序列化逻辑。
    IncrementalGraphIngestionPlan plan = task.loadPlanSnapshot();
    reviewTaskRepository.markExecuting(taskId, expectedReviewVersion);

    try {
        IncrementalGraphIngestionResult result = ingestion.execute(
            plan, graphStore.writer());
        if (result.isSuccess()) {
            reviewTaskRepository.markCompleted(taskId, result);
        } else {
            reviewTaskRepository.markFailed(taskId, result.getWriteResult());
        }
        return result;
    } catch (RuntimeException failure) {
        reviewTaskRepository.markFailed(taskId, failure);
        throw failure;
    }
}
```

用户重复点击或浏览器超时后，应用仍使用同一个 `taskId` 和 `operationId` 做幂等控制。若图写入已经成功但
状态收尾中断，应由后台任务调用 `ingestion.resume(operationId, graphStore.writer())`，而不是再次调用模型或创建
另一条审核任务。

### 5. 点击“拒绝”和“删除”分别做什么

“拒绝”不会调用 Graph SDK 的写入方法：

```java
public void reject(String taskId, long expectedReviewVersion, String reason) {
    ReviewTask task = reviewTaskRepository.lockForDecision(
        taskId, expectedReviewVersion);
    reviewTaskRepository.markRejected(
        task, reason, currentUserOrSystem());
}
```

拒绝后的候选、证据、原始响应和理由仍应保留，方便质量评估和问题回放。对审核任务做物理删除也通常不推荐，
因为会破坏审计链；页面上的“删除”按钮更适合实现为 `ARCHIVED` 或 `DELETED` 软状态，并限制只有管理员可以使用。

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
然后重新生成 Mutation 和审核指纹；只有新快照通过规则后，“接受并入图”按钮才应变为可用。

### UI 按钮如何触发后续流程

下表中的接口名是应用层示例，不是 SDK 已经提供的 HTTP API。

| UI 操作 | 应用层动作 | SDK 动作 |
| --- | --- | --- |
| 接受并入图 | 校验任务状态、`reviewVersion`、`planFingerprint`，冻结审核快照 | 未修改计划时调用 `ingestion.execute(plan, writer)` |
| 拒绝 | 记录原因、审核人和时间，不产生图写入 | 不调用 `execute`，保留候选供回放和评估 |
| 修改后接受 | 保存字段和候选的前后差异，重新校验并组装计划 | 用新的 Mutation 和状态快照执行新的计划 |
| 匹配已有实体 | 展示 Registry 候选，人工选择规范 `nodeId` | 更新节点和关系端点后重新组装 Mutation |
| 创建新实体 | 申请稳定 ID，保存名称、类型和别名 | 将新节点及其关系加入新的 Mutation，并保存实体注册 |
| 拒绝关系 | 从“批准候选”中移除该关系，并保留拒绝原因 | 重新组装 Mutation；不能修改只读的 `getRelations()` 列表 |
| 确认撤回 | 检查是否仍有其他 ACTIVE 文档支持该边，再确认删除 | 使用撤回计划或包含删除边的新计划执行 |
| 要求重新抽取 | 重新调用模型并创建新的业务审核版本 | 不把新结果冒充原操作的恢复；必要时产生新的 `operationId` |

最常见的“接受并入图”链路如下：

```text
点击“接受并入图”
  -> POST /review/tasks/{taskId}/accept
  -> 读取审核任务和冻结的计划
  -> 校验 reviewVersion、planFingerprint、document revision
  -> 标记 EXECUTING
  -> ingestion.execute(plan, graphStore.writer())
  -> 检查 IncrementalGraphIngestionResult.isSuccess()
  -> 保存写入结果和审计记录
  -> 标记 COMPLETED 或 FAILED
```

如果页面没有修改候选，应该执行最初保存的冻结计划，而不是重新调用模型。这样审核者看到的内容和实际写入
内容完全一致，也能通过 `operationId` 做幂等重试。

### 应用层后端接口示例

应用可以为审核页面提供类似下面的接口：

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
ReviewTask task = reviewRepository.require(taskId);
reviewRepository.requirePending(task, expectedReviewVersion);
reviewRepository.requireFingerprint(task, submittedPlanFingerprint);

// 没有人工修改时，直接执行保存的冻结计划。
IncrementalGraphIngestionPlan plan = task.loadPlanSnapshot();
reviewRepository.markExecuting(taskId, expectedReviewVersion);

try {
    IncrementalGraphIngestionResult result = ingestion.execute(
        plan, graphStore.writer());
    if (!result.isSuccess()) {
        reviewRepository.markFailed(taskId, result.getWriteResult());
        return result;
    }
    reviewRepository.markCompleted(taskId, result);
    return result;
} catch (RuntimeException failure) {
    reviewRepository.markFailed(taskId, failure);
    throw failure;
}
```

这里的 `ReviewTask`、`reviewRepository` 和 HTTP 接口都是应用自己的代码。`execute` 成功表示图写入和增量状态
提交已经成功；如果执行过程中发生可恢复故障，应依据操作日志调用 `ingestion.resume(operationId, writer)`，
而不是重新抽取或让浏览器重复提交另一份计划。

### 修改后为什么必须重建计划

`GraphExtractionResult`、`GraphMutation` 和 `IncrementalGraphIngestionPlan` 都是不可变快照。人工点击“拒绝关系”、
修改属性或选择另一个实体时，不能只改数据库中的审核 JSON，也不能直接从 `getEntities()` 或 `getRelations()`
列表中删除元素后继续执行旧计划。

应用需要执行以下步骤：

```text
保存原始抽取快照和人工决策
  -> 得到批准后的候选和实体映射
  -> 重新校验证据、Schema、端点和业务规则
  -> 重新组装 GraphNode、GraphEdge 和 GraphMutation
  -> 更新 nextState 的 nodeIds、edgeKeys 和 factProvenances
  -> 重新检查 staleEdgeKeys 是否仍被其他文档支持
  -> 生成新的计划快照和审核指纹（SDK 执行时还会计算操作指纹）
  -> 在 expected revision 未变化时调用 execute
```

SDK 提供 `GraphMutation.builder()` 和 `IncrementalGraphIngestionPlan.restore(...)` 这类底层构造能力，
但当前没有一个名为“人工批准候选并自动重建计划”的通用审核 API。应用应把上述组装逻辑封装在自己的
`ReviewPlanAssembler` 或类似服务中，并为它编写单元测试。重建 `nextState` 时尤其不能沿用旧关系集合，
否则页面虽然显示拒绝了关系，后续文档版本仍可能把那条边当作当前文档支持的事实。

实体匹配也遵循同一原则：人工选择已有 `nodeId` 后，应该更新节点身份、关系端点和实体注册快照，再重新生成
Mutation；如果创建了新实体，则先持久化稳定的 Registry 记录，再把该记录用于计划组装。实体 Registry 的
业务主数据、权限和合并/拆分审计仍由应用负责。

### 过期计划、并发点击和失败重试

审核页面可能打开数小时，期间同一文档也可能被其他任务更新。执行前至少检查：

- 审核任务仍处于 `PENDING_REVIEW` 或允许执行的状态；
- `reviewVersion` 没有被其他审核者更新；
- 计划的 expected revision 仍等于文档状态的当前 revision；
- Schema、抽取配置和 Space 没有被错误替换；
- 实际 Mutation 与审核者确认的 fingerprint 相同。

任何一项不满足，都应把任务置为 `CHANGES_REQUESTED` 或 `FAILED`，让 UI 重新加载，而不是强行覆盖新版本。
“接受”按钮需要幂等：浏览器超时后再次点击应复用同一个 `operationId`。写图成功但应用进程在状态提交前退出时，
使用 `resume(operationId, writer)` 完成原计划的恢复；`resume` 不会重新调用大模型，也不会替人工做新的决定。

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
端点 -> 重建 Mutation -> 校验 revision -> `execute`”这条链路。审核者选择“拒绝关系”时，应用只移除这条边，
仍可保留同一批次中已经确认的实体或其他关系；最终执行的 Mutation 必须在变更预览区重新展示给审核者。

## 一个审核策略示例

下面示例演示“有结构错误就拒绝，存在高风险关系就转人工，其余低风险结果自动接受”的基本思路。`submitForReview`、`saveRejected` 和 `review` 是应用自己的方法，SDK 不提供这些业务工作流实现。

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

    for (GraphRelationCandidate relation : result.getRelations()) {
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

    for (GraphEntityCandidate entity : result.getEntities()) {
        if (entity.getConfidence() < 0.90D
            || entity.getEvidence() == null) {
            return ReviewDecision.HUMAN_REVIEW;
        }
    }

    return ReviewDecision.AUTO_ACCEPT;
}

ReviewDecision decision = decide(result);
switch (decision) {
    case AUTO_REJECT:
        saveRejected(result);
        break;
    case HUMAN_REVIEW:
        submitForReview(result);
        break;
    case AUTO_ACCEPT:
        GraphWriteResult write = graphStore.writer().mutate(
            result.getMutation(),
            GraphOptions.ofSpace("company_knowledge"));
        if (!write.isSuccess()) {
            handleWriteFailure(write, result);
        }
        break;
}
~~~

这个示例是“整份抽取结果”的审核策略。如果产品需要只拒绝某一条候选、保留同一批次的其他候选，应在 `GraphCandidateValidator` 阶段实现候选级规则，或者在审核后重新构造 Mutation；不能直接修改 `getEntities()` 或 `getRelations()` 返回的只读列表。

## 修改候选和审核待撤回关系

产品可以在执行前修改候选属性、选择不同的 Registry 实体或拒绝某条关系，但修改后必须重新生成或更新待执行 Mutation，并重新计算审核指纹。只修改界面展示字段，然后继续执行旧 Mutation，会导致审核记录与实际图数据不一致。SDK 会在 `execute` 时为实际计划生成操作指纹；应用应同时保存自己的审核快照指纹，便于判断 UI 是否基于最新内容提交。

文档新版本还可能产生 `staleEdgeKeys`。它表示当前文档不再产生的旧关系，不等于立即删除：

```text
旧关系不再出现在本版本
  -> 查询其他 ACTIVE 文档的来源
  -> 仍有来源：只撤销当前事实
  -> 没有来源：按策略决定是否删除物化边
```

抽取结果存在 ERROR 或 Chunk 不完整时，默认禁止使用不完整结果清理旧关系。关系撤回、高影响关系和来源冲突通常应进入人工队列。

## 审核记录和 SDK 边界

SDK 不规定审核表结构，但生产产品至少应保存：

| 信息 | 用途 |
| --- | --- |
| `operationId`、`planFingerprint` | 关联一次具体执行意图 |
| 候选、证据和 Mutation 版本 | 还原审核者当时看到和确认的内容 |
| 决策人、时间和决策 | 责任追踪和审计 |
| 修改前后的差异 | 解释人工干预 |
| 拒绝或修改原因 | 质量反馈和模型评估 |
| 执行结果和操作阶段 | 关联最终写入状态 |

审核记录应采用追加式或带版本的保存方式，不要覆盖原始模型结果。

| 能力 | SDK 提供 | 应用需要提供 |
| --- | --- | --- |
| 候选、证据和问题 | 数据模型 | 展示和筛选界面 |
| Mutation 和增量计划 | 可审核执行对象 | 接受、修改和拒绝操作 |
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
if (result.hasErrors()) {
    return submitForReview(result);
}

reviewEvidence(result.getEntities(), result.getRelations());

GraphWriteResult write = graphStore.writer().mutate(
    result.getMutation(),
    GraphOptions.ofSpace("novel_knowledge"));

if (!write.isSuccess()) {
    throw new IllegalStateException(write.getMessage());
}
~~~

仅检查 `hasErrors()` 仍不等于完成业务审核。它只说明没有 ERROR 级结构化问题。

## 常见问题

### SDK 有没有 `autoAccept` 或 `autoReject` 开关？

没有。`GraphExtractionOptions` 负责抽取过程和结构质量门槛，具体的自动接受、自动拒绝、人工队列和审批记录由应用根据业务风险实现。

### `minConfidence` 设置为 0.9 后就会自动接受吗？

不会。它只会筛掉低于阈值的候选，仍然需要检查证据、断言类型、实体匹配、关系风险、文档 revision 和业务审核规则。

### 能不能只拒绝一条关系，保留同一批次的其他关系？

可以，但需要在 Validator 或审核后重新构造 Mutation。`GraphExtractionResult` 中的列表是只读结果，不能直接删除元素后继续执行原 Mutation。

### 有一个坏候选，整篇都会失败吗？

单条格式或 Schema 错误通常被隔离并记录 issue；根 JSON 损坏、模型调用失败或校验器异常是否中止，取决于 `failOnChunkError`。

### evidence 在原文中出现多次怎么办？

偏移可以定位具体出现位置；没有偏移时只能证明 Chunk 中包含该引文。高要求场景应让解析链路保留更精确的源位置。

### confidence 很高可以跳过审核吗？

不能仅凭模型自报置信度决定。应根据业务类型、标注评估、证据完整性和错误成本制定策略。

### 有 ERROR 时可以写合法子集吗？

技术上 Mutation 仍可能包含合法子集，但增量更新中会带来关系集合不完整风险。默认应拒绝；只有明确理解影响时才允许部分提交。

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
