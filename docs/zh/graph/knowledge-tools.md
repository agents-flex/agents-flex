# 知识图谱检索 Tools

`agents-flex-graph-tools` 用于把知识图谱安全地暴露给 Agent。它不是一个“把数据库查询接口直接交给大模型”的适配层，而是一组带有 Schema 边界、只读约束、分页和结果脱敏能力的 Tool。

这组 Tool 适合这样的交互：

```text
用户问题
    ↓
Agent 发现可用知识源和类型
    ↓
Agent 只展开本次问题需要的 Schema
    ↓
Agent 生成 Portable MATCH 查询
    ↓
Tool 校验查询并访问图数据库
    ↓
Agent 根据结构化结果回答
```

## 这解决什么问题

传统的图数据库问答通常有两种做法：

1. 把完整 Schema、数据库名称和查询语法全部放进系统提示词；
2. 直接让模型生成 Cypher、nGQL 或其他后端方言，再把语句交给数据库执行。

这两种做法都存在明显问题：完整 Schema 会占用大量上下文，数据库结构变化后提示词容易过期；直接执行模型生成的原生语句则可能访问未授权的类型、读取不应公开的属性，甚至执行写操作。

知识图谱 Tools 采用渐进式披露（progressive disclosure）：

- 第一阶段只告诉模型有哪些知识源、节点类型和关系类型；
- 第二阶段只展开当前问题相关类型的属性；
- 第三阶段只接受经过解析和 Schema 校验的只读 Portable Query。

因此，模型可以在需要时获取结构信息，但不会默认看到整个数据库，也不能自行选择物理连接、Space 或后端方言。

## 适用场景

### 企业知识问答

例如：

- “Alice 目前在哪家公司工作，职位是什么？”
- “哪些员工同时参与了支付系统和风控系统？”
- “这份合同提到了哪些组织和项目？”

模型先发现 `Person`、`Company`、`WORKS_AT` 等类型，再查询具体人物或关系。

### 客服和运营助手

可以把订单、客户、产品、工单和服务关系注册为不同知识源，按租户或业务域只暴露必要的逻辑入口。Agent 不需要知道底层使用的是 Neo4j 还是 Nebula。

### 合规和风控检索

知识源可以只暴露经过脱敏的节点和属性，并通过 `maxHops`、`maxPageSize`、超时和只读执行选项限制查询范围。结果中额外的后端属性也会再次按公开 Schema 过滤。

### 文档和知识抽取后的查询

Graph Extractor 负责把文档转换为实体和关系，Knowledge Tools 负责让 Agent 查询已经发布的知识。两者可以使用同一份业务 Schema，但职责不同：

| 能力 | 主要职责 |
| --- | --- |
| Graph Extractor | 从文本发现候选实体和关系，并生成待审核的图变更 |
| Graph Schema | 描述允许的节点、边和属性 |
| Knowledge Tools | 让 Agent 按 Schema 查询已发布的图数据 |
| GraphStore | 管理连接、事务和后端执行 |

Knowledge Tools 不负责抽取、写入、审核或修改 Schema。

## 核心概念

### 知识源

`KnowledgeGraphSource` 是模型可见的逻辑入口。它把以下信息绑定在一起：

- 逻辑名称，例如 `company_knowledge`；
- 面向模型的描述；
- 实际查询执行器；
- 固定的 Graph Space 或 Neo4j database；
- 模型允许看到的 Schema；
- 分页、跳数、超时和结果数量上限。

知识源不是数据库连接枚举器。应用必须显式注册知识源，模型只能从注册列表中选择精确的逻辑名称。

### 暴露 Schema

传给 `KnowledgeGraphSource` 的 Schema 是“模型允许使用的 Schema”，也是查询校验和结果过滤的允许列表。它不一定等于数据库中反查到的完整 Schema。

例如，数据库中可能有 `Person.secretNote`，但如果这个属性不应该出现在 Agent 的回答中，就不要把它加入暴露 Schema。这样模型既不能在查询中引用它，完整节点投影返回时也不会输出它。

### Portable Query

