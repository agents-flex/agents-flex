# 知识抽取模块概览

## 概述

业务知识经常存在于小说、合同、产品手册、研究报告、会议纪要和网页正文中。人可以从这些内容中看出“谁属于哪个组织”“某项产品依赖什么组件”“哪份文件提到了哪个事件”，图数据库却不能直接理解一段自然语言。

`agents-flex-graph-extractor` 用于连接这两个世界：它按照业务定义的 Graph Schema，从文档中提取候选实体和关系，保留原文证据与置信度，完成校验和实体归一，最后生成可以审核的 `GraphMutation`。

这项能力不只是“让大模型返回节点和边”。一个可以长期维护的知识图谱还需要回答：

- 模型输出是否符合业务 Schema；
- 一条关系能否回到原始文档和具体分段；
- “北京大学”和“北大”是不是同一个实体；
- 几个月后导入新文件时，如何复用已有实体；
- 文档修改后，哪些旧关系应保留或撤销；
- 多份文档支持同一关系时，能否只撤销其中一个来源；
- 图写入成功但状态保存失败时，如何恢复；
- 如何让开发者在自己的后台中实现审核、重试和审计。

Graph Extractor 因此是一套从非结构化内容到可维护图数据变更的 SDK 工作流，而不是一个自动生成最终真相的黑盒。

## 解决什么问题

### 把自然语言转成受约束的图数据

直接让模型自由决定类型和属性，容易得到 `Person`、`People`、`人物` 等互不兼容的结构。Extractor 使用现有 Graph Schema 作为模型输出白名单和校验依据，使抽取结果能够进入既定图模型。

### 保留“为什么得到这条关系”

知识图谱中的关系不能只有结论。候选结果可以携带文档 ID、分段 ID、原文引文、字符偏移、置信度和断言类型，供自动规则、人工审核和后续审计使用。

### 让同一个知识库持续增长

同一个 Space 可以先导入一批历史文件，之后持续接收新文件和文档新版本。长期入图服务通过稳定文档 ID、内容摘要、Schema 版本、实体注册表和事实来源，计算本次需要新增、更新或撤销的图数据。

### 为开发者自己的产品提供 SDK 基础

开发者可以基于抽取结果和执行计划建设自己的：

- Schema 配置页面；
- 文件导入任务；
- 候选实体和关系审核台；
- 证据查看与修改流程；
- 文档版本和撤回管理；
- 失败任务恢复与对账工具。

Graph SDK 提供数据模型、状态和扩展点，不负责实现这些 UI。

## 典型场景

### 小说与内容知识图谱

从章节中提取人物、组织、地点和事件，建立人物加入组织、参与事件或到达地点的关系。事件具有时间、地点和多个参与者时，通常建模为独立节点，而不是把所有信息压在一条边上。

### 企业知识库

从制度、合同和会议纪要中提取组织、人员、项目、产品和决策。用户可以沿来源文档追溯一条关系，也可以在新版本文件生效后撤销旧声明。

### 产品与技术文档

从说明书和发布记录中抽取组件、功能、依赖、版本和兼容关系，为影响分析、问答和导航提供结构化上下文。

### 风险与合规资料

从调查材料中提取主体、账户、事件和关联线索。此类场景必须严格区分明确事实、模型推断和主观观点，并保留证据和审核记录。

## 从文档到图谱的完整流程

~~~text
原始文件或业务内容
  -> 文档解析
  -> 稳定 documentId 和版本信息
  -> 文档分段
  -> Schema 引导的候选抽取
  -> JSON 协议解析
  -> Schema、证据和质量校验
  -> 跨分段及跨批次实体归一
  -> 生成可审核的 GraphMutation
  -> GraphWriter 写入目标 Space
  -> 保存文档状态、实体注册和事实来源
  -> 后续更新、撤回、恢复与对账
~~~

前半段回答“文本中可能包含什么”，后半段回答“如何把确认后的知识长期、安全地维护在图中”。两部分可以组合使用，也可以分别替换。

## 与 Graph 基础能力的关系

