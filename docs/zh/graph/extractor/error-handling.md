# 错误处理

## 概述

知识抽取和入图会经过模型调用、响应解析、Schema 校验、实体归一、图写入和状态提交。任何一步都可能出问题，但这些问题不能用同一种方式处理。

例如，一份文档被分成两个 Chunk：

```text
Chunk 1：模型调用超时
Chunk 2：成功抽取出两个实体和一条关系
```

应用需要决定：是立即终止整份文档，还是保留第二个 Chunk 的合法结果交给审核？如果图写入已经成功、文档状态提交却失败，又应该继续提交状态，而不是重新调用模型。

**错误处理的核心不是捕获异常，而是先识别错误发生在哪一层，再决定终止、保留部分结果、审核、重试、恢复或对账。**

本模块使用四种不同的错误表达：

```text
GraphExtractionException      抽取流程无法继续
GraphExtractionIssue          可以定位到 Chunk 或候选的结构化质量问题
GraphWriteResult              GraphWriter 的执行结果
GraphIngestionOperation.Stage 跨系统入图操作已经进行到哪个阶段
```

它们不是同一种错误的四种写法，也不能统一转换成一个布尔值。

## 一条处理主线

可以先用下面的顺序理解整个决策过程：

```text
调用抽取流程
  -> 抛异常？
       是：本次没有可直接使用的完整结果，分类后重试或转人工
       否：检查 GraphExtractionIssue
  -> 存在 ERROR？
       是：默认拒绝自动入图；可保存合法子集供审核
       否：继续业务审核
  -> 执行 GraphMutation
  -> GraphWriteResult 失败或抛异常？
       是：不要重新抽取，使用原计划分类重试或恢复
  -> 图成功后状态提交失败？
       是：根据 GraphIngestionOperation.Stage 恢复剩余步骤
```

## 先区分四类错误

### 1. `GraphExtractionException`：流程无法继续

`GraphExtractionException` 通常表示当前抽取调用无法产生可信的完整结果，例如：

- 模型响应为空或超过大小限制；
- 响应中找不到符合协议的 `entities` 和 `relations` 数组；
- Parser 无法解析根协议；
- Resolver 发现无法自动处理的实体歧义；
- 自定义抽取组件返回非法结果。

SDK 的模型调用和协议错误通常使用该异常传播。参数或配置错误可能使用 `IllegalArgumentException`，自定义扩展也可能抛出其他运行时异常，因此生产入口不应假设所有失败都只有一个异常类型。

异常表示“这一层没有正常返回结果”。它不等于“可以重试”，更不等于“外部系统一定没有副作用”。是否重试仍需检查根因和当前阶段。

### 2. `GraphExtractionIssue`：可定位的结构化问题

有些问题只影响一个候选或一个 Chunk，不必让整个结果消失。`GraphExtractionIssue` 保存：

| 字段 | 用途 |
| --- | --- |
| `code` | 供程序分流和统计的稳定问题码 |
| `severity` | `WARNING` 或 `ERROR` |
| `candidateKey` | 定位相关 Chunk、实体或关系 |
| `message` | 面向开发者和审核者的诊断说明 |

常见问题码包括：

| 问题码 | 级别 | 含义 |
| --- | --- | --- |
| `ENTITY_LIMIT` | WARNING | 实体候选超过单 Chunk 上限，结果被截断 |
| `RELATION_LIMIT` | WARNING | 关系候选超过单 Chunk 上限，结果被截断 |
| `MALFORMED_ENTITY` | ERROR | 某个实体候选字段不完整或类型不正确 |
| `MALFORMED_RELATION` | ERROR | 某个关系候选字段不完整或类型不正确 |
| `INVALID_ENTITY` | ERROR | 实体不符合 Schema、证据或质量要求 |
| `INVALID_RELATION` | ERROR | 关系类型、端点、属性、证据或断言不合法 |
| `CHUNK_EXTRACTION_FAILED` | ERROR | 某个 Chunk 的抽取或协议解析失败 |
| `CHUNK_VALIDATION_FAILED` | ERROR | 某个 Chunk 的校验器执行失败 |