Tool 接受 Graph SDK 的公共查询模型，当前入口是一个线性的 `MATCH` 查询。它不是 Cypher 或 nGQL 字符串转发器，而是先由 `GraphQueryParser` 解析，再由 `GraphSchemaQueryValidator` 校验，最后交给 `GraphQueryExecutor` 执行。

查询中的动态值使用命名参数，例如 `:personName`，通过 `parameters` 对象传递。不要把用户输入直接拼接进查询字符串。

### 软失败

工具错误会转换为模型可以理解和修正的文本：

```text
Error: QUERY_NOT_ALLOWED: Property 'salary' is not exposed for alias 'p'
```

后端异常的详细堆栈只写入服务日志，不直接返回给模型。这样模型可以知道请求被拒绝的原因，但不会看到连接地址、物理查询或内部诊断信息。

## 添加 Maven 依赖

只使用知识图谱 Tool 时添加：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-tools</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

应用还需要引入实际使用的 Graph 后端，例如：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-neo4j</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

如果模型使用 DeepSeek，还需要对应的聊天模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-chat-deepseek</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 定义 Schema

下面定义一个最小企业知识图谱。Schema 中的机器名称用于查询，展示名称和描述用于文档、工具输出和模型理解。

```java
import com.agentsflex.graph.schema.GraphElementMetadata;
import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;

GraphSchema schema = GraphSchema.builder()
    .nodeType(GraphSchema.NodeType.of(
        "Person",
        new GraphElementMetadata("人物", "企业中的员工或合作人员"),
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true),
        new GraphSchema.Property(
            "email",
            GraphSchema.PropertyType.STRING,
            false,
            new GraphPropertyMetadata("邮箱", "联系邮箱", null, null))))
    .nodeType(GraphSchema.NodeType.of(
        "Company",
        new GraphElementMetadata("公司", "企业或组织"),
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, true)))
    .edgeType(GraphSchema.EdgeType.of(
        "WORKS_AT",
        "Person",
        "Company",
        new GraphElementMetadata("任职", "人物在公司中的任职关系"),
        new GraphSchema.Property(
            "title",
            GraphSchema.PropertyType.STRING,
            false,
            new GraphPropertyMetadata("职位", "任职职位名称", null, null))))
    .build();
```

### Schema 设计建议

- 使用稳定的英文机器名称，展示名称和描述使用业务语言；
- 只把确实允许 Agent 查询的属性放入暴露 Schema；
- 把关系自身的信息放在边属性中，例如 `WORKS_AT.title`；
- 不要为了方便查询，把数据库中的所有标签和属性都暴露出去；
- Schema 元数据不能替代数据库权限，数据库账号仍然应该使用最小权限；
- Schema 变化后，应同步更新数据写入、抽取、查询和测试。

## 创建知识源和 Tool

`KnowledgeGraphSource` 不负责管理 GraphStore 的生命周期。GraphStore 应由应用容器长期复用，并在应用关闭时释放。

```java
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.graph.query.GraphQueryExecutor;
import com.agentsflex.graph.tools.KnowledgeGraphSource;
import com.agentsflex.graph.tools.KnowledgeGraphTools;

GraphQueryExecutor executor = graphStore.query();

KnowledgeGraphSource source = KnowledgeGraphSource
    .builder("company_knowledge", executor, schema)
    .description("企业中的人物、公司和任职关系")
    .space("company_graph")
    .defaultPageSize(20)
    .maxPageSize(100)
    .maxHops(3)
    .timeoutMillis(10_000L)
    .fetchSize(100)
    .build();

List<Tool> tools = KnowledgeGraphTools.builder()
    .addSource(source)
    .buildTools();

prompt.addTools(tools);
```

`buildTools()` 返回三个 Tool，并且顺序固定为：

1. `listKnowledgeGraphTypes`；
2. `describeKnowledgeGraphTypes`；
3. `queryKnowledgeGraph`。

也可以注册多个知识源：

```java
List<Tool> tools = KnowledgeGraphTools.builder()
    .addSource(companySource)
    .addSource(productSource)
    .addSource(documentSource)
    .maxSchemaElements(6)
    .buildTools();
```

第一阶段的工具描述会包含这些逻辑知识源名称和描述，但不会暴露连接信息、账号、密码或物理 Space 列表。

## 三阶段渐进式披露

### 第一步：发现类型摘要

