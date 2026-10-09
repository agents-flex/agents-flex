# 数据模型

## 概述

假设应用把下面这句话交给大模型：

```text
张三自 2020 年起在星河科技工作。
```

模型可能会返回“张三”“星河科技”和“任职”关系，但这段返回内容还不是可以直接写入图数据库的数据。应用还需要知道：它们分别是什么类型，关系连接哪两个对象，证据来自原文的哪里，是否符合当前 Schema，以及“张三”是否已经是知识库中的已有实体。

**数据模型**就是 Graph Extractor 在这些处理阶段之间传递信息的统一结构。它把自然语言中的一次提及、一个候选事实、一个长期实体、一条来源声明和一次写入操作区分开来。

本章介绍 `agents-flex-graph-extractor` 在各阶段之间传递的数据模型。它不是查询语言，也不是网络通信协议，而是模型输出、SDK 校验、审核系统和图变更之间共同遵守的数据约定。

## 这个数据模型解决什么问题

如果没有统一的数据模型，应用会遇到：

- 不同模型返回不同 JSON，Parser 无法稳定处理；
- 抽取出的关系无法回到原文，审核者不知道为什么得到它；
- 不同 Chunk 中的“林默”无法判断是否是同一个人；
- 合法候选和错误候选混在一起，无法安全入图；
- 文档更新时无法识别事实来源和过期关系；
- 一次导入操作的 ID 被误当作节点或边 ID。

数据模型的目标，是让每个候选都能回答四个问题：

```text
它是什么？       -> 类型、属性和关系
来自哪里？       -> documentId、chunkId 和 evidence
指向谁？         -> mentionId、nodeId 和实体归一结果
能否入图？       -> issue、审核结果和 GraphMutation
```

## 先按处理阶段理解数据

不熟悉 SDK 时，可以先把数据模型分成四层：

| 层次 | 代表对象 | 用简单的话说 |
| --- | --- | --- |
| 输入层 | `GraphExtractionRequest` | 本次要处理哪段文本，以及允许使用什么 Schema |
| 候选层 | `GraphCandidateResult` | 模型从这段文本中发现了什么 |
| 结果层 | `GraphExtractionResult` | 哪些候选通过了校验，并准备如何映射 |
| 执行层 | `GraphMutation` | 如果审核通过，图数据库需要做哪些变化 |

实体归一、事实来源和增量操作会在结果之后继续补充长期身份和生命周期信息。它们不能被一个临时的模型候选对象替代。

## 从模型输出到图变更

```text
GraphExtractionRequest
  -> 模型响应
  -> GraphCandidateResult
  -> GraphCandidateValidator
  -> GraphExtractionResult
  -> GraphEntityResolver / Registry
  -> GraphMutation
```

这条链路中，每个对象都有明确职责：请求描述抽取范围，候选表示模型发现的知识，校验器筛选合法子集，Resolver 确定长期实体身份，Mutation 表示准备写入图数据库的变化。

## 一个完整的候选示例

对于“张三自 2020 年起在星河科技工作”，模型适配器可以返回这样的结构化候选：

~~~json
{
  "entities": [
    {
      "mentionId": "m1",
      "name": "张三",
      "type": "Person",
      "properties": {"name": "张三"},
      "evidence": "张三自 2020 年起在星河科技工作。",
      "confidence": 0.98
    },
    {
      "mentionId": "m2",
      "name": "星河科技",
      "type": "Company",
      "properties": {"name": "星河科技"},
      "evidence": "张三自 2020 年起在星河科技工作。",
      "confidence": 0.97
    }
  ],
  "relations": [
    {
      "sourceMentionId": "m1",
      "type": "WORKS_FOR",
      "targetMentionId": "m2",
      "properties": {"since": 2020},
      "evidence": "张三自 2020 年起在星河科技工作。",
      "confidence": 0.95,
      "assertionType": "EXPLICIT"
    }
  ]
}
~~~

这个 JSON 仍然只是一个 Chunk 的候选响应。Parser 会把它转换为 SDK 对象，Validator 会检查类型、属性、端点和证据，Resolver 再决定 `m1` 是否对应已有的长期 `nodeId`。

## 抽取请求：告诉模型和 SDK 要处理什么