程序应根据 `code` 和 `severity` 做分流，不要解析可能变化的英文 `message`。

`GraphExtractionResult.hasErrors()` 只表示至少存在一个 ERROR 级 issue。它不表示结果中没有合法候选，也不表示结果已经可以安全写图。

### 3. `GraphWriteResult`：图写入结果

`GraphWriter` 可以返回 `GraphWriteResult`，其中包含：

- `isSuccess()`：Writer 是否报告成功；
- `nodesAffected` 和 `edgesAffected`：影响数量；
- `errorCode`：稳定的图错误分类；
- `message` 和 `error`：诊断详情和底层异常。

上层应优先使用 `GraphErrorCode` 分类，再把 message 用于日志和排查。常见分类包括 `INVALID_ARGUMENT`、`CONNECTION_FAILED`、`SPACE_NOT_FOUND`、`SCHEMA_VALIDATION_FAILED` 和 `WRITE_FAILED`。

Writer 也可能直接抛出运行时异常。无论是失败结果还是异常，都不能仅凭客户端观察断言后端一定没有提交；连接中断或超时可能发生在数据库已经提交之后。

### 4. `GraphIngestionOperation.Stage`：跨系统执行阶段

图写入成功后，还可能在实体注册或文档状态提交时失败。入图操作通过阶段记录已经确认的进度：

```text
PREPARED -> GRAPH_APPLIED -> STATE_COMMITTED -> COMPLETED
     \-> FAILED -> PREPARED
```

进入 `GRAPH_APPLIED` 后，恢复应跳过 Writer，继续后续步骤。停在 `PREPARED` 或 `FAILED` 时，图也仍有可能已经生效，因为 Writer 的成功响应可能丢失。具体故障窗口见[故障恢复](/zh/graph/extractor/recovery)。

## 最小示例：检查抽取问题

下面让单个 Chunk 失败时继续处理其他 Chunk，然后显式检查问题：

```java
import com.agentsflex.graph.extractor.GraphExtractionOptions;
import com.agentsflex.graph.extractor.GraphExtractionResult;
import com.agentsflex.graph.extractor.model.GraphExtractionIssue;

GraphExtractionOptions options =
    GraphExtractionOptions.builder()
        .failOnChunkError(false)
        .build();

GraphExtractionResult extraction;
try {
    extraction = pipeline.extract(document, schema, options);
} catch (RuntimeException fatal) {
    // 参数错误、根协议错误或归一阶段异常等无法形成可用结果。
    throw fatal;
}

for (GraphExtractionIssue issue : extraction.getIssues()) {
    System.out.printf(
        "%s %s %s%n",
        issue.getSeverity(),
        issue.getCode(),
        issue.getCandidateKey());
}

if (extraction.hasErrors()) {
    // 保存候选、问题和证据，进入审核；不要直接写图。
    submitForReview(extraction);
} else {
    // hasErrors=false 之后仍需执行应用自己的审核策略。
    applyBusinessPolicy(extraction);
}
```

`failOnChunkError(false)` 只把逐 Chunk 的抽取或校验执行失败转换成 issue。分段参数非法、Resolver 失败或 Mutation 映射失败等文档级问题仍会通过异常传播。

## 三层安全开关

部分结果能否继续，需要同时理解三层配置：

| 配置 | 所属对象 | 默认值 | 决定什么 |
| --- | --- | --- | --- |
| `failOnChunkError` | `GraphExtractionOptions` | `true` | 单个 Chunk 抽取或校验失败时，立即抛出还是记录 issue 后继续 |
| `failOnExtractionError` | `GraphIngestionRequest` | `true` | 抽取结果含 ERROR 时，是否拒绝生成可执行入图计划 |
| `allowPartialReconciliation` | `GraphIngestionRequest` | `false` | 部分结果是否允许参与旧关系差异计算和删除 |

这三个开关控制不同阶段。仅设置 `failOnChunkError(false)`，并不意味着带 ERROR 的结果会自动入图。

## 三种处理策略

### 严格模式：有错误就停止

