# Schema 驱动抽取

## 概述

假设有一份会议纪要：

> 2026 年 3 月，张三代表星河科技与李四讨论了支付系统升级方案。双方决定由星河科技负责实施，项目预计在 6 月完成。

如果直接把这段文字交给大模型，模型可能会返回人物、公司、会议、项目、时间和多个关系。但不同模型、不同提示词，甚至同一个模型在不同时间，都可能使用不同的类型和字段：

```text
Person / 人物 / Employee
Company / Organization / 企业
works_for / WORKS_FOR / 任职于
finishDate / completedAt / deadline
```

这些结果即使都“看起来合理”，也很难稳定写入图数据库、进行查询和持续更新。

**Schema 驱动抽取**就是在抽取前，先用一份明确的图数据定义告诉 SDK 和模型：

- 允许抽取哪些类型的对象；
- 对象有哪些属性，以及属性是什么类型；
- 哪些对象之间允许建立哪些关系；
- 关系的方向、端点类型和关系属性是什么；
- 哪些信息缺失时不能接受为合法候选。

因此，Schema 不是数据库里的一个陌生配置项，而是自然语言进入图数据之前的一份“结构说明”和“质量边界”。它帮助模型按照统一业务语言产出候选，也帮助 SDK 在写入前发现结构错误。

## 它解决什么场景

Schema 驱动抽取适合把大量非结构化内容持续转换为结构化图数据的场景，例如：

### 企业知识库

从制度、合同和会议纪要中识别人员、组织、项目、产品和决策，并建立任职、负责、参与和依赖关系。

### 小说或内容知识图谱

从章节中识别人物、组织、地点和事件，并建立人物加入组织、参与事件或到达地点的关系。

### 产品和技术文档

从说明书、发布记录和故障报告中识别组件、版本、功能和依赖关系，为影响分析和知识问答提供结构化数据。

### 风险和合规材料

从调查材料中识别主体、账户和事件，保留关系证据，并区分明确事实、模型推断和主观观点。

这些场景有一个共同点：抽取结果不是一次性文本摘要，而是要进入一个可以查询、审核、更新和撤回的长期知识库。

## 先理解三个基本概念

在阅读代码之前，可以先把 Schema 理解成下面三类定义：

```text
节点类型：图中有哪些长期存在的对象？
关系类型：这些对象之间允许有什么联系？
属性定义：对象或联系需要保存哪些信息？
```

以企业知识图谱为例：

```text
节点：Person、Company
关系：Person -[WORKS_FOR]-> Company
属性：Person.name、Company.industry、WORKS_FOR.since
```

对应的业务含义是：一个人可以在某段时间任职于一家公司，人物和公司都有自己的属性，任职关系也可以有关系属性。

## 没有 Schema 会发生什么

### 类型不统一

同一个“公司”可能被抽取为 `Company`、`Organization` 或 `企业`。后续查询必须同时兼容多个类型，实体归一也更困难。

### 关系方向不统一

同一语义可能一部分结果使用：

```text
(Person)-[WORKS_FOR]->(Company)
```

另一部分结果使用：

```text
(Company)-[HAS_EMPLOYEE]->(Person)
```

这两种表达并非不能共存，但如果没有事先约定，查询、统计和增量更新都会变得复杂。

### 属性类型不统一

同一个年份可能被返回为 `2020`、`"2020"` 或 `"2020年"`。没有类型约束时，排序、范围过滤和后续计算都可能出现问题。

### 模型凭空补充事实

原文没有写出人物年龄，但模型为了满足一个过于严格的要求自行推测年龄。Schema 和校验策略应让“原文没有就不要猜”成为明确规则。

## Schema 驱动抽取的完整过程

Schema 会同时参与多个阶段，但每个阶段的作用不同：

```text
业务人员确定图数据定义
  -> 使用 GraphSchema 描述节点、关系和属性
  -> 根据 Schema 生成模型提示
  -> 模型返回候选实体和关系
  -> Validator 检查类型、属性、端点和证据
  -> Resolver 为候选匹配长期实体身份
  -> Mapper 生成 GraphMutation
  -> 审核后写入目标 Space
```

