# Schema 定义

## 概述

Graph Schema 描述一个 Graph Space 中允许出现的节点类型、边类型、属性和索引。它是应用对图结构的期望声明，也是数据导入、查询、迁移和开发工具共同理解图谱的基础。

如果把 Graph Space 类比为 MySQL Database，那么 Graph Schema 可以粗略类比为表、列和索引的集合；但图数据库还需要表达节点类型、边类型以及边连接实体的业务含义。

例如，一个企业知识图谱可能包含：

```text
(Person)-[WORKS_FOR]->(Company)
(Company)-[LOCATED_IN]->(City)
(Document)-[MENTIONS]->(Person)
```

Schema 需要回答：

- `Person`、`Company`、`City` 和 `Document` 有哪些属性？
- `WORKS_FOR`、`LOCATED_IN` 和 `MENTIONS` 可以保存哪些关系属性？
- 哪些属性是业务必需信息？
- 哪些字段会被频繁过滤，需要建立索引？
- 哪些结构属于所有后端都能表达的公共能力？

Graph SDK 中的 Schema 是声明模型，不是执行中的数据库连接，也不会在构建对象时直接修改数据库。只有调用 Schema 管理能力后，目标后端才会把可支持的定义转换为实际数据库结构。

## 为什么需要 Schema

### 统一数据生产和数据使用

在没有 Schema 的图谱中，不同导入任务可能分别写入 `Person`、`People`、`PERSON`，或者把同一个 `age` 属性一部分写成字符串、一部分写成整数。查询和分析很快就会失去稳定基础。

Schema 为抽取、导入和查询建立共同约定：

- 数据生产方知道可以写入哪些节点、边和属性；
- 查询方知道可以依赖哪些类型和字段；
- 知识抽取流程可以根据 Schema 约束模型输出；
- 管理工具可以根据 Schema 生成表单、映射和提示；
- 迁移流程可以比较期望结构与实际结构。

### 控制图谱演进

图谱会持续变化。开始时可能只有人物和组织，后来加入事件、地点、来源、置信度和版本信息。Schema 将这些变化从“临时写入行为”提升为可审查、可版本化的结构变更。

### 为查询和性能规划提供依据

图数据库允许沿边遍历，但属性过滤仍然依赖合理的类型和索引。Schema 可以显式声明查询需要的索引，避免数据量增长后才发现常用条件无法高效执行，或者在 Nebula 中因缺少索引而无法通过查询优化阶段。

## Schema 不是什么

为避免职责混淆，需要明确：

- Schema 不是 Java 领域对象的完整复制；
- Schema 不是 UI 表单或页面配置本身；
- Schema 不负责租户权限、数据审批和业务流程；
- Schema 不自动完成历史数据修复和回填；
- Schema 元数据中的展示名称、默认值和枚举，不保证被编译为数据库 DDL；
- `required` 表达公共模型中的业务意图，不应假设每个后端都会生成等价的非空约束；
- Schema 定义成功，不代表索引传播完成或数据已经符合新规则。

数据库约束、应用校验、知识抽取校验和数据治理需要共同保证数据质量。

## 从业务问题开始建模

设计 Schema 前，不要先把现有 MySQL 表机械地转换成节点。应先明确业务需要回答的问题，例如：

- 某个人物出现在哪些文档和事件中？
- 一家公司通过哪些人物和另一家公司发生关联？
- 一个风险账户与哪些设备、地址和交易共享特征？
- 某份文档中的事实来自哪些来源？

然后依次识别：

1. 可以独立识别的实体，设计为节点；
2. 需要被遍历或解释的关联，设计为边；
3. 描述实体自身的信息，设计为节点属性；
4. 描述关联自身的信息，设计为边属性；
5. 常用过滤、定位和排序字段，评估索引；
6. 稳定 ID、来源、版本和时间等治理信息。

## 节点类型

节点类型描述一类业务实体，例如：

```text
Person、Company、Document、Event、Product
```

每个节点类型包含一个可移植名称和一组属性。名称会映射到 Neo4j Label 或 Nebula Tag，因此必须符合 Graph SDK 的标识符规则。

