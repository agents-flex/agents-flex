# Schema 驱动抽取

## 概述

知识抽取首先要回答的不是“选哪个大模型”，而是“图谱允许表达什么”。如果没有稳定的业务模型，同一批文档可能被抽取成不同类型、不同属性和不同方向的关系，最终无法可靠查询和维护。

Graph Extractor 复用 Graph Schema，把它同时作为：

- 模型可输出的节点、边和属性白名单；
- 提示词中的业务语义说明；
- 候选结果的类型和完整性校验规则；
- 后续 GraphMutation 与数据库 Schema 的共同契约。

Schema 约束可以显著减少自由输出，但不能证明模型提取的事实一定正确。证据审核、实体归一和业务规则仍然不可缺少。

## 从业务问题开始

设计抽取 Schema 前，应先写出希望图谱回答的问题。例如：

- 哪些人物加入了哪些组织？
- 一项决策由谁在什么会议中提出？
- 某个组件依赖哪些服务和版本？
- 一条风险关系来自哪份材料？

然后识别：

1. 有独立身份并需要长期复用的对象，建模为节点；
2. 需要沿其遍历的关联，建模为边；
3. 关联自身的时间、角色和置信信息，建模为边属性；
4. 具有多个参与者或独立生命周期的动作，优先建模为事件节点；
5. 需要审计的来源信息，保存在事实来源或独立 Fact 模型中。

不要把现有文档标题或数据库表机械转换成节点类型。

## 定义一个抽取 Schema

~~~java
GraphSchema.Property name = new GraphSchema.Property(
    "name", GraphSchema.PropertyType.STRING, true);

GraphSchema schema = GraphSchema.builder()
    .nodeType(GraphSchema.NodeType.of("Character", name))
    .nodeType(GraphSchema.NodeType.of("Organization", name))
    .nodeType(GraphSchema.NodeType.of(
        "Event",
        name,
        new GraphSchema.Property(
            "summary",
            GraphSchema.PropertyType.STRING,
            false)))
    .edgeType(GraphSchema.EdgeType.of(
        "MEMBER_OF", "Character", "Organization"))
    .edgeType(GraphSchema.EdgeType.of(
        "PARTICIPATES_IN", "Character", "Event"))
    .build();
~~~

模型只能产生 Schema 中声明的类型和属性。默认校验器会拒绝未知节点类型、未知关系类型、未知属性和不兼容的属性值。

## 节点和关系如何建模

### 节点应具有可识别身份

`Character`、`Company`、`Product` 和 `Document` 适合作为节点，因为它们可以在多个段落或文档中反复出现，并与其他实体建立关系。

避免把每一种描述都创建成节点。单纯描述实体自身、无需独立查询和连接的信息，更适合作为属性。

### 关系方向必须稳定

同一个业务含义应始终使用相同方向，例如：

~~~text
(Character)-[MEMBER_OF]->(Organization)
~~~

不要让不同模型调用有时返回 `MEMBER_OF`，有时返回反向的 `HAS_MEMBER`。Schema 和提示描述应明确方向、含义以及允许的端点。

### 复杂动作优先建成事件

“林默于某日参加青云会在杭州举行的大会”包含人物、组织、时间和地点。若把它压缩成一条直接边，后续很难添加更多参与者和事件属性。

更适合的模型是：

~~~text
(Character)-[PARTICIPATES_IN]->(Event)
(Organization)-[ORGANIZES]->(Event)
(Event)-[LOCATED_IN]->(Place)
~~~

## Schema 元数据如何影响抽取

默认提示词会使用类型和属性的业务说明，帮助模型理解机器名称。当前适合用于抽取指导的信息包括：

- 类型和属性的 `displayName`；
- 类型和属性的 `description`；
- 属性 `enumValues`；
- 关系的 sourceLabel 和 targetLabel；
- 属性名、类型及 required 意图。

`enumValues` 同时参与校验，模型返回范围外的值会被拒绝。

`defaultValue` 不会用于虚构原文中没有的信息，也不会由默认解析器自动补写。当前解析器有一个确定性例外：如果节点类型声明了字符串 `name` 属性而模型遗漏它，会使用候选实体主名称填充。

## required 的正确理解