Schema 可以限制“结构上允许出现什么”，但不能单独证明“文本中的事实一定是真的”。证据审核、实体归一、业务规则和人工审核仍然需要保留。

## 第一个完整示例：企业知识图谱

下面的 Schema 表达一个简单的企业知识图谱：

- `Person` 表示人物；
- `Company` 表示企业；
- `WORKS_FOR` 表示人物任职于企业；
- `since` 表示开始任职的年份。

```java
GraphSchema schema = GraphSchema.builder()
    .metadata(new GraphSchemaMetadata(
        "company_knowledge",
        "2.0",
        "企业知识图谱",
        "人物与企业关系",
        null))
    .nodeType(GraphSchema.NodeType.of(
        "Person",
        new GraphSchema.Property(
            "name",
            GraphSchema.PropertyType.STRING,
            true),
        new GraphSchema.Property(
            "active",
            GraphSchema.PropertyType.BOOLEAN,
            false)))
    .nodeType(GraphSchema.NodeType.of(
        "Company",
        new GraphSchema.Property(
            "name",
            GraphSchema.PropertyType.STRING,
            true),
        new GraphSchema.Property(
            "industry",
            GraphSchema.PropertyType.STRING,
            false)))
    .edgeType(GraphSchema.EdgeType.of(
        "WORKS_FOR",
        "Person",
        "Company",
        new GraphSchema.Property(
            "since",
            GraphSchema.PropertyType.INT64,
            false)))
    .index(new GraphSchema.Index(
        "person_name_idx",
        GraphSchema.IndexTarget.NODE,
        "Person",
        Collections.singletonList("name"),
        false))
    .index(new GraphSchema.Index(
        "company_name_idx",
        GraphSchema.IndexTarget.NODE,
        "Company",
        Collections.singletonList("name"),
        false))
    .build();
```

这段代码定义的是“允许抽取和保存什么”，不是在这一步导入真实数据，也不是在这一步调用大模型。

### 代码逐段解释

```java
GraphSchema schema = GraphSchema.builder()
```

创建一份 Schema 构建器。后续通过 `nodeType`、`edgeType` 和 `index` 添加图模型定义。

```java
.nodeType(GraphSchema.NodeType.of("Person", ...))
```

声明节点类型 `Person`。节点类型代表可以在多个文档和多个关系中长期复用的对象。

```java
new GraphSchema.Property("name", STRING, true)
```

声明 `Person.name` 是字符串属性，最后的 `true` 表示它是 required 属性。候选人物没有名称时，默认无法通过合法性校验。

```java
.edgeType(GraphSchema.EdgeType.of(
    "WORKS_FOR", "Person", "Company", ...))
```

声明一个有方向的关系：起点必须是 `Person`，终点必须是 `Company`。这不是数据库表的外键，而是抽取和写入阶段共同遵守的端点约束。

```java
new GraphSchema.Property("since", INT64, false)
```

声明关系可以携带一个可选的整数年份。原文没有明确年份时，候选可以不提供这个属性，不能为了满足字段而猜测。

```java
.index(new GraphSchema.Index(...))
```

声明节点索引，用于后端 Schema 管理、查询优化或实体定位。索引本身不会让模型自动识别实体，也不会替代实体归一策略。

## 模型可能产生什么，SDK 会如何处理

对于文本：

```text
张三自 2020 年起在星河科技工作。
```

符合 Schema 的候选可以表达为：

```json
{
  "entities": [
    {
      "mentionId": "m1",
      "name": "张三",
      "type": "Person",
      "properties": {
        "name": "张三"
      }
    },
    {
      "mentionId": "m2",
      "name": "星河科技",
      "type": "Company",
      "properties": {
        "name": "星河科技"
      }
    }
  ],
  "relations": [
    {
      "sourceMentionId": "m1",
      "type": "WORKS_FOR",
      "targetMentionId": "m2",
      "properties": {
        "since": 2020
      }
    }
  ]
}
```

如果模型返回未知类型 `Employee`、未知关系 `EMPLOYED_BY`，或者把 `since` 返回成无法转换的值，默认校验器会产生问题，候选不会作为合法结果直接进入 Mutation。