`GraphExtractionRequest` 描述一个 Chunk 的抽取范围：

| 字段 | 作用 |
| --- | --- |
| `text` | 当前 Chunk 的正文，也是 evidence 允许引用的唯一文本范围 |
| `schema` | 允许的节点、关系、属性和类型 |
| `documentId` | 跨版本稳定的逻辑文档 ID |
| `chunkId` | 当前分段 ID，参与证据定位和候选作用域 |
| `previousContext` | 仅用于指代消解的前文，不能作为当前证据 |
| `metadata` | 页码、章节和来源系统等只读来源信息 |
| `options` | 置信度、数量上限、断言类型和响应大小策略 |

例如，前一个 Chunk 提到“林默”，当前 Chunk 只写“他加入了青云宗”。`previousContext` 可以帮助模型理解“他”是谁，但证据仍必须来自当前 Chunk，不能引用前文中没有出现在当前正文的句子。

## 候选结果：模型认为文本中有什么

### `GraphCandidateResult`

一个 Chunk 的解析结果，包括：

- `entities`：实体候选；
- `relations`：关系候选；
- `issues`：解析阶段发现的问题；
- `rawResponse`：模型原始响应，可能包含敏感原文。

这里的 Result 表示一次候选处理的整体结果，实体和关系分别保存在集合中。解析器返回的候选还需要经过
Schema 和质量校验；校验器也使用这个类型保存通过校验的候选子集。后续流程将多个 Chunk 的结果汇总，
完成实体归一并生成 Mutation，最终返回 `GraphExtractionResult`。

### `GraphEntityCandidate`

表示文本中的一次实体提及，通常包含：

- `mentionId`；
- 名称、类型、别名和属性；
- `evidence`；
- `confidence`。

同一个真实人物在不同 Chunk 中可以有不同的 `mentionId`。是否归一为同一个图节点，要等 Resolver 和 Registry 处理，不能直接按名称写入。

### `GraphRelationCandidate`

表示两个候选实体之间可能存在的关系。它使用 `sourceMentionId` 和 `targetMentionId` 引用当前候选实体，并携带关系类型、属性、证据、置信度和 `assertionType`。

关系候选不能直接把名称当作长期端点，也不能引用只存在于 `previousContext`、没有出现在当前候选集合中的实体。

## 证据：说明为什么得到这条知识

`GraphEvidence` 让候选可以回到原文，至少包含：

- `documentId`：来自哪个逻辑文档；
- `chunkId`：来自哪个分段；
- `quote`：连续原文引文；
- `startOffset`、`endOffset`：当前 Chunk 内的字符范围；
- metadata：页码、章节等扩展位置。

提供偏移时必须满足：

```text
0 <= startOffset <= endOffset <= text.length()
text.substring(startOffset, endOffset).equals(quote)
```

如果无法可靠定位，应使用 `-1/-1`，不能把 PDF 字节位置或整篇文档位置冒充 Chunk 偏移。证据是审核、质量评估、文档更新和事实撤回的基础。

## `GraphExtractionResult`：一次完整抽取的结果

流水线合并所有 Chunk 后产生 `GraphExtractionResult`，其中最重要的是：

| 内容 | 作用 |
| --- | --- |
| `allEntities/allRelations` | 保留全部解析候选，便于审核和回放 |
| `validatedEntities/validatedRelations` | 通过 Schema、证据和质量校验的候选子集 |
| `issues` | 解析、校验和 Chunk 容错问题 |
| `entityResolution` | 实体归一结果，包含最终节点及候选键到节点 ID 的映射 |
| `mutation` | 待审核的节点和边变更 |
| `rawResponses` | 各 Chunk 的模型原始响应 |

`validated` 表示候选通过了校验，不表示人工审核已经同意。人工修改或拒绝部分候选后，
这两个集合保存重新校验的当前子集；`allEntities/allRelations` 继续保留原始候选。

`entityResolution` 的类型是 `GraphEntityResolutionResult`，由 `GraphEntityResolver.resolve(...)`
返回。它包含归一后的节点和候选键到节点 ID 的映射，供候选变更映射器生成 `GraphMutation`。

结果对象不等于数据库写入结果。即使存在合法 Mutation，也仍然需要经过审核策略和显式写入。