`required=true` 表示候选进入合法结果前必须提供该属性。它适合姓名、业务编号等没有就无法使用的字段。

但不要把“文本中可能不存在的信息”都标成 required。例如文档未必写出人物年龄；将 age 设为 required 会让原本合法的人物候选全部被拒绝。

required 也不能迫使模型知道答案。提示词应明确：原文没有的信息不得猜测或用默认值补齐。

## 属性类型

公共 Schema 支持字符串、布尔、整数、浮点、日期和日期时间等类型。Extractor 会检查 Java 值是否兼容：

- `STRING` 用于名称、状态和说明；
- `BOOLEAN` 用于明确的是/否事实；
- `INT64` 用于年份、数量和整数编号；
- `DOUBLE` 用于权重和置信类数值；
- `DATE`、`DATETIME` 接受对应 Java 时间对象或合法 ISO-8601 字符串。

DOUBLE 属性和候选 confidence 必须是有限数，NaN 与正负无穷值会被拒绝。

当原文中的时间含糊，例如“去年冬天”，不要强行伪造精确日期。可以保存原文时间描述，或在业务 Schema 中设计时间精度和解析状态。

## 关系端点约束

关系类型应尽量声明 sourceLabel 和 targetLabel。默认校验器会验证候选关系引用的实体类型是否符合约束。

端点约束解决的是抽取阶段的数据质量，而不一定由目标图数据库 DDL 完整强制。即使数据库允许连接错误类型，Extractor 也应在入图前拒绝：

~~~text
(Organization)-[MEMBER_OF]->(Character)  // 方向和类型不符合 Schema
~~~

## Schema 版本

长期知识库必须记录每次抽取使用的 Schema 版本。以下变化都可能改变抽取结果：

- 新增节点或关系类型；
- 修改类型说明；
- 新增或删除属性；
- 改变 required 或枚举；
- 改变关系方向和端点；
- 把直接关系重构为事件节点。

内容没有变化但 Schema 版本变化时，增量服务会重新抽取。破坏性 Schema 调整还需要配合[Schema 增量迁移](/zh/graph/schema-migration)，不能只重跑模型就假设旧图结构已经清理。

## 抽取 Schema 与数据库 Schema

建议两者使用同一份 `GraphSchema`，但它们关注的阶段不同：

| 阶段 | Schema 的作用 |
| --- | --- |
| 模型提示 | 告诉模型允许提取什么 |
| 候选校验 | 拒绝未知类型、属性和错误端点 |
| Mutation 映射 | 生成符合图模型的节点和边 |
| 数据库管理 | 创建或检查后端 Tag、Label、Edge 和索引 |

Extractor 不会自动调用 `GraphManager.applySchema...`。应用应在导入前独立创建和验证目标 Space 的数据库 Schema。

## 常见问题

### Schema 越大，抽取越好吗？

不是。类型和关系过多会增加模型选择歧义、提示长度和误报概率。优先为当前业务问题设计最小可用 Schema，再按版本扩展。

### 可以让模型动态发明关系类型吗？

默认不可以，这也是为了保证图谱可查询和可维护。需要探索未知关系时，可以先进入独立候选流程，经过人工归类和 Schema 发布后再正式入图。

### 数据库支持动态属性，为什么还要 Schema？

数据库能够保存某个属性，不代表不同批次会使用一致的名字、类型和业务含义。抽取 Schema 主要解决数据生产契约和质量问题。

### Schema 默认值会自动补全缺失事实吗？

不会。缺少证据的事实不应仅因为存在默认值而写入知识图谱。

## 设计检查清单

- Schema 是否来自明确的业务查询问题；
- 节点是否具有独立身份和长期复用价值；
- 关系方向和端点类型是否明确；
- 多参与者动作是否应建模为 Event；
- required 是否只用于原文应当明确提供的信息；
- 属性类型、枚举和说明是否足以约束模型；
- 是否区分事实字段和展示元数据；
- Schema 版本是否进入每次抽取和文档状态；
- 数据库 Schema 是否在入图前独立应用并验证；
- Schema 变化是否配套历史数据迁移或重抽策略。

Schema 确定后，下一步阅读[知识抽取流程](/zh/graph/knowledge-extraction-pipeline)。