默认配置就是严格模式：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(space, documentId)
        .build();
```

单个 Chunk 执行失败时直接抛出；即使 Parser 或 Validator 返回了局部 ERROR，入图服务也会因为 `failOnExtractionError=true` 拒绝生成可执行计划。适合自动化程度高、错误事实成本高的场景。

### 审核模式：保留合法子集，不自动执行

需要查看剩余合法候选时，可以允许流水线和计划保留部分结果：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(space, documentId)
        .extractionOptions(
            GraphExtractionOptions.builder()
                .failOnChunkError(false)
                .build())
        .failOnExtractionError(false)
        .build();

GraphIngestionPlan plan =
    ingestion.plan(document, schema, request);

if (plan.getExtractionResult().hasErrors()) {
    GraphReviewTask task = reviewService.submit(plan);
    // 等待审核，不调用 ingestion.execute(...)。
}
```

`failOnExtractionError(false)` 的含义是“允许生成计划”，不是“错误可以忽略”。调用方必须检查 issues、合法候选、证据和 Mutation，再决定修改、拒绝或接受。

### 部分提交模式：只接受明确安全的变化

如果业务允许自动写入合法子集，可以执行带 ERROR 的计划。默认情况下，入图服务会保留旧版本的节点、关系和来源，不让不完整结果把旧事实误判为已经删除：

```java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(space, documentId)
        .extractionOptions(
            GraphExtractionOptions.builder()
                .failOnChunkError(false)
                .build())
        .failOnExtractionError(false)
        .staleRelationPolicy(
            GraphIngestionRequest.StaleRelationPolicy.DELETE_IF_UNREFERENCED)
        .build();
```

即使配置了 `DELETE_IF_UNREFERENCED`，存在抽取 ERROR 时，默认也会清空 `staleEdgeKeys`，不删除旧关系。

只有显式设置 `.allowPartialReconciliation(true)` 后，残缺结果才会参与旧关系删除。这会把“本次没有抽取到”解释为“新版本不再包含”，风险很高，通常只适用于能证明缺失范围不影响关系集合的场景。

## 为什么部分结果不能当作完整快照

假设旧版本包含：

```text
(张三)-[WORKS_FOR]->(星河科技)
```

新版本处理时，包含这句话的 Chunk 恰好超时。如果把本次合法子集当成完整结果，系统会认为旧关系已经消失并将其删除。但真实情况只是“这一段没有处理成功”。

因此部分提交通常只能安全地增加明确抽取出的事实，不能据此证明未出现的旧事实已经失效。应用还应记录失败 Chunk，后续补抽并重新形成完整版本。

## 从异常到处理动作

### 输入或配置错误

例如空文档、重复 Chunk ID、非法阈值或 Space 不一致。这类问题通常抛出 `IllegalArgumentException`，重试相同输入没有意义，应修正调用参数或配置。

### 模型超时、限流或连接失败

可能是暂时性错误。可以在模型调用层使用有上限的指数退避，并保留同一文档和配置。超过次数后结束当前抽取任务或转人工，不应无限重试。

### 根协议错误

响应为空、超限，或没有合法的 `entities`/`relations` 根对象时，当前 Chunk 无法解析。严格模式直接失败；宽松模式会把逐 Chunk 异常转换为 `CHUNK_EXTRACTION_FAILED`。

如果大量请求持续出现同一协议错误，应修复 Prompt、模型结构化输出配置或 Parser，而不是不断重试相同响应。

### 单个候选错误

格式错误候选会产生 `MALFORMED_ENTITY` 或 `MALFORMED_RELATION`；Schema、端点、属性、置信度或证据错误会产生 `INVALID_ENTITY` 或 `INVALID_RELATION`。其他合法候选仍然保留，可进入质量统计或人工审核。

### 实体归一歧义

一个候选同时命中多个历史实体时，`RegistryGraphEntityResolver` 会抛出异常，避免随机选择。此类问题需要人工确认身份或修正实体注册，不适合盲目重试。

### Writer 失败

Writer 明确返回失败时，`GraphIngestionResult.isSuccess()` 为 `false`，文档状态不会提交。Writer 抛异常时，异常会向调用方传播。