注意：Schema 校验的是结构和允许范围。例如它可以发现 `WORKS_FOR` 的起点不是 `Person`，但不能仅凭类型判断“张三确实在星河科技工作”。事实正确性仍需要原文证据和业务审核。

## 如何选择节点、关系和属性

### 什么适合建模为节点

如果对象有独立身份、会在多个段落出现，并且需要与其他对象建立关系，通常建模为节点：

```text
Person、Company、Product、Document、Place
```

### 什么适合建模为关系

如果信息主要表达两个对象之间的联系，并且需要沿这条联系查询，通常建模为关系：

```text
WORKS_FOR、DEPENDS_ON、MEMBER_OF、LOCATED_IN
```

### 什么适合建模为属性

只描述节点或关系自身、不会独立参与查询和连接的信息，通常建模为属性：

```text
Person.name、Company.industry、WORKS_FOR.since
```

### 什么时候使用事件节点

如果一个动作有多个参与者、时间、地点或独立生命周期，不要把所有信息压缩进一条边。例如：

```text
张三在上海参加星河科技的产品发布会。
```

可以建模为：

```text
(Person)-[PARTICIPATES_IN]->(Event)
(Company)-[ORGANIZES]->(Event)
(Event)-[LOCATED_IN]->(Place)
```

这样未来可以继续添加会议时间、参与人员、会议议题和会议来源。

## 同一对节点可以有多种关系

Schema 允许同一对节点类型声明多种关系：

```java
.edgeType(GraphSchema.EdgeType.of(
    "WORKS_FOR", "Person", "Company"))
.edgeType(GraphSchema.EdgeType.of(
    "FOUNDED", "Person", "Company"))
.edgeType(GraphSchema.EdgeType.of(
    "INVESTED_IN", "Person", "Company"))
```

这表示同一个人和同一家公司之间可以同时存在任职、创立和投资等不同语义。每种关系可以有自己的属性和校验规则。

同样可以声明 `Person` 和 `Person` 之间的关系：

```java
.edgeType(GraphSchema.EdgeType.of(
    "KNOWS", "Person", "Person"))
```

关系是否允许重复实例、是否需要时间区间或 rank，应根据业务模型进一步定义。不要用一个含义模糊的 `RELATED_TO` 属性承载所有关系类型，否则查询和抽取都会失去明确约束。

## 属性要求和类型约束

### `required` 不是“让模型猜答案”

`required=true` 表示候选要进入合法结果必须提供该属性，适合姓名、业务编号等关键字段。

如果原文不一定提供人物年龄，就不应把 `age` 设为 required。required 不能迫使模型知道原文中不存在的信息。

### 常见属性类型

| 类型 | 适合表达 | 注意事项 |
| --- | --- | --- |
| `STRING` | 名称、状态、说明 | 不要把所有数字都保存成字符串 |
| `BOOLEAN` | 明确的是/否事实 | 原文不明确时不要推断 |
| `INT64` | 年份、数量、整数编号 | 不能直接接收含单位的任意文本 |
| `DOUBLE` | 金额、权重、比例 | 必须是有限数，不能是 NaN 或无穷值 |
| `DATE` | 日期 | 使用明确日期或合法日期字符串 |
| `DATETIME` | 日期时间 | 注意时区和精度 |

“去年冬天”这类模糊时间不要强行转换为精确日期。可以保存原文描述，或者在 Schema 中增加时间精度和解析状态。

## Schema 元数据的作用

Schema 不只保存机器名称，还可以通过类型和属性的展示名称、描述以及枚举值帮助模型理解业务含义：

- `displayName`：给人和模型看的业务名称；
- `description`：解释什么情况下应该使用该类型或属性；
- `enumValues`：限制状态等属性的允许范围；
- `sourceLabel`、`targetLabel`：说明关系端点类型；
- `required`：说明候选完整性的最低要求。

例如，技术名称可以是 `WORKS_FOR`，展示名称可以是“任职于”，描述可以说明“只有原文明确表达任职关系时才抽取”。技术名称用于查询和写入，展示信息用于提示、审核和文档。

