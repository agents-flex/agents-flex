# 实体归一

## 概述

假设从一部小说中抽取出“林默”和“林公子”，只说明这部小说里出现了两个名字；它们可能指向同一个人，也可能不是。

实体归一（Entity Resolution）就是根据名称、类型、别名、业务主键和上下文等信息，判断多个候选是否指向同一个业务实体，并把确认属于同一实体的候选映射到一个稳定的图节点 ID。

可以先把问题想成下面这样：

```text
第一段：林默加入青云宗。
第二段：林公子后来成为青云宗长老。

抽取结果：
  c1::m1  Character  林默
  c2::m1  Character  林公子

归一结果：
  c1::m1 ─┐
          ├── Character:person-001（一个图节点）
  c2::m1 ─┘
```

如果不归一，图数据库可能创建两个节点，人物的关系会被拆散；如果把两个同名但不同的人错误合并，关系又会传播到错误的对象。实体归一的作用，是在“避免重复节点”和“避免错误合并”之间做出可解释、可复用的身份判断。

它通常出现在知识抽取流程的这一步：

```text
文档 -> 分段 -> 抽取候选 -> Schema 校验 -> 实体归一 -> GraphMutation
```

实体归一只负责身份映射，不负责证明文本中的事实一定正确，也不会自动把结果写入图数据库。审核、入图和失败恢复分别由后续流程负责。

## 先区分四个对象

第一次接触这部分代码时，最容易混淆的是“文本里出现的名字”和“图数据库里的节点”。它们不是同一个对象：

| 对象 | 含义 | 生命周期 | 示例 |
| --- | --- | --- | --- |
| 文本提及（mention） | 某个 Chunk 中的一次局部出现 | 一次模型响应 | `c1::m1` |
| 候选实体（candidate） | 抽取器把提及整理成的结构化对象，包含名称、类型、别名、属性和证据 | 一次抽取结果 | `Character / 林默 / 林公子` |
| 业务实体 | 现实世界中被识别和维护的对象 | 跨文档、跨批次 | 林默这个人物 |
| 图节点（node） | 业务实体在图数据库中的表示，有稳定 `nodeId` | 长期存储 | `person-001` |

`candidateKey`（例如 `c1::m1`）只用于在当前流水线中引用候选，尤其是关系的端点；它不是最终的数据库 ID。`GraphEntityResolutionResult` 会返回两部分结果：去重后的 `GraphNode` 列表，以及每个候选键到最终 `nodeId` 的映射。

## 一个最小的可运行例子

下面直接构造两个候选，模拟它们来自不同 Chunk。第二个候选使用“林公子”作为名称，但与第一个候选共享同一个 `Character` 类型和别名，因此默认解析器会把它们归为一个节点。

```java
import com.agentsflex.graph.extractor.model.GraphEntityCandidate;
import com.agentsflex.graph.extractor.resolution.GraphEntityResolutionResult;
import com.agentsflex.graph.extractor.resolution.NameAliasGraphEntityResolver;

import java.util.Arrays;
import java.util.Collections;

GraphEntityCandidate first = new GraphEntityCandidate(
    "c1::m1", "林默", "Character",
    Collections.singletonList("林公子"),
    Collections.singletonMap("name", "林默"),
    null, 1D);

GraphEntityCandidate alias = new GraphEntityCandidate(
    "c2::m1", "林公子", "Character",
    Collections.emptyList(),
    Collections.singletonMap("role", "长老"),
    null, 1D);

GraphEntityResolutionResult result = new NameAliasGraphEntityResolver()
    .resolve(Arrays.asList(first, alias));

System.out.println(result.getNodes().size());
// 1
System.out.println(result.findNodeId(first.getCandidateKey()));
// 例如：character:...
System.out.println(result.findNodeId(alias.getCandidateKey()));
// 与上一个 nodeId 相同
```

这个例子里发生了三件事：

1. 解析器比较候选的类型、名称和强别名；
2. 两个候选被放进同一个实体集合，并合并缺失属性；
3. 解析器为集合生成一个稳定 `nodeId`，再把两个 `candidateKey` 都映射到这个 ID。

关系候选仍然使用 `candidateKey` 指向端点。后续的 `GraphCandidateMutationMapper` 会根据这个映射，把关系端点替换成真正的节点 ID。因此，关系不需要在抽取阶段猜测最终 ID。

## 默认归一规则

`NameAliasGraphEntityResolver` 是流水线的默认实现。它使用“节点类型 + 规范化名称或强别名”进行确定性归一：