调用方可以这样理解和检查结果：

~~~java
GraphExtractionResult result = pipeline.extract(document, schema);

// allEntities / allRelations：模型和解析器返回的全部候选。
// validatedEntities / validatedRelations：通过校验的子集，不代表审核已接受。
review(result.getAllEntities(), result.getAllRelations());
review(result.getValidatedEntities(), result.getValidatedRelations());
review(result.getIssues(), result.getEntityResolution());

if (!result.hasErrors()) {
    // mutation 仍然只是待执行变化，不代表已经写入数据库。
    GraphMutation mutation = result.getMutation();
}
~~~

审核通过后，应用再把 Mutation 交给 Writer，或者交给 `GraphIngestionService` 生成包含文档版本和来源的入图计划。

## 身份字段不能混用

同一个“林默”在不同阶段会有不同身份。它们解决的问题不同：

| 字段 | 示例 | 含义 | 作用域 |
| --- | --- | --- | --- |
| `mentionId` | `m1` | 当前 Chunk 中的一次文本提及 | 单个 Chunk |
| `candidateKey` | `chunk-01:m1` | 定位候选和问题 | 一次抽取或 Chunk |
| `nodeId` | `character:lin-mo` | Graph Space 中的长期实体 | Space |
| `factId` | `fact:doc-01:001` | 某个来源对事实的声明 | 来源事实 |
| `operationId` | `import-2026-001` | 一次入图操作 | 业务操作 |

必须牢记：

```text
mentionId != candidateKey != nodeId != factId != operationId
```

`GraphEdgeKey` 还表示物化边的身份，通常由 sourceId、关系类型、targetId 和稳定 rank 组成。它也不等同于 factId：多份文档事实可以共同支持同一条物化边。

## 问题：说明为什么候选不能直接使用

`GraphExtractionIssue` 是结构化质量问题，不是 Java 异常。它包含：

- 稳定 `code`；
- `WARNING` 或 `ERROR` 严重级别；
- `candidateKey` 或 Chunk 定位；
- 面向开发者的诊断信息。

例如，未知关系类型、证据不在当前 Chunk、端点类型不匹配和置信度过低，都可以作为 issue 返回。业务程序应依据 code 和 severity 分流，不要依赖英文 message。

模型调用失败、根响应无法解析或流水线无法继续时，才使用 `GraphExtractionException`。错误处理详见[错误处理](/zh/graph/knowledge-extraction-errors)。

## 从数据模型到图变更

```text
候选实体和关系
  -> Schema 与证据校验
  -> 实体归一，得到 nodeId
  -> GraphCandidateMutationMapper
  -> 节点 Upsert、边 Upsert 和稳定 EdgeKey 去重
```

`GraphCandidateMutationMapper` 不负责审核、删除、写数据库或解决来源冲突。旧关系是否删除，由增量计划结合文档状态和事实来源决定。

## 开发者什么时候需要关注这些数据

- 接入 DeepSeek、OpenAI 或其他模型时，适配输出结构；
- 开发候选、证据和关系审核后台时，展示可追溯信息；
- 保存抽取结果、人工修改和审核记录时，保持身份稳定；
- 实现增量导入、文档更新和撤回时，区分事实来源与物化边；
- 做模型评估和问题回放时，关联原文、Chunk、Schema 和配置版本。

## 自定义模型适配要求

自定义 Parser 或模型适配器至少应保证：

1. 缺失 `entities` 或 `relations` 时返回明确问题；
2. 单个坏候选尽量隔离，不丢弃同一响应中的合法候选；
3. 保留请求中的 documentId、chunkId 和 metadata；
4. 不把 `previousContext` 中的文字当作 evidence；
5. 把未知 Schema 类型和非法端点交给 Validator；
6. 保存足够的响应和版本信息，使结果可以回放。

## 下一步阅读

- [审核](/zh/graph/knowledge-extraction-quality)：了解候选如何进入审核、被接受、修改或拒绝；
- [实体归一](/zh/graph/knowledge-extraction-entity-resolution)：了解 mention 如何映射为 nodeId；
- [文档生命周期](/zh/graph/knowledge-extraction-lifecycle)：了解 fact、edge 和来源状态。