如果已经创建持久化操作，应使用原 `operationId` 和冻结计划恢复，不重新调用模型。对超时或连接中断等未知提交结果，应先核验图状态并确保 Mutation 可重放。

### 状态提交或阶段推进失败

图写入可能已经完成。此时不要重新开始整条 `ingest(...)` 流程；应读取 `GraphIngestionOperation.Stage`，通过 `resume(...)` 继续实体注册、文档状态或操作日志步骤。

### revision CAS 冲突

说明计划基于的文档版本已经被另一任务推进。旧计划不能继续覆盖最新状态。应停止自动重试，读取最新 revision，检查已经产生的图副作用，并重新规划或补偿。

## 检查写入结果

自动入图时，需要同时处理“返回失败”和“抛出异常”：

```java
GraphIngestionResult result;
try {
    result = ingestion.execute(plan, graphStore.writer());
} catch (RuntimeException failure) {
    // 查询持久化 operation stage，决定 resume、对账或人工处理。
    handleExecutionException(operationId, failure);
    return;
}

if (!result.isSuccess()) {
    GraphErrorCode code =
        result.getWriteResult().getErrorCode();

    routeWriteFailure(
        code,
        result.getWriteResult().getMessage(),
        operationId);
    return;
}
```

当前入图服务在 Writer 返回失败结果时返回 `isSuccess() == false`；Writer 抛异常、文档状态提交失败或操作阶段推进失败时会进入异常分支。异常分支应查询持久化操作阶段，再决定恢复或对账。

不要仅根据 message 文本匹配“timeout”或“connection”决定重试。优先使用 `GraphErrorCode`，并结合后端是否可能已经提交、当前操作阶段和业务幂等能力制定策略。

## 处理矩阵

| 错误或状态 | 是否重新调用模型 | 是否重试 Writer | 是否推进文档状态 | 推荐处置 |
| --- | --- | --- | --- | --- |
| 参数或配置错误 | 否 | 否 | 否 | 修正输入或配置 |
| 模型暂时性失败 | 按上限重试当前抽取 | 否 | 否 | 退避、限次、失败后转人工 |
| 根协议持续不兼容 | 否 | 否 | 否 | 修复 Prompt、模型配置或 Parser |
| 候选 WARNING | 否 | 否 | 由业务决定 | 记录指标并继续审核 |
| 候选 ERROR | 否 | 否 | 默认不推进 | 严格拒绝或审核合法子集 |
| Resolver 歧义 | 否 | 否 | 否 | 人工确定身份并持久化裁决 |
| Writer 明确失败 | 否 | 按错误分类 | 否 | 使用原计划恢复，不重新抽取 |
| Writer 结果未知 | 否 | 先核验再决定 | 否 | 对账后重放幂等 Mutation 或补偿 |
| `GRAPH_APPLIED` 后失败 | 否 | 否 | 继续剩余步骤 | 使用原 operationId 恢复 |
| revision CAS 冲突 | 否 | 不盲目重放 | 否 | 读取最新状态，重新规划或补偿 |
| 状态已提交、收尾失败 | 否 | 否 | 已提交 | 补齐历史和操作阶段 |

## 重试原则

### 只重试可能恢复的错误

限流、短暂连接失败和依赖服务不可用可能适合重试。非法参数、Schema 不兼容、权限拒绝、稳定协议错误和实体歧义通常需要修正或人工处理。

### 保持同一阶段和同一身份

写入与状态恢复应使用原 `operationId` 和原计划。不要在 Writer 失败后重新调用模型，也不要给同一次业务操作生成新的 operationId 来绕过冲突。

### 未知提交结果先核验

超时表示客户端没有得到结果，不表示数据库一定没有提交。重复写入前应确认 Mutation 可重放，并通过查询或对账检查实际状态。

### 设置上限、退避和抖动

模型、Writer、状态存储应分别配置超时、最大尝试次数和退避策略。恢复任务需要死信和告警，不能永久占用队列。

### 记录每次尝试

