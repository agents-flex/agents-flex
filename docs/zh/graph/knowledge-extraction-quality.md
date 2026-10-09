# 审核

## 概述

知识抽取会从文档中识别人物、组织及其关系，但模型生成的内容可能有误。例如，原文写的是“张三可能加入星河科技”，模型却把它理解为“张三任职于星河科技”。如果直接写入，这条不确定的信息就会成为知识库中的正式关系，影响后续查询和回答。

**审核是在写入图数据库之前，决定抽取结果是否可以使用的步骤。** 它让应用能够拒绝不合格的结果，自动接受符合规则的结果，或者交给人工查看证据、修正候选后再确认入图。

SDK 会先检查候选是否符合 Schema、属性类型和证据要求等规则。这些检查可以发现数据格式和来源问题，但不能保证事实正确。因此，低风险资料可以按规则自动入图；涉及人物身份、重要业务关系或旧关系撤回时，可以增加人工确认。

| 处理方式 | 适用场景 | 后续动作 |
| --- | --- | --- |
| 自动拒绝 | 抽取出错，或不满足业务规则 | 不执行入图计划 |
| 自动接受 | 通过校验，且满足自动入图条件 | 直接执行计划，不需要审核 Store |
| 人工确认 | 需要核实事实、修改属性或确认实体身份 | 保存审核任务，确认后再入图 |

人工审核由 `GraphReviewService` 提供任务管理方法，`GraphReviewStore` 保存任务及待执行计划。开发者可以将这些方法接入自己的后台。

## 配置示例

### 配置自动校验规则

以下配置要求候选置信度不低于 0.8、提供原文证据，并排除推断和观点关系：

```java
// 抽取选项：决定模型返回的候选需要满足哪些自动校验条件。
GraphExtractionOptions options = GraphExtractionOptions.builder()
    // 模型给出的置信度低于 0.8 时，不让该候选通过校验。
    .minConfidence(0.8)
    // 候选必须提供可在原文中核实的证据。
    .requireEvidence(true)
    // 不将推断关系、观点关系纳入通过校验的结果。
    .includeInferredRelations(false)
    .includeOpinionRelations(false)
    // 某个文本分段抽取失败时，保留问题并继续处理其他分段。
    .failOnChunkError(false)
    .build();

// 入图请求：说明这份文档属于哪个知识库，以及本次使用哪些版本和选项。
// company_knowledge 是目标 Space；document-001 是跨版本保持稳定的文档 ID。
GraphIngestionRequest request = GraphIngestionRequest.builder(
        "company_knowledge", "document-001")
    // 文档更新后使用新的业务版本，但保持文档 ID 不变。
    .documentVersion("v1")
    // 标识本次使用的 Schema 版本，便于后续判断是否需要重新抽取。
    .schemaVersion("company-schema-v1")
    // 使用上面定义的自动校验规则。
    .extractionOptions(options)
    // 抽取结果含 ERROR 时仍返回计划，交由下文业务规则决定是否拒绝。
    .failOnExtractionError(false)
    // 实际图写入的目标 Space 必须与请求中的 Space 一致。
    .graphOptions(GraphOptions.ofSpace("company_knowledge"))
    .build();
```

这些选项决定哪些候选能通过自动校验。**自动接受、自动拒绝还是转人工，由应用的业务规则决定**，没有一个通用的审核模式开关。模型给出的置信度只是筛选依据，不代表事实正确的概率。

本例关闭“遇到错误立即中断”，让应用能读取结果中的问题，再按下文规则拒绝带 ERROR 的计划。若希望出错直接抛异常，可以保留这两个选项默认的 `true`。

### 创建知识入图和审核服务

`GraphIngestionService` 是 SDK 的**知识入图服务**：它调用抽取流程生成入图计划，并在确认后执行图写入、保存文档状态。下文使用的 `ingestionService` 就是这个类的实例，不是图数据库连接，也不是需要开发者自行实现的业务类。

`GraphReviewService` 是**审核任务服务**：它负责保存、查询和修改任务；接受任务时，把任务中保存的计划交给知识入图服务执行。

示例需要以下已有对象：

| 变量 | 类型 | 从哪里来、用于什么 |
| --- | --- | --- |
| `pipeline` | `GraphExtractionPipeline` | 按[知识抽取流程](/zh/graph/knowledge-extraction-pipeline)配置，负责调用模型、校验和实体归一 |
| `document` | `Document` | 文档解析得到的文本对象，是本次待抽取内容 |
| `schema` | `GraphSchema` | 应用定义的实体类型、关系类型和属性约束 |
| `request` | `GraphIngestionRequest` | 上节创建的入图请求 |
| `graphStore` | `GraphStore` | 已配置目标数据库连接的 Neo4j、Nebula 等实现，通过 `writer()` 提供图写入接口 |

