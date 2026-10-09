# 知识入图

## 概述

单次抽取只回答“这份文本中可能有哪些实体和关系”。真正的知识库需要在同一个 Space 中持续接收新文件、文档新版本和人工修订，并避免每次都生成一套互不相关的节点。

`GraphIngestionService` 在知识抽取流程之外增加：

- 内容摘要和重复提交判断；
- 稳定文档 ID 与业务版本；
- Schema 和抽取配置版本；
- 跨批次实体注册；
- 新旧关系差异；
- 文档当前状态与历史版本；
- 图写入、状态提交和恢复计划。

它是文档知识入图编排器，不是通用文件导入器。需要人工确认时，可将计划交给 `GraphReviewService` 创建审核任务；
自动接受时不需要审核任务。

## 首次入图的准备

一个新知识库通常经历：

~~~text
创建 Space
  -> 应用并验证 Graph Schema
  -> 准备持久化文档状态、实体注册和操作存储
  -> 解析与分段历史文档
  -> 生成并审核入图计划
  -> 写入节点和关系
  -> 保存状态与来源
  -> 执行数据质量校验
~~~

首次导入也不应省略稳定身份和来源。否则第二批文件到来时，无法判断它们提到的是已有实体还是新实体。

## 核心持久化组件

### GraphDocumentStateStore

保存每个 `Space + documentId` 的当前版本、内容摘要、Schema 版本、抽取配置、节点、边和事实来源，并通过 revision CAS 防止并发覆盖。

### GraphEntityRegistry

保存跨文档可复用的实体身份。它应同时装配给 Pipeline 的 `RegistryGraphEntityResolver`，以及入图服务。

### GraphIngestionOperationStore

保存一次执行计划和跨系统操作阶段，使进程崩溃后可以继续，而不必重新调用可能产生不同结果的大模型。

### GraphIngestionLockProvider

限制同一 Space 和 documentId 的并发执行。默认本地实现只保护单 JVM，多实例必须使用共享锁或可靠任务分区。

SDK 的 InMemory 实现只适合测试，进程退出后数据丢失。

## 创建长期入图服务

~~~java
GraphDocumentStateStore documentStates =
    new YourPersistentDocumentStateStore();
GraphEntityRegistry entityRegistry =
    new YourPersistentEntityRegistry();
GraphIngestionOperationStore operations =
    new YourPersistentOperationStore();
GraphIngestionLockProvider locks =
    new YourDistributedDocumentLockProvider();

GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(
        extractor,
        splitter,
        validator,
        new RegistryGraphEntityResolver(
            "novel_knowledge",
            entityRegistry),
        new GraphCandidateMutationMapper());

GraphIngestionService ingestion =
    new GraphIngestionService(
        pipeline,
        documentStates,
        entityRegistry,
        operations,
        locks);
~~~

这里的 Space 必须保持一致：Registry Resolver、请求、GraphOptions 和状态存储都应指向同一知识库。

## 构造入图请求

~~~java
GraphIngestionRequest request =
    GraphIngestionRequest.builder(
            "novel_knowledge",
            "book-001/chapter-008")
        .documentVersion("2026-09-25")
        .schemaVersion("novel-schema-v3")
        .batchId("import-2026-09-001")
        .sourceUpdatedAtMillis(sourceUpdatedAt)
        .graphOptions(
            GraphOptions.ofSpace("novel_knowledge"))
        .build();
~~~

重要字段：

| 字段 | 作用 |
| --- | --- |
| `space` | 目标知识库和状态隔离边界 |
| `documentId` | 跨版本稳定的逻辑文档身份 |
| `contentHash` | 可选可信摘要；未提供时 SDK 计算 SHA-256 |
| `documentVersion` | 业务版本；未提供时使用内容摘要 |
| `schemaVersion` | 本次抽取采用的 Schema 版本 |
| `batchId` | 上层导入批次关联标识 |
| `operationId` | 稳定操作号；未提供时由服务生成 |
| `sourceUpdatedAtMillis` | 来源更新时间，用于拒绝乱序旧版本 |
| `extractionFingerprint` | 模型、Prompt 等外部抽取配置指纹 |
| `graphOptions` | 必须显式指向同一个 Space |

若应用提供 `contentHash`，必须保证它真实代表当前内容；错误摘要会导致错误跳过。

## plan 与 execute 分离

高风险或需要人工审核的系统应先生成计划：

~~~java
GraphIngestionPlan plan =
    ingestion.plan(document, schema, request);

if (plan.getStatus() != GraphIngestionPlan.Status.UNCHANGED) {
    // 只在需要人工确认时配置 reviewService，不立即写图。
    GraphReviewTask task = reviewService.submit(plan);
    // 向自己的后台返回 task.getTaskId()。
}
~~~