| 能力 | 负责的问题 |
| --- | --- |
| Graph Schema | 图谱允许有哪些节点、边和属性 |
| Graph Extractor | 从文本中产生并校验候选实体和关系 |
| Graph Entity Resolver | 判断候选是否指向同一个业务实体 |
| Graph Mutation | 表达审核后准备执行的节点和边变化 |
| Graph Writer | 把变化写入 Neo4j、Nebula 等后端 |
| 知识入图服务 | 管理文档版本、差异、来源和恢复状态 |
| Graph Query | 查询已经物化到 Space 中的图数据 |

Extractor 不依赖具体 Neo4j 或 Nebula 适配器。抽取和审核可以在没有数据库连接的环境中进行，确认后再把 Mutation 交给任意 `GraphWriter`。

## 两种使用层级

### 单次抽取

适合实验、一次性处理或由应用自行管理状态：

~~~text
Document -> GraphExtractionPipeline -> GraphExtractionResult
                                      -> 审核
                                      -> GraphWriter
~~~

流水线本身不会自动写入数据库。即使结果包含可执行 Mutation，调用方也应先检查问题、证据和业务审核策略。

### 长期知识入图

适合一个知识库持续接收文件：

~~~text
Document + 文档版本 + 已有文档状态 + 实体注册表
  -> GraphIngestionPlan
  -> 审核
  -> 图写入
  -> 文档状态、事实来源和操作状态提交
~~~

这一路径处理内容判重、历史实体复用、旧关系识别、文档撤回、乐观锁和故障恢复，但仍需要开发者提供生产级持久化实现和任务调度。

## 模块边界

模块负责：

- 组合 `DocumentSplitter`、`ChatModel` 与 Graph API；
- 根据 Schema 生成提示和校验输出；
- 解析模型返回的候选实体、关系和证据；
- 跨分段归一实体并生成稳定节点 ID；
- 把合法候选映射为 GraphMutation；
- 规划文档首次入图、更新和撤回；
- 暴露文档状态、事实来源、操作日志和恢复扩展点。

模块不负责：

- 文件上传、格式解析和对象存储；
- 模型训练或知识正确性的最终裁决；
- 人工审核 UI 和产品权限；
- 自动创建或迁移数据库 Schema；
- 分布式任务调度、租约和死信队列；
- 自动提供跨图数据库与状态存储的分布式事务；
- 替应用构建业务主数据或解决所有同名实体歧义。

## 建议阅读顺序

第一次接入时建议依次阅读：

1. [Schema 驱动抽取](/zh/graph/extractor/schema-driven-extraction)：先确定允许抽取什么；
2. [知识抽取流程](/zh/graph/extractor/extraction-process)：理解文本如何进入抽取流程；
3. [知识入图生命周期](/zh/graph/extractor/ingestion-lifecycle)：明确抽取、计划、执行和恢复的副作用边界；
4. [核心类](/zh/graph/extractor/core-classes)：按包了解请求、候选、状态、入图和扩展类；
5. [审核](/zh/graph/extractor/review)：决定什么可以进入审核和 Mutation，并把候选、证据和计划接入自己的产品流程；
6. [实体归一](/zh/graph/extractor/entity-resolution)：避免重复实体；
7. [知识入图](/zh/graph/extractor/ingestion)：把结果接入目标 Space。

准备生产运行时继续阅读：

8. [故障恢复](/zh/graph/extractor/recovery)；
9. [错误处理](/zh/graph/extractor/error-handling)；
10. [模型接入](/zh/graph/extractor/model-integration)。

## 最小依赖

~~~xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-extractor</artifactId>
    <version>${VERSION}</version>
</dependency>
~~~

模块依赖 `agents-flex-core` 和 `agents-flex-graph-api`，不绑定具体图数据库。应用还需要选择自己的 ChatModel 实现；只有真正写图时才需要 Neo4j 或 Nebula 等 GraphStore 后端。

## 一个最小认知示例

~~~java
GraphExtractor extractor = new LlmGraphExtractor(chatModel);
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(extractor);

Document document = Document.of("林默加入青云宗。");
document.setId("novel-001");

GraphExtractionResult result = pipeline.extract(document, schema);

// 抽取结果不是数据库写入凭证，先执行审核策略。
if (!result.hasErrors()) {
    GraphWriteResult write = graphStore.writer().mutate(
        result.getMutation(),
        GraphOptions.ofSpace("novel_knowledge"));
}
~~~

这个示例只展示职责连接。生产流程还需要稳定文档版本、持久化实体注册、事实来源、操作恢复以及写入后对账，后续各篇将逐层展开。