```text
Character / 林默 + 林公子  -> 同一个节点
Character / 林默            -> Character 类型的节点
Organization / 林默         -> 另一个节点
```

默认实现会进行 Unicode NFKC、首尾空白、连续空白和大小写规范化；常见代词不会作为合并键。例如，即使模型把“他”放进两个候选的 `aliases`，也不会因此把两个角色合并。

属性处理也需要理解：同一实体的多个候选被合并时，默认策略保留先出现的属性值，只补充此前缺失的属性。它不会根据时间、来源可信度或业务优先级自动裁决冲突。

### 默认实现能解决什么

- 同一文档或同一批次中重复出现的实体；
- 明确的名称和稳定别名，例如“北京大学”和“北大”；
- 相同名称但节点类型不同的实体隔离；
- 重试时由类型和规范名称生成相同的确定性 ID。

### 默认实现不能替你决定什么

- “他”“她”“该公司”等指代词具体指向谁；
- 两个同名人物是否是同一个人；
- 人物改名、公司更名前后是否是同一实体；
- 翻译名、音译名或只有上下文才能判断的称呼；
- 抽取结果是否已经达到可以自动入图的业务可信度。

因此，“名称相同”是默认实现的匹配信号，不是普遍成立的事实。

### `nodeId` 应该从哪里来

`nodeId` 表示业务实体，不表示它在某个 Chunk 中出现的位置。长期图谱可以按业务情况选择：

- 业务系统主键或权威主数据 ID；
- 外部标准编号；
- 持久化实体注册表分配的 ID；
- 仅在名称足够稳定、歧义较少时，使用类型和规范名称生成的哈希。

不要把 `candidateKey`、`chunkId`、随机数或当前时间当作长期 ID，否则同一文档重试或后续增量导入时会不断创建重复节点。需要接入自己的 ID 策略时，可以实现 `GraphEntityIdGenerator`，再传给 `NameAliasGraphEntityResolver` 或 `RegistryGraphEntityResolver` 的构造方法。

## 把解析器装配到抽取流水线

使用 `new GraphExtractionPipeline(extractor)` 时，流水线已经自动使用 `NameAliasGraphEntityResolver`。如果要显式表达这个选择，或替换为自己的实现，可以把 Resolver 作为第四个组件传入：

```java
GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    documentSplitter,
    new SchemaGraphCandidateValidator(),
    new NameAliasGraphEntityResolver(),
    new GraphCandidateMutationMapper());

GraphExtractionResult extraction = pipeline.extract(document, schema);

// extraction.getEntityResolution() 中包含：
// 1. 去重后的 GraphNode；
// 2. candidateKey -> nodeId 的映射。
// 此时仍是内存结果，不会自动写入图数据库。
```

如果业务已有主数据、向量检索或人工审核规则，可以实现 `GraphEntityResolver`，在 `resolve(List<GraphEntityCandidate>)` 中使用这些信息。自定义实现仍应返回 `GraphEntityResolutionResult`，并保证每个已接收候选都有对应的节点 ID。

## 为什么需要实体注册表

默认解析器的 ID 由“类型 + 规范名称”生成。它适合开始验证，但长期知识库通常还需要跨批次复用身份：

```text
第一次导入：林默 -> person-001
后来导入：  林公子 -> 仍然应该是 person-001
```

名称可能变化，同名实体也可能被人工拆分。仅靠名称哈希无法回答这些问题，所以需要一个持久化的 `GraphEntityRegistry`，保存已经确认的“名称/别名 -> nodeId”关系。

注册表不是图节点的完整副本，而是跨文档、跨任务解析身份所需的索引。它至少应保存：

- `space`：知识库作用域；
- `type`：Schema 中的节点类型；
- `nodeId`：已确认的稳定节点 ID；
- `canonicalName`：规范名称；
- `aliases`：可以复用的稳定别名；
- 已确认的属性快照（可选）。

## 使用注册表复用历史身份

先用内存注册表演示 API。它只适合测试和示例，进程退出后数据会丢失。

```java
import com.agentsflex.graph.extractor.registry.GraphEntityRegistry;
import com.agentsflex.graph.extractor.registry.GraphRegisteredEntity;
import com.agentsflex.graph.extractor.registry.InMemoryGraphEntityRegistry;
import com.agentsflex.graph.extractor.resolution.RegistryGraphEntityResolver;

GraphEntityRegistry registry = new InMemoryGraphEntityRegistry();
registry.saveAll("novel_knowledge", Collections.singletonList(
    new GraphRegisteredEntity(
        "person-001", "Character", "林默",
        Collections.singletonList("林公子"),
        Collections.singletonMap("age", 30L))));

GraphExtractionPipeline pipeline = new GraphExtractionPipeline(
    extractor,
    documentSplitter,
    new SchemaGraphCandidateValidator(),
    new RegistryGraphEntityResolver("novel_knowledge", registry),
    new GraphCandidateMutationMapper());
```

