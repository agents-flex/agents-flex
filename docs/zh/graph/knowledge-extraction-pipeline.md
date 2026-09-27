# 知识抽取流程

## 概述

大模型通常无法一次稳定处理整本小说或大量企业文档。即使上下文窗口足够，把整篇内容一次送入模型也会增加成本、响应不稳定性和证据定位难度。

`GraphExtractionPipeline` 把完整文档拆成可管理的 Chunk，对每个 Chunk 执行候选抽取和校验，再统一完成实体归一与 Mutation 映射。

本文关注一次知识抽取从文档进入到生成 `GraphExtractionResult` 的具体处理步骤；抽取结果生成之后如何规划、审核、执行和恢复，见[知识入图生命周期](/zh/graph/knowledge-extraction-flow)。

~~~text
Document
  -> DocumentSplitter
  -> 每个 Chunk 调用 GraphExtractor
  -> GraphCandidateParser
  -> GraphCandidateValidator
  -> 合并所有合法候选
  -> GraphEntityResolver
  -> GraphMutationMapper
  -> GraphExtractionResult
~~~

流水线只产生内存结果，不会自动写入图数据库。

## 创建最小流水线

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

默认流水线组合：

- 1200 字符、200 字符重叠的 `SimpleDocumentSplitter`；
- `SchemaGraphCandidateValidator`；
- `NameAliasGraphEntityResolver`；
- `GraphMutationMapper`。

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

流水线还会把上一 Chunk 尾部作为下一 Chunk 的消歧上下文，长度由 `contextCharacters` 控制。上下文只帮助模型理解当前文本，不是当前 Chunk 的证据来源；默认提示词要求不得从上下文重复抽取事实。

即便如此，不能假设模型一定遵守。证据校验和 Mutation 去重仍然必须存在。

## 使用自定义分段器

结构化文档通常更适合按 Markdown 标题、章节或业务段落分割：

~~~java
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    documentSplitter,
    new SchemaGraphCandidateValidator(),
    new NameAliasGraphEntityResolver(),
    new GraphMutationMapper());
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

容错不等于可以直接提交。尤其在文档更新中，部分 Chunk 缺失可能把仍然有效的旧关系误判为过期；增量服务默认禁止这类部分结果触发删除。

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

## 常见问题

### overlap 越大越好吗？

不是。更大重叠提高边界召回，也增加模型调用成本和重复候选。应通过标注集衡量召回与重复率。

### 上一段上下文中的关系会被写入吗？

默认提示要求只从当前 Chunk 抽取，校验也要求证据属于当前 Chunk。上下文只用于消歧。

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

流水线产生候选后，继续阅读[候选质量审核](/zh/graph/knowledge-extraction-quality)。