后台查询和人工确认统一调用 `reviewService.get/list/accept/reject`；修改候选调用 `applyPatch`。
配置和完整示例参见[审核](/zh/graph/knowledge-extraction-quality)。自动接受仍直接调用 `ingest` 或 `execute`。

计划包含：

- 上一个已提交文档状态；
- 准备提交的新状态；
- 抽取结果；
- 旧版本不再出现的关系；
- 最终 GraphMutation；
- 目标 Space 和 GraphOptions；
- 是否需要写图。

`ingest(...)` 是 plan 与 execute 的便捷组合，适合已经建立自动接受策略的场景。

## 重复提交与 UNCHANGED

服务比较：

~~~text
Space + documentId + contentHash + documentVersion
+ schemaVersion + extractionFingerprint
~~~

内容和相关版本都未变化时返回 `UNCHANGED` 语义，不再次调用模型或写图。

以下情况会重新抽取：

- 文档内容摘要变化；
- 业务文档版本变化；
- Schema 版本变化；
- 抽取配置指纹变化；
- 显式启用 `forceReextract(true)`。

模型名称、Prompt 模板、温度、解析协议或业务词典发生变化时，应更新 extractionFingerprint。只依赖基础 Options 指纹无法覆盖所有自定义组件。

## 新文件与文档新版本

二者必须区分：

- 新逻辑文档使用新的 documentId，作为新的事实来源；
- 原文档修订继续使用相同 documentId，增加 documentVersion 和来源更新时间。

如果每次上传都生成新 documentId，服务会把它们视为不同来源，无法自动计算旧版本失效关系。反过来，把两份独立文件误用同一 documentId，又会让后者覆盖前者状态。

## 乱序版本保护

分布式消息、批处理和人工重试可能让旧版本晚于新版本到达。`sourceUpdatedAtMillis` 用于拒绝来源时间倒退的请求。

它需要来自可信业务源，而不是消费任务的当前时间。多个来源时间不可比较时，应使用业务 revision 或让上层串行化。

## 写入后的状态提交

执行计划的主要顺序是：

~~~text
确认或创建操作记录
  -> GraphWriter 写入 Mutation
  -> 保存实体注册
  -> CAS 提交文档状态
  -> 保存版本历史
  -> 完成操作状态
~~~

图数据库和状态存储通常不能组成一个事务，因此任何阶段都可能中断。生产环境必须配置操作存储并按原计划恢复，详见[故障恢复](/zh/graph/knowledge-extraction-recovery)。

## 与普通批量导入的区别

普通 `GraphWriter.importData` 接收已经准备好的节点和边，按批写入。知识入图服务额外理解：

- 哪一份文档产生了哪些事实；
- 当前内容是否已经处理；
- 新旧版本有何差异；
- 哪条关系是否仍被其他文档支持；
- 图写入后如何提交外部状态。

大规模历史文件可以由上层任务系统逐个调用入图服务；不要因为文件很多就绕过文档状态和来源管理，除非图谱确实不需要更新和撤回。

## 常见问题

### 后续几年导入新文件需要新 Space 吗？

通常不需要。同一知识库继续使用原 Space，依靠稳定文档 ID、实体注册和事实来源持续维护。

### contentHash 相同就一定不用重抽吗？

不一定。Schema、业务版本或抽取配置变化也会触发重抽；也可以显式强制重抽。

### 可以只使用 DocumentStateStore，不使用 OperationStore 吗？

可以完成基本入图，但图写入成功、状态提交前崩溃时缺少可靠阶段记录和原计划。生产恢复建议同时实现 OperationStore。

### plan 生成后可以长期保存再执行吗？

可以持久化，但执行前必须确认其 expected revision 未被其他版本推进，目标 Schema 和路由仍兼容，并使用原计划指纹。

## 生产检查清单

- documentId 是否稳定区分“新文件”和“同一文件新版本”；
- 请求 Space 与 GraphOptions 是否完全一致；
- Schema 与抽取配置是否有明确版本；
- Entity Registry 是否真正参与 Pipeline 解析；
- 状态、注册和操作存储是否为持久化实现；
- 是否先 plan、审核，再执行高风险变更；
- contentHash 是否可信且可重建；
- 是否使用来源时间或业务 revision 防止旧版本倒灌；
- 图写入后是否提交版本历史和事实来源；
- 首次入图和后续文档更新是否执行相同的数据质量规则。

新旧版本差异和撤回策略见[文档生命周期](/zh/graph/knowledge-extraction-lifecycle)。