模型调用：

```json
{
  "knowledgeSourceName": "company_knowledge"
}
```

返回结果类似：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "nodeTypes": [
    {"name": "Person", "displayName": "人物", "description": "企业中的员工或合作人员"},
    {"name": "Company", "displayName": "公司", "description": "企业或组织"}
  ],
  "edgeTypes": [
    {
      "name": "WORKS_AT",
      "sourceLabel": "Person",
      "targetLabel": "Company",
      "displayName": "任职",
      "description": "人物在公司中的任职关系"
    }
  ],
  "nextStep": "Call describeKnowledgeGraphTypes for only the node and edge types needed by the query."
}
```

摘要阶段不返回属性。模型只能根据节点、边和端点判断下一步需要展开哪些类型。

### 第二步：按需展开属性

对于“查询 Alice 的公司和职位”这个问题，模型只需要展开 `Person`、`Company` 和 `WORKS_AT`：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "nodeLabels": ["Person", "Company"],
  "edgeTypes": ["WORKS_AT"]
}
```

返回结果会包含精确的属性名、类型、必填状态、说明和枚举值：

```json
{
  "nodeTypes": [
    {
      "name": "Person",
      "properties": [
        {"name": "name", "type": "STRING", "required": true},
        {"name": "email", "type": "STRING", "required": false}
      ]
    },
    {
      "name": "Company",
      "properties": [
        {"name": "name", "type": "STRING", "required": true}
      ]
    }
  ],
  "edgeTypes": [
    {
      "name": "WORKS_AT",
      "sourceLabel": "Person",
      "targetLabel": "Company",
      "properties": [
        {"name": "title", "type": "STRING", "required": false}
      ]
    }
  ],
  "nextStep": "Build a portable MATCH query using only the returned names, then call queryKnowledgeGraph."
}
```

一次详情请求默认最多展开 10 个节点或边类型，可通过 `maxSchemaElements(...)` 调整。建议按问题最小化请求，不要每次都展开整个图谱。

### 第三步：执行受限查询

模型可以根据第二阶段返回的名称生成 Portable Query：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "expression": "MATCH (p:Person)-[w:WORKS_AT]->(c:Company) WHERE p.name = :personName RETURN c.name AS company, w.title AS title",
  "parameters": {
    "personName": "Alice"
  },
  "pageSize": 20
}
```

Tool 在真正访问后端前会依次完成：

1. 解析查询语法；
2. 确认是单个线性 `MATCH`；
3. 检查节点标签、边类型、端点和方向；
4. 检查过滤、投影、排序和分组中引用的属性；
5. 检查最大跳数和分页参数；
6. 强制使用固定 Space、超时、最大记录数和 `readOnly=true`；
7. 格式化结果，并按暴露 Schema 过滤实体属性。

## 完整的 Tool Calling 示例

下面示例使用 `MemoryPrompt` 保存多轮消息。它适用于 DeepSeek、OpenAI 兼容模型以及其他支持 Tool Calling 的 `ChatModel`。

```java
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.MemoryPrompt;

import java.util.List;

ChatModel chatModel = createChatModel();
List<Tool> knowledgeTools = KnowledgeGraphTools.builder()
    .addSource(companySource)
    .buildTools();

MemoryPrompt prompt = new MemoryPrompt();
prompt.setSystemMessage(
    "你是知识图谱检索助手。必须先调用 listKnowledgeGraphTypes，"
        + "再调用 describeKnowledgeGraphTypes，最后调用 queryKnowledgeGraph。"
        + "不得猜测知识源、标签、边类型或属性，不得使用原生 Cypher/nGQL。"
        + "如果工具返回错误，应根据错误修正请求；如果 Schema 不支持某字段，不要编造答案。"
);
prompt.addTools(knowledgeTools);
prompt.addUserMessage("Alice 目前在哪家公司工作，职位是什么？");