```java
GraphSchema.NodeType person = GraphSchema.NodeType.of(
    "Person",
    new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
    new GraphSchema.Property("age", GraphSchema.PropertyType.INT64, false),
    new GraphSchema.Property("active", GraphSchema.PropertyType.BOOLEAN, false));
```

设计节点类型时建议：

- 使用稳定的机器名称，不把可变中文展示名称当作类型标识符；
- 一个类型表达一个清晰的业务概念；
- 不要为每个文件、批次或状态创建新节点类型；
- 跨后端模型优先使用一个清晰的主类型；
- 为实体准备稳定业务 ID，但不要把后端内部 ID 当作跨系统身份；
- 把来源、版本、创建时间等治理字段纳入整体模型设计。

Graph SDK 会拒绝同一个 Schema 中重复的节点类型和同一节点类型内重复的属性名，避免后续比较或 DDL 编译时发生静默覆盖。

## 边类型

边类型描述两个节点之间的业务关联，例如：

```text
WORKS_FOR、KNOWS、MENTIONS、BELONGS_TO
```

边可以声明起点类型、终点类型和边属性：

```java
GraphSchema.EdgeType worksFor = GraphSchema.EdgeType.of(
    "WORKS_FOR", "Person", "Company",
    new GraphSchema.Property("since", GraphSchema.PropertyType.INT64, false),
    new GraphSchema.Property("role", GraphSchema.PropertyType.STRING, false));
```

边属性适合保存关系自身的信息，例如发生时间、角色、权重、来源和置信度。不要把属于目标实体的信息重复复制到边上。

需要注意，端点类型约束属于公共 Schema 意图，但当前 Neo4j 和 Nebula 后端都不能通过 Portable Schema 完整强制执行起点和终点标签。应用仍应在写入校验、抽取校验和测试中确保边连接了正确类型的节点。

当业务确实允许一个边类型连接任意节点类型时，可以使用不声明端点约束的边类型。这种形式也常见于从后端反查 Schema，因为数据库元数据未必能恢复可靠的端点标签。

## 属性

属性可以属于节点或边。公共 Schema 支持以下基础类型：

| 类型 | 适合的数据 | 常见 Java 值 |
| --- | --- | --- |
| `STRING` | 名称、状态、说明、外部编号 | `String` |
| `BOOLEAN` | 是否启用、是否确认 | `Boolean` |
| `INT64` | 数量、年份、整数评分 | `Long` |
| `DOUBLE` | 权重、置信度、比例 | `Double` |
| `DATE` | 业务日期 | 后端支持的日期值 |
| `DATETIME` | 事件时间、更新时间 | 后端支持的日期时间值 |

### required 的含义

`required=true` 表达应用期望该类实体提供此属性。它可以被上层表单、抽取校验和导入校验使用，但不同图数据库能否生成完全等价的强约束并不一致。

因此，不应只依赖 Schema 应用来保证必填数据。关键字段还应在写入前校验，并通过数据库真实数据检查确认约束是否生效。

### 默认值和枚举

属性元数据可以描述展示名称、说明、默认值和枚举候选。这些信息适合用于：

- Schema 设计器；
- 数据映射和导入向导；
- 知识抽取提示与结果校验；
- 开发者文档和表单生成。

它们是开发工具元数据，不会被所有后端自动转换成默认值约束或枚举约束。业务应用必须决定何时填充默认值、如何处理未知枚举值以及如何迁移已有数据。

### 属性还是边

一个常见建模问题是“这项信息应该是属性还是边”。可以使用以下判断：

| 问题 | 更适合属性 | 更适合边 |
| --- | --- | --- |
| 是否只是描述当前实体？ | 是 | 否 |
| 是否需要独立身份和自己的属性？ | 否 | 是 |
| 是否需要沿它继续遍历？ | 否 | 是 |
| 是否会被多个实体共享？ | 通常否 | 通常是 |
| 是否需要保存关联时间、来源、角色？ | 不适合 | 适合 |

