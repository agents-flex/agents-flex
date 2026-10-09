# 知识抽取流程

## 概述

假设应用收到一本小说、一份合同或一批产品文档。文件解析之后，内容仍然只是自然语言；图数据库并不知道其中有哪些人物、公司、产品和关系。

最直接的想法是把整篇文档一次交给大模型，让模型返回节点和边。但在真实场景中，这种做法通常会遇到：

- 文档太长，超出模型上下文或响应限制；
- 模型遗漏长文档前面的信息；
- 一次失败会导致整篇文档重新处理；
- 返回的证据无法精确定位到原文；
- 同一实体在不同段落中被重复识别；
- 抽取结果还没有经过 Schema 和质量校验。

**知识抽取流程**就是把一份文档按可管理的文本单元分段，逐段发现候选实体和关系，再统一完成校验、实体归一和图变更映射的处理过程。

它解决的是“文本如何被转换成可审核的图知识”，而不是“如何把知识写入图数据库”。流程最终产生 `GraphExtractionResult`，不会自动修改 Neo4j、Nebula 或其他图数据库。计划、审核、写入、更新和恢复属于[知识入图生命周期](/zh/graph/extractor/ingestion-lifecycle)。

`GraphExtractionPipeline` 把完整文档拆成可管理的 Chunk，对每个 Chunk 执行候选抽取和校验，再统一完成实体归一与 Mutation 映射。

## 一个具体例子

对于下面的会议纪要：

```text
张三自 2020 年起在星河科技工作。李四负责支付系统升级项目。
```

知识抽取流程需要把文本转换成候选知识：

```text
完整文档
  -> 分成若干 Chunk
  -> 每个 Chunk 调用模型或其他抽取器
  -> 解析候选实体、关系和证据
  -> 按 Graph Schema 校验候选
  -> 合并各 Chunk 的合法结果
  -> 归一跨 Chunk 的同一实体
  -> 生成待审核 GraphMutation
```

结果可能表达为：

```text
(Person:张三)-[WORKS_FOR {since: 2020}]->(Company:星河科技)
(Person:李四)-[RESPONSIBLE_FOR]->(Project:支付系统升级)
```

这里的节点和边仍然只是“候选对应的待写入变化”，不是已经写入图数据库的事实。

~~~text
Document
  -> DocumentSplitter
  -> 每个 Chunk 调用 GraphExtractor
  -> GraphCandidateParser
  -> GraphCandidateValidator
  -> 合并所有合法候选
  -> GraphEntityResolver
  -> GraphCandidateMutationMapper
  -> GraphExtractionResult
~~~

## 流程中的基本概念

### Document

`Document` 是文档解析模块输出的内容对象，可以来自纯文本、PDF、Word、网页或业务系统。它可以携带 ID、标题和 metadata。

知识抽取流程不负责文件上传和格式解析。调用方应先把文件解析为 `Document`，再交给 `GraphExtractionPipeline`。

### Chunk

Chunk 是从完整文档中切出的一个连续文本片段。每个 Chunk 都有自己的 `chunkId`，用于定位候选和证据。

Chunk 不等于一个业务实体，也不等于一个最终图节点。它只是控制模型调用、错误隔离和证据范围的处理单元。

### GraphExtractionResult

流程完成后，`GraphExtractionResult` 保存：

- 模型解析出的全部候选；
- 通过校验的合法候选；
- 证据、置信度和结构化问题；
- 跨 Chunk 的实体归一结果；
- 待审核的 `GraphMutation`；
- 各 Chunk 的原始响应。

结果可以被审核、评估、保存或交给知识入图服务，但不会自动写库。

## 每一步解决什么问题

| 步骤 | 主要工作 | 解决的问题 |
| --- | --- | --- |
| 分段 | 把长文档切成可处理单元 | 控制上下文、成本和失败范围 |
| 抽取 | 调用模型、规则引擎或 NLP 服务 | 从文本发现候选知识 |
| 解析 | 转换为统一候选对象 | 屏蔽不同模型的响应格式 |
| 校验 | 检查 Schema、属性、证据和端点 | 阻止结构错误进入后续流程 |
| 合并 | 汇总所有 Chunk 的候选和问题 | 形成文档级结果 |
| 归一 | 判断不同提及是否是同一实体 | 避免重复节点 |
| 映射 | 生成节点和边的待写入变化 | 为审核和入图准备 Mutation |

## 最小代码示例

下面的代码假设你已经准备好一个 `GraphSchema` 和一个 `ChatModel`：

~~~java
ChatModel chatModel = createYourChatModel();
GraphExtractor extractor = new LlmGraphExtractor(chatModel);
GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(extractor);