在这些对象准备好后，创建两个服务：

```java
// 保存已经入图的文档版本和事实来源，用于重复导入判断及后续文档更新。
// 内存实现仅供本地体验，进程退出后数据丢失。
GraphDocumentStateStore documentStateStore = new InMemoryGraphDocumentStateStore();

// 创建知识入图服务：第一个参数负责抽取，第二个参数负责记录入图后的文档状态。
// 创建服务本身不会调用模型，也不会写入图数据库。
GraphIngestionService ingestionService = new GraphIngestionService(
    pipeline, documentStateStore);

// 保存等待人工处理的任务及完整入图计划。
// 全部采用自动接受时，不需要创建审核 Store 或审核服务。
GraphReviewStore reviewStore = new InMemoryGraphReviewStore();

// 创建审核服务，接受任务时使用上面同一个知识入图服务执行计划。
GraphReviewService reviewService = new GraphReviewService(
    reviewStore, ingestionService);
```

`GraphReviewStore` 保存“等待确认的任务”，文档状态存储记录“已经入图的文档版本”，两者用途不同。内存实现的数据会在进程退出后丢失。

生产环境需要自行实现持久化的 `GraphReviewStore`，保存完整任务和计划，并支持带 `reviewVersion` 条件的原子更新。文档状态等长期存储的配置见[知识入图](/zh/graph/knowledge-extraction-ingestion)。

### 按规则选择处理方式

先生成计划，再决定是否执行。下面以“有 ERROR 就拒绝，涉及任职关系或旧关系失效就转人工，其余自动接受”为例：

这里使用的是上一节创建的 `GraphIngestionService ingestionService`。它的 `plan(document, schema, request)` 方法生成“确认后要写入哪些节点和关系”的 `GraphIngestionPlan`，这个步骤可能调用模型，但不写图；`execute(plan, writer)` 才执行计划中的变更，并保存入图状态，不重新调用模型。

```java
// 1. 使用文档、Schema 和入图请求生成计划，暂不写入数据库。
GraphIngestionPlan plan = ingestionService.plan(document, schema, request);

if (plan.getType() == GraphIngestionPlan.Type.NO_OP) {
    // 2. 内容和相关版本未变，无需审核；执行 NO_OP 计划也不会再次写图。
    // graphStore.writer() 返回 GraphWriter，即实际写入节点和关系的接口。
    ingestionService.execute(plan, graphStore.writer());
} else if (plan.getExtractionResult().hasErrors()) {
    // 3. 自动拒绝：抽取或校验出现 ERROR，本分支不执行计划、不创建人工任务。
    // 应用可将 plan.getExtractionResult().getIssues() 保存到自己的业务日志。
} else {
    // 4. 示例业务规则：文档有旧关系失效，或候选包含任职关系时，需要人工确认。
    // staleEdgeKeys 表示当前文档不再支持的旧关系，不代表这些关系必然被删除。
    // validatedRelations 是通过自动校验的关系候选，尚未经过人工确认。
    boolean needsHumanReview = !plan.getStaleEdgeKeys().isEmpty()
        || plan.getExtractionResult().getValidatedRelations().stream()
            .anyMatch(relation -> "WORKS_FOR".equals(relation.getType()));

    if (needsHumanReview) {
        // 5. 保存待审核任务；此时不会执行图写入。
        GraphReviewTask task = reviewService.submit(plan);
        // 将 task.getTaskId() 和 task.getReviewVersion() 返回给业务后台。
        // 用户确认后，再调用后文的 reviewService.accept(...)。
    } else {
        // 6. 自动接受：使用图数据库的写入接口执行当前计划，并提交文档状态。
        GraphIngestionResult result = ingestionService.execute(
            plan, graphStore.writer());
        // isSuccess() 表示图写入和入图状态提交均已完成。
        if (!result.isSuccess()) {
            throw new IllegalStateException("知识入图未完成");
        }
    }
}
```

这里的“任职关系需要人工审核”只是示例业务规则。自动接受执行的是通过校验的候选子集；被校验排除的候选不会因此入图。是否还要对 WARNING 或候选被排除的情况转人工，由应用决定。

如果全部采用自动接受，可以直接调用 `ingestionService.ingest(document, schema, request, graphStore.writer())`，它组合了生成计划和执行两个步骤，不需要配置 `GraphReviewStore`。此时建议保留请求默认的 `failOnExtractionError(true)`，防止错误结果直接入图。

