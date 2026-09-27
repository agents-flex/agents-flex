# 候选质量审核

## 概述

大模型输出只能被视为候选知识。即使 JSON 格式正确，也可能存在错误类型、错误端点、原文不存在的事实、过低置信度或把观点当作事实等问题。

Graph Extractor 将“模型返回了什么”和“哪些候选通过了校验”分开保存，让上层能够实现自动门槛、人工审核和离线评估，而不是把一次模型调用直接变成数据库写入。

## 结果的四个层次

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

## 自动审核与人工审核

推荐把审核策略分层：

### 自动拒绝

- 未知类型或属性；
- 证据不属于当前 Chunk；
- 非法端点；
- 协议损坏；
- 明显低于阈值；
- 禁止的断言类型。

### 人工确认

- 同名实体存在多个可能匹配；
- 高影响关系；
- 关系撤销；
- 模型推断或观点；
- 属性冲突；
- 新出现但尚未纳入 Schema 的概念。

### 自动接受

只有经过标注集验证、风险较低且具有完整证据的类型，才适合自动写入。自动接受规则也应版本化和审计。

SDK 不实现审核 UI，但结果对象提供候选、证据、问题、归一映射和 Mutation，足以供开发者自己的后台展示。

## 写入前的门槛

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

质量审核完成后，还需要通过[实体归一](/zh/graph/knowledge-extraction-entity-resolution)解决跨段、跨文件的实体重复问题。