Document document = Document.of(
    "张三自 2020 年起在星河科技工作。\n"
        + "李四负责支付系统升级项目。");
document.setId("meeting-2026-001");
document.setTitle("项目会议纪要");
document.putMetadata("source", "meeting.txt");

GraphExtractionResult result =
    pipeline.extract(document, schema);

// 这里只得到抽取结果，不会自动写入图数据库。
System.out.println(result.getValidatedEntities());
System.out.println(result.getValidatedRelations());
System.out.println(result.getIssues());
~~~

这段代码完成的是“文档到候选结果”，不是“文档到数据库”。如果需要长期入图，应先审核结果，再交给知识入图服务生成并执行计划。

## 代码执行了什么

1. `GraphExtractor` 负责处理单个 Chunk，可以调用大模型，但不直接写数据库；
2. `GraphExtractionPipeline` 负责分段、逐段抽取、校验、归一和 Mutation 映射；
3. `GraphExtractionResult` 保存全部候选、合法候选、问题和待审核变化；
4. `GraphWriter` 或知识入图服务只有在调用方确认后才会写入目标 Space。

默认流水线使用 1200 字符、200 字符重叠的 `SimpleDocumentSplitter`，以及默认的校验、实体归一和 Mutation 映射组件。默认参数适合开始验证，不代表适合所有语言、文档结构和生产规模。

流水线只产生内存结果，不会自动写入图数据库。

## 完整示例：处理一篇小说

~~~java
ChatModel chatModel = createYourChatModel();
GraphExtractor extractor = new LlmGraphExtractor(chatModel);
GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(extractor);

Document document = Document.of(novelText);
document.setId("novel-001");
document.setTitle("示例小说");
document.putMetadata("source", "novel.txt");

GraphExtractionResult result =
    pipeline.extract(document, schema);
~~~

这个示例与前面的会议纪要示例使用同一条流程，只是把输入换成更长、更适合分段的小说内容。默认流水线组合：

- 1200 字符、200 字符重叠的 `SimpleDocumentSplitter`；
- `SchemaGraphCandidateValidator`；
- `NameAliasGraphEntityResolver`；
- `GraphCandidateMutationMapper`。

默认参数适合开始验证，不代表适合所有语言、文档结构和模型。生产环境应使用真实文档评估分段召回率和成本。

## 为什么需要分段

分段解决的不只是模型上下文长度问题，还提供：

- 更明确的证据范围；
- 单个失败的隔离边界；
- 可控制的实体与关系候选上限；
- 更细粒度的重试和质量评估；
- 可追踪的 chunkId；
- 对章节、标题和段落结构的利用。

分段过小会切断实体和关系上下文；分段过大则增加模型遗漏、输出截断和费用。应根据文本结构和实际评测选择。

## 重叠与上下文的区别

文档重叠会让前后 Chunk 包含部分相同原文，提高跨边界关系被看见的概率，但也会产生重复候选。

流水线还会把上一 Chunk 尾部作为下一 Chunk 的消歧上下文，长度由 `maxPreviousContextCharacters` 控制。上下文只帮助模型理解当前文本，不是当前 Chunk 的证据来源；默认提示词要求不得从上下文重复抽取事实。

即便如此，不能假设模型一定遵守。证据校验和 Mutation 去重仍然必须存在。

需要特别注意：前一个 Chunk 的尾部只是帮助当前 Chunk 消解指代，不能自动证明跨 Chunk 的关系。若关系的证据分散在多个段落，建议采用章节级二次抽取、保存多段证据，或把参与者和时间等信息建模为事件节点。

## 使用自定义分段器

结构化文档通常更适合按 Markdown 标题、章节或业务段落分割：

~~~java
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    documentSplitter,
    new SchemaGraphCandidateValidator(),
    new NameAliasGraphEntityResolver(),
    new GraphCandidateMutationMapper());
~~~

知识抽取分段应尽量满足：

- 一个 Chunk 保留完整语义段落；
- 标题和上级章节信息能够通过内容或 metadata 传递；
- ID 在同一文档内稳定且唯一；
- 重新处理同一版本时顺序和内容可复现；
- 不把表头与数据行、定义与说明随意分离；
- 超长段落仍有明确的二次切分策略。

## 复用已有分段结果

应用已经使用 Agents-Flex 文档解析和分段能力时，可以直接调用 `extractChunks`，避免二次切分：

~~~java
List<Document> chunks =
    documentSplitter.split(document, idGenerator);

GraphExtractionResult result = pipeline.extractChunks(
    chunks,
    "novel-001",
    schema,
    options);
~~~

显式提供父文档 ID 后，每条 `GraphEvidence` 都能指向共同文档。未提供时，证据 documentId 为空，只能依赖 chunkId 和 metadata 定位。