例如，人的姓名适合作为属性；人所在的公司适合建模为 `WORKS_FOR` 边，而不是仅在 Person 上保存 `companyId` 字符串。

## 索引

索引用于加速节点或边属性过滤，也可能表达唯一性意图。索引定义包含：

- 稳定的索引名称；
- 目标是节点还是边；
- 所属节点类型或边类型；
- 一个或多个索引属性；
- 是否要求唯一。

```java
GraphSchema.Index personNameIndex = new GraphSchema.Index(
    "person_name_idx",
    GraphSchema.IndexTarget.NODE,
    "Person",
    Collections.singletonList("name"),
    false);
```

索引应来自真实查询模式，而不是给每个属性都盲目建立。设计时应回答：

- 哪些属性经常作为查询起点？
- 哪些属性用于高选择性过滤？
- 哪些属性用于业务唯一定位？
- 写入吞吐和索引维护成本是否可接受？
- 组合索引的字段顺序是否符合后端查询方式？

Neo4j 后端会为公共节点 ID 创建唯一约束，并根据公共 Schema 创建索引；关系唯一索引不属于当前 Portable 能力。Nebula Portable Schema 不支持唯一索引，而且属性过滤通常要求存在有效索引；索引创建后还需要完成重建和数据面传播。

## Schema 元数据与版本

Schema 可以携带稳定业务 ID、版本、展示名称、描述和扩展属性；节点类型、边类型和属性也可以携带展示元数据。

这些信息适合上层系统实现：

- Schema 版本目录；
- 发布记录和审批；
- 图谱设计器；
- 字段说明和数据字典；
- 知识抽取提示；
- 导入映射模板。

元数据不会自动写入图数据库 DDL，也不会替上层持久化版本历史。应用需要把期望 Schema 作为版本化制品保存在代码仓库、配置中心或自己的 Schema Registry 中。

## 一个完整定义示例

下面的示例描述一个简化的企业知识图谱：

```java
GraphSchema schema = GraphSchema.builder()
    .metadata(new GraphSchemaMetadata(
        "company_knowledge", "2.0", "企业知识图谱", "人物与企业关系", null))
    .nodeType(GraphSchema.NodeType.of("Person",
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
        new GraphSchema.Property("active", GraphSchema.PropertyType.BOOLEAN, false)))
    .nodeType(GraphSchema.NodeType.of("Company",
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
        new GraphSchema.Property("industry", GraphSchema.PropertyType.STRING, false)))
    .edgeType(GraphSchema.EdgeType.of("WORKS_FOR", "Person", "Company",
        new GraphSchema.Property("since", GraphSchema.PropertyType.INT64, false)))
    .index(new GraphSchema.Index(
        "person_name_idx", GraphSchema.IndexTarget.NODE,
        "Person", Collections.singletonList("name"), false))
    .index(new GraphSchema.Index(
        "company_name_idx", GraphSchema.IndexTarget.NODE,
        "Company", Collections.singletonList("name"), false))
    .build();
```

这个对象只是期望结构。应用它之前，应先确认目标 Space 已存在、后端支持所需能力，并执行校验和迁移预览。

## 校验 Schema

校验用于在执行 DDL 前发现公共定义错误和后端能力冲突：

```java
GraphSchemaValidation validation =
    graph.manager().validateSchema("company_knowledge", schema);

if (!validation.isValid()) {
    throw new IllegalStateException(validation.getErrors().toString());
}
```

校验结果分为：

- **errors**：阻止当前 Schema 应用的问题；
- **warnings**：可以继续，但上层需要明确展示和评估的问题。

例如，Nebula 的唯一索引会成为校验错误；边端点类型无法由后端完整强制执行时会产生警告。

校验只针对当前 SDK 能识别的定义和后端差异，不证明数据库容量足够、历史数据符合约束、账号拥有全部 DDL 权限，也不替代真实环境执行。

## 应用 Schema

Schema 应在应用部署、租户/知识库初始化或独立迁移流程中执行，不应在每次写入前重复应用。