在这条流水线中，解析器先在当前批次内按名称和别名聚类，再按 `space`、节点类型和名称查询注册表：

- 没有历史命中：生成新的 `nodeId`；
- 只命中一个历史实体：复用它的 `nodeId`，并优先使用已注册属性；
- 命中多个不同 `nodeId`：抛出歧义错误，交给审核，不能随机选择。

注意，下面两件事不是同一件事：

```text
把 registry 传给 GraphIngestionService
  -> 图写成功后保存本次注册结果

把 registry 传给 RegistryGraphEntityResolver
  -> 抽取阶段就能复用已有 nodeId
```

要跨批次复用身份，必须在 Pipeline 的 Resolver 中使用同一个持久化注册表；只在入图服务中保存注册结果，无法改变本次抽取已经产生的映射。

## 什么时候需要人工审核

以下情况不应静默自动合并：

- 同一候选的名称和别名分别命中多个历史实体；
- 两个同名实体的属性、来源或图谱邻居互相矛盾；
- 新名称可能是改名，也可能是另一个实体；
- 只有上下文、时间或业务主键才能判断身份。

审核界面至少应展示候选名称、类型、别名、原文证据、当前文档上下文、历史实体属性和来源，并提供“复用已有实体”“新建实体”“暂不决定”等选项。审核结果应保存为注册表中的别名或身份映射，使下一批文档可以复用，而不是只修改当前一次 Mutation。

## 注册、写图与合并拆分的边界

推荐在 Graph Mutation 写入成功后再保存注册记录，避免注册表指向尚未存在的节点：

```text
抽取/审核 -> 生成 Mutation -> 图写入成功 -> 保存实体注册 -> 保存文档和来源状态
```

图数据库和注册表通常不在同一个事务中，因此仍可能出现“图写入成功但注册表保存失败”。生产系统应保存操作状态、支持重试，并定期对账。

如果发现历史上已经创建了两个重复节点，也不能只把注册表的指向改掉。图中可能还有关系、来源事实、外部系统引用和历史文档状态。实体合并或拆分应作为独立、可审核、可回滚的数据治理操作，规划属性合并、关系迁移、别名更新和审计记录；它不是默认 Resolver 的自动职责。

## 常见问题

### 同名同类型一定是同一实体吗？

不一定。默认解析器只能做保守的名称/别名归一；生产场景应结合业务主键、上下文、来源或人工审核。

### `candidateKey` 可以直接当图节点 ID 吗？

不可以。`candidateKey` 通常带有 Chunk 标识，只在一次抽取流程内有效。长期节点应使用业务主键、注册表 ID 或确定性 ID 生成器。

### 可以把“他”加入别名帮助归一吗？

可以保留在候选或证据中供审核，但默认解析器不会把常见代词当作跨候选合并键。代词消解需要上下文规则、模型判断或人工确认。

### 修改规范名称会改变节点 ID 吗？

默认哈希策略可能改变。长期图谱应使用业务主键或持久化注册表，让名称变化只更新名称和别名，保持 `nodeId` 不变。

### 注册表使用内存实现可以吗？

可以用于测试、示例和单次进程内任务；跨天、跨批次或多实例部署必须实现持久化的 `GraphEntityRegistry`，并提供唯一约束、并发冲突处理和审计。

## 生产检查清单

- 是否先向读者和调用方区分了 mention、candidate、业务实体和 GraphNode？
- 是否明确 `candidateKey` 只是局部引用，而不是长期节点 ID？
- 是否根据业务风险选择默认 Resolver、注册表 Resolver 或人工审核？
- 是否把 `space` 和节点类型纳入注册表查询及唯一约束？
- 同名多命中时是否进入审核，而不是随机选一个？
- 属性冲突是否有明确的来源优先级或合并策略？
- 注册记录是否在图写入成功后提交，并能处理两边不一致？
- 合并、拆分和别名修改是否有审计、回滚和来源迁移方案？

完成身份判断后，可以继续阅读[知识入图](/zh/graph/extractor/ingestion)，了解如何把审核后的 Mutation 写入目标 Graph Space。