for (int turn = 0; turn < 6; turn++) {
    AiMessageResponse response = chatModel.chat(prompt);
    if (!response.hasToolCalls()) {
        System.out.println(response.getMessage().getContent());
        break;
    }

    // 先保存模型发出的 AiMessage，其中包含 ToolCall ID。
    prompt.addMessage(response.getMessage());

    // 执行工具并生成带 ToolCall ID 的 ToolMessage。
    List<ToolMessage> toolMessages = response.executeToolCallsAndGetToolMessages();
    prompt.addMessages(toolMessages);
}
```

一个真实模型通常会产生类似的调用链：

```text
第 1 轮  listKnowledgeGraphTypes(company_knowledge)
第 2 轮  describeKnowledgeGraphTypes(Person, Company, WORKS_AT)
第 3 轮  queryKnowledgeGraph(MATCH ... WHERE p.name = :personName ...)
第 4 轮  最终自然语言回答
```

不要只把工具结果拼成新的用户消息。必须把原始 `AiMessage` 和带有对应 `toolCallId` 的 `ToolMessage` 放回 Prompt，模型才能正确理解哪一次调用得到了哪一个结果。

### DeepSeek 配置示例

```java
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;

DeepseekConfig config = new DeepseekConfig();
config.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
config.setModel("deepseek-chat");
config.setLogEnabled(false);

ChatModel chatModel = new DeepseekChatModel(config);
```

API Key 应由环境变量、密钥管理系统或运行平台注入，不要写入源码、文档、测试日志或 Tool 描述。

## 查询约束

`queryKnowledgeGraph` 当前只接受可以解析为 `TraversalQuery` 的单个线性查询。

### 允许的写法

```text
MATCH (p:Person)-[w:WORKS_AT]->(c:Company)
WHERE p.name = :personName
RETURN c.name AS company, w.title AS title
```

支持的查询能力取决于 Graph SDK 的 Portable Query 模型，常见部分包括：

- 一个起点节点和线性遍历；
- 明确的节点 label；
- 明确的边类型和方向；
- `WHERE` 过滤；
- `RETURN` 投影；
- `GROUP BY` 和 `ORDER BY`；
- 命名参数；
- 由 Tool 参数控制的分页。

### 明确禁止的写法

```text
// 原生后端方言
CALL db.labels()

// 未标注节点
MATCH (p) RETURN p

// 未标注边
MATCH (p:Person)-[r]->(c:Company) RETURN p, r, c

// 写操作
MATCH (p:Person) DELETE p

// 联合查询和可选匹配
MATCH (p:Person) RETURN p
UNION
MATCH (c:Company) RETURN c
```

禁止项不是只写在提示词里。查询解析器和 Schema 校验器会在访问数据库前拒绝这些请求。

### 参数化查询

推荐：

```json
{
  "expression": "MATCH (p:Person) WHERE p.name = :name RETURN p.email AS email",
  "parameters": {"name": "Alice"}
}
```

不推荐把用户输入拼接到字符串中：

```java
String expression = "MATCH (p:Person) WHERE p.name = '" + userInput + "' RETURN p";
```

参数化可以避免查询语法被用户输入破坏，也让后端适配器更容易复用查询计划。

## 结果格式

成功结果包含逻辑知识源、记录和分页元数据：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "records": [
    {
      "company": "Acme",
      "title": "Staff Engineer"
    }
  ],
  "metadata": {
    "recordCount": 1,
    "truncated": false,
    "executionTimeMillis": 12,
    "hasNext": false,
    "nextCursor": ""
  }
}
```

如果投影返回完整节点或边，Tool 会转换为稳定结构：

```json
{
  "kind": "node",
  "id": "person-1",
  "labels": ["Person"],
  "properties": {
    "name": "Alice",
    "email": "alice@example.com"
  }
}
```

节点和边中的额外标签、额外属性不会因为数据库实际存在就自动输出。只有公开 Schema 中的标签和属性才会进入结果。

## 分页