## 审核任务的增删改查

以下代码按操作分别展示。任务由 SDK 生成 `taskId`；后续修改、接受、拒绝和归档，都需要传入任务当前的 `reviewVersion`，避免覆盖其他审核者的操作。

### 创建任务

```java
// 使用上文的知识入图服务生成计划，暂不写图。
// 本段演示创建任务；是否允许带问题的计划进入人工审核，需由业务规则先判断。
GraphIngestionPlan plan = ingestionService.plan(document, schema, request);

// NO_OP 计划没有需要审核的变更，不能提交为审核任务。
if (plan.getType() != GraphIngestionPlan.Type.NO_OP) {
    // SDK 生成任务 ID，并把完整计划保存到审核 Store。
    GraphReviewTask task = reviewService.submit(plan);
    // 后续查询、修改、接受和拒绝都使用这个任务 ID。
    String taskId = task.getTaskId();
    // 后续操作还需提交当前版本；任务每次更新后，使用返回的新版本。
    long reviewVersion = task.getReviewVersion();
    // 将任务 ID 和版本返回给后台。
}
```

`plan(...)` 只生成待执行变更，`submit(...)` 只保存审核任务，两个步骤都不会写入图数据库。

### 查询列表和详情

```java
// 查询某个知识库中等待审核的任务，第一页最多 20 条。
List<GraphReviewTask> tasks = reviewService.findTasks(
    GraphReviewTaskQuery.builder()
        // 限定目标知识库，避免混合展示其他 Space 的任务。
        .space("company_knowledge")
        // 只查询等待审核的任务；退回修改、已完成等状态不会进入此列表。
        .status(GraphReviewTaskStatus.PENDING_REVIEW)
        // 跳过 0 条记录；下一页可使用 offset(20)。
        .offset(0)
        // 最多返回 20 条。
        .limit(20)
        .build());

// 根据创建任务时返回的 taskId 查询详情。
GraphReviewTask task = reviewService.findTask(taskId);
if (task == null) {
    throw new IllegalArgumentException("审核任务不存在");
}

// 从保存的计划读取抽取结果，无需再次调用模型。
GraphExtractionResult extraction = task.getPlan().getExtractionResult();
// extraction.getAllEntities() / getAllRelations()：全部原始候选。
// extraction.getValidatedEntities() / getValidatedRelations()：通过校验的候选。
// extraction.getIssues()：抽取和校验问题。
// task.getPlan().getMutation()：确认后实际执行的图变更。
```

详情页可以展示候选、原文证据、问题和变更预览。`GraphReviewTaskQuery` 还支持 `documentId(...)` 过滤；要同时查询退回修改的任务，可再添加 `.status(GraphReviewTaskStatus.CHANGES_REQUESTED)`。

### 修改候选

例如，审核者将某个人物的 `name` 属性改为“张三”，可以通过 Patch 修改计划：

```java
// task 是当前详情页读取的任务；示例修改第一个通过校验的实体。
List<GraphEntityCandidate> entities = task.getPlan().getExtractionResult()
    .getValidatedEntities();
if (entities.isEmpty()) {
    throw new IllegalStateException("当前任务没有可修改的实体候选");
}
GraphEntityCandidate entity = entities.get(0);

// 先保留原属性，再修改 name，因为 replaceEntityProperties 替换完整属性集合。
Map<String, Object> properties = new LinkedHashMap<>(entity.getProperties());
properties.put("name", "张三");

// Patch 表达修改意图；使用 SDK 返回的 candidateKey 定位实体，不自行拼接键。
GraphReviewPatch patch = GraphReviewPatch.builder()
    .replaceEntityProperties(entity.getCandidateKey(), properties)
    .build();

// 应用修改、重新校验并重建计划，不写图。
GraphReviewTask updated = reviewService.applyPatch(
    task.getTaskId(),       // 要修改的审核任务。
    task.getReviewVersion(), // 当前详情页读取的版本，用于检测并发修改。
    patch,                 // 本次修改内容。
    "修正人物姓名",         // 修改理由，保存在任务记录中。
    "reviewer-001");       // 操作人标识，应来自应用当前登录用户。

// 将 updated 返回给后台重新预览；后续接受时使用 updated.getReviewVersion()。
```

示例要求该实体类型在 Schema 中声明了 `name` 属性。SDK 会重新校验属性、重建入图计划并递增审核版本，不调用模型、不写图。后台应展示 `updated` 中的新预览，确认后再接受。