```java
GraphSchemaApplyResult result = graph.manager().applySchemaResult(
    "company_knowledge",
    schema,
    GraphManager.SchemaMode.ADDITIVE);

if (!result.isSuccess()) {
    throw new IllegalStateException(
        result.getErrorCode() + ": " + result.getError());
}
```

结构化结果包含：

- 是否成功；
- 已执行或确认的逻辑步骤；
- 后端兼容性警告；
- 稳定错误分类和错误说明；
- 执行耗时。

### 应用模式

| 模式 | 用途 | 行为边界 |
| --- | --- | --- |
| `VALIDATE_ONLY` | 发布前预检 | 校验定义，不执行 DDL |
| `CREATE_IF_ABSENT` | 初始化缺失结构 | 确保缺失定义被创建 |
| `ADDITIVE` | 兼容增量发布 | 创建缺失定义，并在后端支持时增加缺失属性 |

这三个模式都不应该被理解为自动执行任意结构迁移。属性类型变化、删除属性、删除节点/边类型和替换约束等操作，需要单独的迁移、审批和数据处理流程。

## Neo4j 与 Nebula 的差异

| 维度 | Neo4j | Nebula Graph |
| --- | --- | --- |
| 节点类型 | Label | Tag |
| 边类型 | Relationship Type | Edge Type |
| 属性结构 | 更偏动态属性模型 | Tag/Edge 需要显式属性定义 |
| 节点稳定 ID | SDK 创建唯一约束 | VID 参与实体身份 |
| 普通索引 | 支持节点和关系索引 | 支持 Tag 和 Edge 索引 |
| 唯一索引 | 节点可支持，关系唯一约束当前不属于 Portable 能力 | Portable Schema 不支持 |
| 端点标签约束 | 当前 Portable Schema 不强制 | 当前 Portable Schema 不强制 |
| 新增属性 | 数据写入时可自然出现，Schema 侧主要管理索引和约束 | 需要 ALTER Tag/Edge 并等待传播 |
| 索引就绪 | 受 Neo4j 索引状态影响 | 创建后需要重建并等待传播 |

公共 Schema 只承诺可移植意图。需要使用数据库专属约束、全文索引、向量索引或其他高级结构时，应在明确的后端迁移边界中使用原生能力，并把差异记录在 Schema 版本和发布流程中。

## 常见设计问题

### 是否应该把所有字段都声明为属性？

不一定。只有需要存储、过滤、展示或治理的数据才应进入图模型。大段正文通常保留在文档系统或对象存储中，图中保存摘要、引用和关联；需要全文检索的内容通常还会进入搜索或向量系统。

### 是否应该给每个属性建立索引？

不应该。索引会占用存储并增加写入成本，应根据实际查询起点和过滤条件设计。Nebula 的查询约束更依赖索引，因此需要在建模阶段提前规划。

### 是否应该为每个关系创建一个节点？

如果关系只连接两个实体并保存少量关系属性，优先使用边。如果“关系”本身拥有独立生命周期、需要连接更多实体或被其他关系引用，例如合同、会议、交易，通常应建模为节点。

### Schema 能否保证历史数据全部正确？

不能。Schema 描述期望结构，历史数据可能缺少必填属性、使用旧类型或包含未知标签。迁移前需要数据审计、回填和真实查询验证。

## 生产检查清单

- Schema 是否从业务查询和关联场景出发，而不是机械复制现有表；
- 节点和边类型是否使用稳定、可移植的机器名称；
- 节点是否有稳定业务 ID 和实体归一策略；
- 关系自身的信息是否放在边属性中；
- 常用过滤字段是否有合理索引；
- 是否区分了数据库约束、应用校验和展示元数据；
- 是否检查目标后端对唯一性、端点、多标签和索引的支持；
- 是否为 Schema 分配稳定 ID 和版本，并保留发布记录；
- 是否在真实 Neo4j/Nebula 环境验证 DDL、传播和查询；
- 是否通过独立流程处理类型变化、删除和其他破坏性变更。

Schema 定义完成后，继续阅读 [Schema 增量迁移](/zh/graph/schema-migration) 了解版本演进，或阅读 [Schema 反查](/zh/graph/schema-inspection) 获取数据库当前可观察结构。