查询文本中不要写 `SKIP` 和 `LIMIT`，由 Tool 参数统一控制：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "expression": "MATCH (p:Person) RETURN p.name AS name ORDER BY p.name",
  "parameters": {},
  "pageSize": 20
}
```

如果结果中有下一页：

```json
{
  "hasNext": true,
  "nextCursor": "offset:20:..."
}
```

下一次请求必须保持完全相同的 `knowledgeSourceName`、`expression` 和 `parameters`，只把 `nextCursor` 原样传回：

```json
{
  "knowledgeSourceName": "company_knowledge",
  "expression": "MATCH (p:Person) RETURN p.name AS name ORDER BY p.name",
  "parameters": {},
  "pageSize": 20,
  "cursor": "offset:20:..."
}
```

游标绑定查询指纹，不能跨查询复用，也不能由应用自行解析或拼接。生产分页应配合稳定排序，否则数据变化期间可能出现重复或遗漏。

## 错误处理和模型重试

工具采用稳定的软错误格式。常见错误包括：

| 错误码 | 常见原因 | 模型或应用的处理方式 |
| --- | --- | --- |
| `UNKNOWN_KNOWLEDGE_SOURCE` | 逻辑知识源名称不存在 | 重新调用第一阶段 Tool，使用精确名称 |
| `UNKNOWN_GRAPH_TYPE` | 节点或边类型不在 Schema | 不要猜测名称，回到摘要阶段确认 |
| `INVALID_ARGUMENT` | 参数为空、页大小越界等 | 修正参数后重试 |
| `INVALID_QUERY` | 查询无法解析 | 重新生成 Portable Query |
| `QUERY_NOT_ALLOWED` | 未公开属性、未标注节点、方向或跳数不合法 | 根据错误消息修正，不要绕过校验 |
| `UNSUPPORTED_QUERY` | 使用了不支持的查询结构 | 改成单个线性 `MATCH` |
| `QUERY_TIMEOUT` | 后端执行超时 | 缩小范围、减少跳数或分页查询 |
| `QUERY_FAILED` | 后端或执行器异常 | 由应用记录并决定是否有限重试 |

推荐在系统提示词中明确：

```text
如果 queryKnowledgeGraph 返回 QUERY_NOT_ALLOWED，不要猜测替代属性名并循环重试。
应根据错误说明 Schema 不支持该字段，并向用户解释限制。

Schema 返回的名称是唯一可信来源。不要根据业务常识补全标签、边类型、属性或替代字段。
```

### 一个错误恢复示例

用户要求查询 `salary`，但 `Person` Schema 只有 `name` 和 `email`：

```text
模型调用 queryKnowledgeGraph
    ↓
Error: QUERY_NOT_ALLOWED: Property 'salary' is not exposed for alias 'p'
    ↓
模型说明 Schema 不支持 salary
    ↓
不提供猜测的薪资值，也不无限重试
```

如果业务明确要求工具返回校验错误，也可以让模型继续完成第三阶段调用；否则模型在第二阶段发现字段不存在后直接解释限制通常更节省一次数据库请求。

## 多知识源和租户隔离

可以通过多个逻辑知识源复用同一套 Tool：

```java
KnowledgeGraphSource hrSource = KnowledgeGraphSource
    .builder("hr_knowledge", hrExecutor, hrSchema)
    .description("人事和组织关系")
    .space("tenant_hr")
    .maxHops(2)
    .build();

KnowledgeGraphSource productSource = KnowledgeGraphSource
    .builder("product_knowledge", productExecutor, productSchema)
    .description("产品、版本和依赖关系")
    .space("tenant_product")
    .maxHops(3)
    .build();

List<Tool> tools = KnowledgeGraphTools.builder()
    .addSource(hrSource)
    .addSource(productSource)
    .buildTools();