其他常见修改也通过 `GraphReviewPatch.builder()` 表达：

| 修改意图 | 方法 |
| --- | --- |
| 拒绝某个实体及其关联关系 | `rejectEntity(candidateKey)` |
| 拒绝某条关系 | `rejectRelation(index)` |
| 替换关系的完整属性集合 | `replaceRelationProperties(index, properties)` |
| 将候选匹配到已确认的节点 | `resolveEntity(candidateKey, node)` |

实体键必须来自当前候选；关系下标对应当前 `getValidatedRelations()` 列表，并与审核版本一起提交。Patch 操作的是通过校验的候选；类型、证据或解析错误需要修复后重新抽取，再用 `updatePlan(...)` 替换尚未执行的任务计划。

### 接受或拒绝

后台“接受并入图”按钮调用：

```java
// taskId 和 expectedReviewVersion 来自用户当前查看并确认的任务快照。
// 接受时执行任务中保存的计划，不重新抽取文档。
GraphReviewExecutionResult result = reviewService.accept(
    taskId,                 // 要接受的任务 ID。
    expectedReviewVersion,  // 用户确认的版本，避免执行他人修改后的计划。
    graphStore.writer(),    // 目标图数据库的 GraphWriter，实际执行节点和关系变更。
    "reviewer-001");        // 当前审核人标识。

// 返回结果包含更新后的任务及入图执行结果。
if (!result.isSuccess()) {
    // 执行未完成，记录结果并进入故障处理流程。
}
```

`expectedReviewVersion` 是用户查看并确认的任务版本。`accept(...)` 会执行任务中保存的计划，并更新任务状态；应检查返回结果，不能仅凭调用未抛异常就认为成功。

后台“拒绝”按钮调用：

```java
// 拒绝整个任务，保留原计划和理由，不调用图数据库写入接口。
GraphReviewTask rejected = reviewService.reject(
    taskId,                 // 要拒绝的任务 ID。
    expectedReviewVersion,  // 用户查看任务时的版本。
    "原文证据不足",         // 拒绝理由。
    "reviewer-001");        // 当前审核人标识。
```

拒绝只更新任务状态和理由，不写图。若希望保留任务、要求进一步修改，可调用 `requestChanges(taskId, expectedReviewVersion, reason, actor)`。

### 删除：归档任务

SDK 不提供物理删除审核任务的方法。“从待处理列表移除”可以通过拒绝后归档实现，保留任务快照和处理理由：

```java
// 每次状态变化都会递增版本，归档时使用拒绝操作返回的新版本。
// 此处 rejected 是上一个示例中 reject(...) 返回的任务。
GraphReviewTask archived = reviewService.archive(
    rejected.getTaskId(),        // 已拒绝任务的 ID。
    rejected.getReviewVersion(), // 拒绝后的最新版本，不能复用拒绝前的旧版本。
    "reviewer-001");            // 执行归档的操作人。
```

只有已完成或已拒绝的任务可以归档。归档不删除已经入图的节点或关系，也不是撤回文档。

## 常见场景问题

### 只想自动入图，是否需要审核 Store？

不需要。采用自动接受时直接使用入图服务；只有需要保存人工审核任务时才配置 `GraphReviewStore`。文档版本和事实来源仍由入图服务维护。

### 两个人同时审核，或者用户重复点击怎么办？

操作必须携带页面读取的 `reviewVersion`。旧版本提交会产生版本冲突，后台应刷新任务，让用户重新确认。对已完成任务提交当前版本会返回成功结果，不重复写图；执行中的任务不能再次接受或修改。

### 只想拒绝一条关系，保留其他知识怎么办？

用 `GraphReviewPatch.rejectRelation(index)` 和 `applyPatch(...)` 修改任务，再预览并接受新计划。不要调用 `reject(...)`，后者拒绝的是整个任务。拒绝某条候选也不等于无条件删除图中已有的共享关系。

### 接受时失败，是否重新抽取后再次提交？

应先确认原执行是否中断，并读取任务最新状态。对已中断的 `EXECUTING` 或 `FAILED` 任务，使用 `reviewService.resume(taskId, reviewVersion, writer, actor)` 恢复原计划。恢复要求入图服务配置支持恢复的操作存储，具体配置见[故障恢复](/zh/graph/knowledge-extraction-recovery)。

### 任务等待审核期间，文档已经更新怎么办？

旧计划执行时会检查文档状态版本，过期计划不能覆盖已提交的新状态。应基于最新文档重新生成计划；若原任务尚未执行，可通过 `updatePlan(...)` 替换，并让审核者重新确认。