## documentId 与 chunkId

`documentId` 应跨版本稳定，表示同一逻辑文档；文件名、临时上传路径或数据库自增任务号通常不适合作为长期文档身份。

`chunkId` 标识某个抽取单元：

- 优先使用 Chunk Document 自身 ID；
- 缺失时，带父文档 ID 的入口生成 `documentId#chunk-N`；
- 未提供父文档 ID 时生成 `chunk-N`；
- 最终 chunkId 必须唯一。

重复 Chunk ID 会在任何模型调用之前失败，避免不同 Chunk 中相同 mentionId 被映射到同一候选作用域。

模型返回的 `mentionId` 只在一次响应内有效。解析器会形成：

~~~text
chunkId::mentionId
~~~

它是候选引用键，不是数据库节点 ID。

## metadata 的作用

Chunk metadata 可以携带来源文件、页码、章节、租户或解析器位置等信息，并进入 `GraphEvidence`。建议只放置：

- 可安全持久化的来源标识；
- 能稳定重建的定位信息；
- 审核界面真正需要展示的信息。

不要把访问令牌、数据库密码或无需长期保存的敏感正文放入 metadata。

## Chunk 错误策略

`failOnChunkError=true` 是默认值。模型调用、根协议解析或校验器异常会立即终止整篇抽取，适合对完整性要求高的流程。

设置为 `false` 时：

- 模型或响应解析失败记录 `CHUNK_EXTRACTION_FAILED`；
- 校验器自身失败记录 `CHUNK_VALIDATION_FAILED`；
- 失败 Chunk 不产生可写入数据；
- 后续 Chunk 继续处理；
- 最终结果仍可能包含 ERROR。

容错不等于可以直接提交。尤其在文档更新中，部分 Chunk 缺失可能把仍然有效的旧关系误判为过期；入图服务默认禁止这类部分结果触发删除。

## 并发与复用

`GraphExtractionPipeline` 不保存单次请求状态，可以作为应用单例复用，前提是注入的组件也支持并发：

- ChatModel；
- GraphExtractor；
- DocumentSplitter；
- PromptBuilder 和 Parser；
- Validator；
- EntityResolver；
- MutationMapper。

`LlmGraphExtractor.setChatOptions` 对后续线程可见，但不建议在并发请求中频繁切换。模型、版本和参数应在一批任务中保持稳定，并进入抽取配置指纹。

## 抽取流程和入图生命周期的区别

```text
知识抽取流程：文档 -> 分段 -> 候选 -> 校验 -> 归一 -> GraphMutation
知识入图生命周期：Mutation -> 计划 -> 审核 -> 写图 -> 状态提交 -> 更新/撤回/恢复
```

Pipeline 的输出可以交给人工审核，也可以交给 `GraphIngestionService` 生成计划。两者之间不是自动连接的数据库事务。只有调用方显式执行 Writer 或入图计划，目标 Graph Space 才会发生变化。

## 常见问题

### overlap 越大越好吗？

不是。更大重叠提高边界召回，也增加模型调用成本和重复候选。应通过标注集衡量召回与重复率。

### 上一段上下文中的关系会被写入吗？

默认提示要求只从当前 Chunk 抽取，校验也要求证据属于当前 Chunk。上下文只用于消歧，不能单独作为关系证据。

### 一个 Chunk 失败后，其他 Chunk 的结果可以写入吗？

技术上可以保留合法子集，但需要明确采用部分提交策略。文档更新时应禁止部分结果触发旧关系删除，并将任务标记为不完整，等待补偿或人工审核。

### 可以并行处理同一文档的 Chunk 吗？

当前流水线按顺序处理，并把上一段尾部传给下一段。自定义并行方案会改变上下文语义和结果顺序，需要自行处理限流、确定性及合并。

### 为什么已有分段还要传父 documentId？

Chunk ID 只能定位分段；父 documentId 用于跨版本状态、事实来源和文档撤回。长期入图应显式提供。

## 生产检查清单

- 完整文档是否有跨版本稳定的 documentId；
- Chunk 是否按语义结构切分，并有稳定唯一 ID；
- 分段大小、重叠和上下文长度是否经过标注集评估；
- metadata 是否足以定位来源且不含秘密；
- 是否明确整篇失败或部分 Chunk 容错策略；
- 部分结果是否被禁止参与破坏性关系清理；
- 组件是否支持计划中的并发度；
- 模型和抽取配置是否在任务期间固定；
- 原始文档、Chunk 和证据偏移是否可以重建。

流水线产生候选后，继续阅读[审核](/zh/graph/extractor/review)。