当前默认解析器不会因为 `defaultValue` 存在就虚构原文事实。一个确定性例外是：节点类型声明字符串 `name` 属性，而模型遗漏它时，可以使用候选实体主名称填充 `name`。

## Schema 版本和数据库 Schema

每次抽取都应记录使用的 Schema 版本。以下变化可能改变结果：

- 新增或删除节点、关系和属性；
- 修改属性类型、required 或枚举；
- 修改关系方向和端点类型；
- 修改类型或属性描述；
- 将直接关系重构为事件节点。

内容没有变化但 Schema 版本变化时，增量入图服务也可能需要重新抽取。破坏性调整还要配合[Schema 增量迁移](/zh/graph/schema-migration)。

抽取 Schema 和后端数据库 Schema 建议使用同一份 `GraphSchema`，但职责不同：

| 阶段 | Schema 的作用 |
| --- | --- |
| 模型提示 | 告诉模型允许提取什么 |
| 候选校验 | 拒绝未知类型、属性和错误端点 |
| Mutation 映射 | 生成符合图模型的节点和边 |
| 后端管理 | 创建或检查 Label、Edge、Tag 和索引 |

Extractor 不会自动替应用创建或迁移数据库 Schema。应用应在导入前独立验证目标 Space，并在破坏性变更时执行迁移和回滚策略。

## 一个从定义到抽取的最小流程

```java
// 1. 定义允许抽取的图结构
GraphSchema schema = createCompanyKnowledgeSchema();

// 2. 使用 Schema 创建抽取流水线
GraphExtractor extractor = new LlmGraphExtractor(chatModel);
GraphExtractionPipeline pipeline =
    new GraphExtractionPipeline(extractor);

// 3. 准备文档并执行抽取
Document document = Document.of(
    "张三自 2020 年起在星河科技工作。");
document.setId("meeting-2026-001");
GraphExtractionResult result = pipeline.extract(document, schema);

// 4. 先检查问题和审核策略，再决定是否写图
if (!result.hasErrors()) {
    graphStore.writer().mutate(
        result.getMutation(),
        GraphOptions.ofSpace("company_knowledge"));
}
```

这个流程中：

1. `GraphSchema` 定义允许出现的图结构；
2. `GraphExtractionPipeline` 使用 Schema 处理文档；
3. `GraphExtractionResult` 保存候选、问题和 Mutation；
4. `GraphWriter` 只有在调用方确认后才会写入目标 Space。

## 常见问题

### Schema 是不是数据库表结构？

不是。它描述图中的节点、关系、属性和约束，既可以用于抽取和校验，也可以用于后端 Schema 管理。图数据库中的 Label、Tag、Edge 和索引是它在具体后端中的一种实现。

### Schema 越大，抽取越好吗？

不是。类型和关系过多会增加模型选择歧义、提示长度和误报概率。应先设计能够回答当前业务问题的最小 Schema，再按版本逐步扩展。

### 可以让模型自由发明关系类型吗？

正式入图时不建议。未知关系可以先作为探索候选保存，经过人工归类、Schema 发布和迁移后，再进入正式抽取。

### 数据库支持动态属性，为什么还需要 Schema？

数据库能保存一个属性，不代表不同批次会使用一致的名称、类型和业务含义。Schema 主要保证数据生产契约和长期可维护性。

## 设计检查清单

- 是否先明确了知识库需要回答的业务问题；
- 节点是否具有独立身份和长期复用价值；
- 关系名称、方向和端点类型是否明确；
- 同一对节点的多种关系是否分别定义；
- 多参与者动作是否应建模为事件节点；
- required 是否只用于原文应当提供的信息；
- 属性类型、枚举和描述是否足以约束模型；
- 是否区分技术名称、展示名称和业务描述；
- Schema 版本是否进入每次抽取和文档状态；
- 后端数据库 Schema 是否在入图前独立应用并验证；
- Schema 变化是否配套历史数据迁移或重抽策略。

Schema 确定后，下一步阅读[知识抽取流程](/zh/graph/knowledge-extraction-pipeline)。