至少记录错误分类、阶段、尝试次数、时间、下一次重试时间和最终处置，便于区分短暂波动与持续配置错误。

## 自定义扩展点的错误边界

自定义组件应遵守与 SDK 相同的语义：

- Extractor 或 Parser 遇到无法形成协议根对象的问题时抛出明确异常；
- Parser 把能定位到单个候选的错误转换为 issue，继续处理其他候选；
- Validator 返回合法子集和问题，不静默丢弃候选；
- Resolver 遇到无法自动消歧的身份冲突时明确失败，不随机选择；
- MutationMapper 不吞掉缺少端点或身份映射的问题；
- Writer 使用稳定 `GraphErrorCode` 返回失败，或抛出保留 cause 的异常；
- Store 的 CAS 冲突与基础设施异常应可区分。

不要捕获所有异常后返回空候选或成功结果。空结果可能被入图服务理解为文档不再包含旧关系，从而产生错误删除。

## 日志、指标与隐私

建议记录：

- Space、documentId、chunkId 和 candidateKey；
- operationId、planFingerprint 和执行阶段；
- issue code、severity 和稳定写入错误分类；
- 模型、Schema、Prompt 和抽取配置版本；
- 重试次数、耗时、最终处置和恢复结果。

建议统计：

- 每种 issue code 的数量和文档占比；
- Chunk 失败率、候选淘汰率和人工接受率；
- Writer 错误分类和未知提交次数；
- 待恢复操作数量、最老年龄和恢复成功率；
- revision 冲突和人工补偿次数。

日志中不要直接记录完整原文、模型原始响应、访问凭证和敏感实体属性。原始响应若需要用于回放，应进入有访问控制、加密和保留周期的专用存储。

## 常见问题

### `hasErrors()` 为 `false` 就一定可以写图吗？

不一定。它只说明没有 ERROR 级 issue。应用仍需检查业务审核规则、证据质量、Mutation、实体身份、Schema 版本和文档 revision。

### `WARNING` 可以全部忽略吗？

不建议。例如 `ENTITY_LIMIT` 和 `RELATION_LIMIT` 表示候选已经被截断，可能影响召回和版本差异。是否接受应由业务规则决定，并纳入质量指标。

### `failOnChunkError(false)` 会捕获所有异常吗？

不会。它只处理逐 Chunk 的 Extractor 和 Validator 执行失败。输入校验、分段 ID、实体归一和 Mutation 映射等文档级失败仍会向调用方传播。

### Writer 失败后可以重新调用 `ingest(...)` 吗？

只有在确认尚未创建持久化操作，或明确决定废弃旧计划并重新规划时才这样做。正常生产恢复应优先使用原 operationId 和 `resume(...)`。

### 状态提交失败时可以直接重写图吗？

不能盲目重写。先读取操作阶段和图状态；已经进入 `GRAPH_APPLIED` 时应跳过 Writer，继续提交状态。

### 可以根据异常 message 决定是否重试吗？

不应把 message 当作稳定协议。优先使用 issue code、`GraphErrorCode`、异常类型和操作阶段；message 仅用于诊断。

## 生产检查清单

- 是否区分异常、结构化 issue、Writer 结果和入图操作阶段？
- 业务逻辑是否依赖稳定 code，而不是解析 message？
- `failOnChunkError`、`failOnExtractionError` 和 `allowPartialReconciliation` 是否按场景明确配置？
- 带 ERROR 的结果是否默认阻止自动入图？
- 部分结果是否避免撤销本次未成功处理的旧关系？
- Writer 失败后是否使用原计划，而不是重新调用模型？
- 未知提交结果是否先对账，并保证 Mutation 可重放？
- revision 冲突是否停止自动重试并重新规划？
- 重试是否有分类、超时、退避、上限、死信和告警？
- 日志和回放存储是否避免泄露原文、响应和敏感实体？
- 是否持续统计 issue、写入失败、恢复和人工处理质量？

具体崩溃窗口和 `resume(...)` 行为见[故障恢复](/zh/graph/extractor/recovery)；候选审核流程见[审核](/zh/graph/extractor/review)。