```

租户隔离不能只依靠模型不选择错误知识源。应用应在创建 `KnowledgeGraphSource` 时根据当前用户或租户生成允许列表，必要时为每个租户创建独立的 Tool 集合和数据库凭据。

## 安全边界

Knowledge Tools 提供的是应用层安全边界，不是数据库授权替代品。

### Tool 层做什么

- 只暴露显式注册的逻辑知识源；
- 不接受原生查询和写操作；
- 校验节点、边、属性、端点、方向和跳数；
- 固定 Space、超时、最大记录数和只读选项；
- 过滤完整实体和路径中的未公开属性；
- 隐藏后端查询文本和详细异常。

### 数据库层仍然必须做什么

- 使用只读账号；
- 为不同租户和业务域设置数据库权限；
- 使用 TLS 和密钥管理系统；
- 对高成本查询设置数据库级超时和资源限制；
- 记录审计事件和调用者身份；
- 对敏感字段执行脱敏或物理隔离。

即使 Tool 已经设置 `readOnly=true`，也不应该让它使用拥有写权限的数据库账号。

## 当前边界和设计取舍

### 调用顺序主要由 Agent 提示词引导

当前三个 Tool 的顺序和 `nextStep` 字段会引导模型按三阶段执行，但 `queryKnowledgeGraph` 本身不会读取之前是否调用过 `listKnowledgeGraphTypes`。它会独立完成自己的查询解析和 Schema 校验。

这意味着：

- 正确的 Agent Prompt 应明确三阶段调用顺序；
- 工具层仍然能阻止越权查询，即使模型直接调用第三阶段；
- 如果业务必须强制顺序，需要在应用层增加会话状态、一次性 disclosure token 或 Tool 调用中间件。

### 完整节点投影的最小化程度

如果查询返回完整 `GraphNode`，当前格式化器会返回该节点在公开 Schema 中允许的属性，而不是重新推断模型实际写出的 `RETURN` 字段。需要严格字段级最小化时，应优先让模型使用显式属性投影：

```text
RETURN p.name AS name, p.email AS email
```

而不是：

```text
RETURN p
```

### 后端能力差异

Portable Query 解决的是公共语义，不代表所有后端拥有完全相同的性能和索引行为。上线前仍应在目标 Neo4j、Nebula 或其他适配器上验证：

- 查询语法编译；
- 参数类型；
- 排序和分页稳定性；
- 关系端点和方向语义；
- 超时和只读选项；
- 大结果集的物化成本。

## 测试建议

### 不连接数据库的契约测试

使用内存 `GraphQueryExecutor` 测试：

- 三个 Tool 是否按顺序生成；
- 摘要阶段是否隐藏属性；
- 详情阶段是否只返回请求类型；
- 未知标签、边和属性是否在执行前被拒绝；
- 结果中的额外属性是否被过滤；
- 分页游标是否绑定原始查询；
- ToolCall 和 ToolMessage 是否正确往返。

### 真实模型集成测试

在显式环境变量开启时再调用真实模型，不要把 API Key 写进测试源码：

```java
Assume.assumeTrue("true".equalsIgnoreCase(System.getenv("GRAPH_LLM_INTEGRATION")));
String apiKey = System.getenv("DEEPSEEK_API_KEY");
Assume.assumeTrue(apiKey != null && !apiKey.trim().isEmpty());
```

建议覆盖：

- 正常三阶段调用；
- 用户要求使用原生 Cypher 或跳过 Schema；
- 用户要求不存在的属性；
- 工具返回 `QUERY_NOT_ALLOWED` 后的错误恢复；
- 多知识源选择；
- 长结果分页；
- 提示注入文本不会改变 Tool 约束。

真实模型测试应使用固定的内存或隔离图数据，避免把测试写入生产数据库。

## 生产检查清单

- [ ] 暴露 Schema 只包含模型真正需要的节点、边和属性；
- [ ] 知识源名称是稳定的业务逻辑名称，不是数据库内部名称；
- [ ] GraphStore 在应用生命周期内复用，不在每次 Tool 调用时新建；
- [ ] 数据库账号为只读并按租户隔离；
- [ ] `maxHops`、`maxPageSize`、超时和 fetch size 已按业务设置；
- [ ] Agent 系统提示词明确三阶段调用顺序和错误处理方式；
- [ ] 应用正确回传 `AiMessage` 和 `ToolMessage`，保留 ToolCall ID；
- [ ] 对结果使用显式属性投影，避免不必要的完整实体投影；
- [ ] 已测试未知属性、原生查询、写操作、分页和提示注入；
- [ ] API Key、数据库密码和物理连接信息没有进入 Prompt、Tool 描述或错误响应；
- [ ] 已在目标图数据库上验证 Portable Query 的编译、性能和分页行为。

## 相关文档

1. [Graph 概览](./overview)
2. [快速开始](./getting-started)
3. [Schema 定义](./schema)
4. [遍历查询](./traversal-query)
5. [查询结果](./query-result)
6. [分页](./pagination)
7. [Neo4j 适配器](./neo4j)
8. [Nebula 适配器](./nebula)
9. [大模型知识提取](./extractor/model-integration)
