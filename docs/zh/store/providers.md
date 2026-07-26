<div v-pre>

# 存储选型与能力矩阵

## 概述

Agents-Flex 提供统一 Store API，但选择后端仍然是架构决策。数据库产品的向量规模、过滤能力、部署方式和运维
模型不同，Agents-Flex 对各产品的适配完整度也不同。

正确的选型顺序不是先比较“哪个数据库最快”，而是：

1. 明确数据规模、查询类型、隔离要求和运维边界；
2. 排除不满足硬性条件的实现；
3. 使用生产数据和生产查询做基准测试；
4. 验证故障、升级、备份和模型迁移；
5. 再比较成本和团队维护能力。

本页矩阵描述的是仓库当前 Store 适配器，而不是数据库产品理论上支持的全部功能。

## 使用场景

### 已有关系数据库，希望降低系统复杂度

优先评估 [Pgvector](./pgvector)。向量、业务字段和 JSONB metadata 可以使用现有数据库的事务、权限、备份和
监控体系。需要确认数据规模、HNSW 索引和连接资源是否适合现有集群。

已经使用 MariaDB 11.7+ 的团队可以评估 [MariaDB](./mariadb)。它使用数据库原生 VECTOR 和 JSON 能力，不需要
安装扩展；旧版 MariaDB 和 MySQL 不能直接替代。

### 已有 Redis Stack，重视低延迟

优先评估 [Redis](./redis)。它适合中等规模、在线低延迟检索和已有 Redis 运维体系的团队。必须使用包含
RedisJSON 和 RediSearch 的 Redis Stack，并评估向量索引的内存成本。

### 大规模专用向量检索

优先评估 [Milvus](./milvus) 或 [Qdrant](./qdrant)。两者都提供专用向量能力，但部署架构、过滤模型、索引参数
和团队运维经验会影响最终选择。

### 已有搜索平台，需要结合搜索生态

使用 Elasticsearch 的团队可以评估 [Elasticsearch](./elasticsearch)；使用 OpenSearch 的团队可以评估
[OpenSearch](./opensearch)。需要注意当前两个 Agents-Flex 适配器的条件能力并不相同。

### 本地原型和轻量知识库

[Chroma](./chroma) 易于本地启动，并支持结构化 metadata 过滤，适合原型验证和轻量知识库。

### 希望由云厂商托管

[阿里云 DashVector](./aliyun) 和[腾讯云向量数据库](./qcloud)避免自建集群，但需要评估网络、配额、费用、凭证、
区域和当前 Store 适配能力。它们不能在开发机本地安装，联调需要云端测试实例。

## 后端总览

| Store 模块 | 数据库 | 典型定位 | 运行方式 |
| --- | --- | --- | --- |
| `agents-flex-store-redis` | Redis Stack | 低延迟、复用 Redis 技术栈 | 自建或托管 Redis Stack |
| `agents-flex-store-milvus` | Milvus / Zilliz Cloud | 大规模专用向量数据库 | 自建集群或云服务 |
| `agents-flex-store-pgvector` | PostgreSQL + pgvector | 关系数据与向量统一运维 | PostgreSQL 扩展 |
| `agents-flex-store-mariadb` | MariaDB 11.7+ | 关系数据与向量统一运维 | MariaDB 原生 VECTOR |
| `agents-flex-store-elasticsearch` | Elasticsearch | 搜索平台中的向量召回 | 自建或 Elastic Cloud |
| `agents-flex-store-opensearch` | OpenSearch | OpenSearch k-NN | 自建或 Amazon OpenSearch |
| `agents-flex-store-chroma` | Chroma | 原型和轻量知识库 | 独立 HTTP 服务 |
| `agents-flex-store-qdrant` | Qdrant | 专用向量检索与 payload 过滤 | 自建或 Qdrant Cloud |
| `agents-flex-store-aliyun` | DashVector | 阿里云托管向量服务 | 云服务 |
| `agents-flex-store-qcloud` | 腾讯云向量数据库 | 腾讯云托管向量服务 | 云服务 |

## 当前适配能力

### 路由与条件

| Store | 数据空间路由 | 自动创建 | 当前条件过滤 |
| --- | --- | --- | --- |
| [Redis](./redis) | `collectionName` | 自动建索引 | RediSearch 表达式适配 |
| [Milvus](./milvus) | `collectionName`、Partition | 自动建 Collection/索引 | Milvus 标量表达式适配 |
| [Pgvector](./pgvector) | `collectionName` 对应表 | 自动建表可配置 | 参数化 SQL 与 JSONB 条件 |
| [MariaDB](./mariadb) | `collectionName` 对应表 | 自动建表和 VECTOR INDEX 可配置 | 参数化 SQL 与 JSON_VALUE 条件 |
| [Elasticsearch](./elasticsearch) | `indexName` | 自动建 Index | query string 适配 |
| [OpenSearch](./opensearch) | `indexName` | 自动建 Index | 当前搜索未接入 `Condition` |
| [Chroma](./chroma) | `collectionName` | 可配置 | Chroma `where` JSON |
| [Qdrant](./qdrant) | `collectionName` | 可配置 | Qdrant 原生 `Filter` |
| [阿里云 DashVector](./aliyun) | `collectionName`、Partition | 控制台预建 | DashVector 专属 filter 适配 |
| [腾讯云向量数据库](./qcloud) | `collectionName` | 控制台或 SDK 预建 | 官方 SDK；支持条件与纯过滤查询 |

::: warning 多租户安全
OpenSearch 当前不能依赖 `SearchWrapper.condition` 做共享集合的租户隔离。需要严格隔离时使用独立数据空间，
或者先完善适配器并完成安全测试。
:::

### 连接与本地开发

| Store | 应用连接 | 本地开发环境 |
| --- | --- | --- |
| Redis | Redis URI | Redis Stack Docker |
| Milvus | Milvus Java SDK | 官方 Standalone Docker 脚本 |
| Pgvector | JDBC | `pgvector/pgvector` Docker |
| MariaDB | JDBC | `mariadb:11.7` Docker |
| Elasticsearch | Elasticsearch Java Client | 官方单节点 Docker |
| OpenSearch | OpenSearch Java Client | 官方单节点 Docker |
| Chroma | REST `/api/v2` | Chroma Docker |
| Qdrant | gRPC 6334 | Qdrant Docker，同时开放 HTTP 6333 |
| 阿里云 DashVector | 官方 Java SDK | 不能本地安装，连接云端测试实例 |
| 腾讯云向量数据库 | 官方 Java SDK | 不能本地安装，连接云端测试实例 |

具体安装命令、端口、认证和清理方式均在各 Store 独立文档中提供。

## 选型维度

### 数据规模和增长速度

先测算向量数量、维度、metadata 大小、每天新增量和保留周期。向量占用不仅是 `数量 × 维度 × 4 bytes`，还要
计算索引、payload、复制、WAL、缓存和临时构建空间。

规模较小时，复用 PostgreSQL 或 Redis 可能比新增专用集群更合理。数据快速增长、需要复杂索引调优或分布式扩展
时，再评估 Milvus、Qdrant 或托管专用服务。

### 查询模式

列出真实查询，而不是只测试一条 topK：

- 纯向量召回；
- 向量加 tenant、分类和状态过滤；
- IN、NOT IN、BETWEEN、NULL 和嵌套 AND/OR/NOT；
- 是否需要纯 metadata 查询；
- 是否需要返回向量或指定字段；
- topK、并发量、P95/P99 延迟和召回率目标。

如果过滤是硬性要求，应先排除当前未接入 `Condition` 的 Store。

### 数据一致性和更新

确认业务是否要求：

- 写入返回后立即可搜索；
- 批量写入全部成功或全部失败；
- 更新不存在 ID 时必须报错；
- 文档内容、向量和 metadata 原子更新；
- 删除后立即不可见。

统一 `StoreResult` 不会自动抹平数据库的一致性模型和 upsert 语义。

### 多租户隔离

独立 Collection/Index 隔离更清晰，但租户数量过多会增加数据库元数据和索引管理成本。共享集合要求每条访问路径
强制追加 tenant 条件，并且 Store 必须真正支持服务端过滤。

对于权限敏感数据，不要仅凭一条成功的条件查询就判断隔离可靠，应执行跨租户读写、条件绕过、空条件和异常路径
测试。

### 运维与团队能力

选型还包括数据库之外的成本：

- 团队是否熟悉备份、恢复、扩容和版本升级；
- 是否已有监控、日志、权限和告警体系；
- 自建集群的值班成本是否高于云服务费用；
- 数据所在区域、合规和网络延迟是否允许使用云服务；
- 客户端和服务端版本是否有明确兼容策略。

### Embedding 模型迁移

模型版本、维度、归一化和距离度量共同定义向量空间。更换模型时通常需要新建 Collection/Index、全量重算向量、
验证召回率后切换流量。数据库是否方便做蓝绿集合和延迟清理，是长期维护的重要指标。

## 按约束缩小候选

| 首要约束 | 优先评估 | 重点验证 |
| --- | --- | --- |
| 已有 PostgreSQL，希望少一个系统 | Pgvector | 数据规模、查询计划、连接与 HNSW |
| 已有 MariaDB 11.7+，希望少一个系统 | MariaDB | VECTOR INDEX、连接资源、表数量与版本兼容 |
| 已有 Redis Stack，在线低延迟 | Redis | 内存、淘汰策略、动态字段索引 |
| 大规模专用向量检索 | Milvus、Qdrant | 召回率、索引参数、扩缩容与过滤 |
| 已有 Elasticsearch 搜索体系 | Elasticsearch | mapping、script score 成本、字段精确匹配 |
| 已有 OpenSearch 体系 | OpenSearch | 当前缺少 Condition、TLS 客户端配置 |
| 快速原型且需要 metadata 过滤 | Chroma | 版本兼容、持久化和容量边界 |
| 不维护数据库集群 | DashVector、腾讯云、其他托管方案 | 适配能力、网络、配额、费用和锁定成本 |

这个表用于生成候选名单，不应代替真实压测。

## PoC 验证方法

### 准备代表性数据

使用生产模型、真实文本长度分布、真实 metadata schema 和至少接近预期规模的数据。少量随机句子无法验证索引、
过滤和召回质量。

### 固定评价指标

至少记录：

- Recall@K 或人工标注命中率；
- P50、P95、P99 查询延迟；
- 写入吞吐和索引构建时间；
- 内存、磁盘和网络占用；
- metadata 过滤后的召回率；
- 故障重试和恢复时间。

### 使用同一业务查询集

不同数据库分值不完全一致，不能只比较 `score` 数值。应使用同一批 query、同一组相关性标注和相同业务过滤，
比较最终返回结果与延迟。

### 验证生命周期

PoC 不应停在“能写能搜”，还要执行：

1. 批量写入和部分失败；
2. 更新正文、向量与 metadata；
3. 删除以及删除后可见性；
4. 第二个 Collection/Index 的隔离；
5. 数据库重启和持久化恢复；
6. 备份恢复到新实例；
7. 客户端断线、超时和有限重试；
8. 模型维度不匹配和 schema 不兼容。

## 上线前检查清单

1. 固定数据库、客户端和镜像版本；
2. 使用生产 Embedding 模型确认维度和距离度量；
3. 预建或验证 Collection/Index schema；
4. 覆盖全部业务条件和特殊字符；
5. 校准 topK、候选数和 `minScore`；
6. 验证多租户不可见性和路由白名单；
7. 配置认证、TLS、最小权限和网络边界；
8. 配置持久化、备份，并完成一次真实恢复演练；
9. 监控容量、写入失败、查询延迟、索引状态和费用；
10. 准备 Embedding 模型升级、数据迁移和回滚方案。

## 下一步

选出一到两个候选后，进入对应的独立文档完成本地安装和真实测试：

- [Redis](./redis)
- [Milvus](./milvus)
- [Pgvector](./pgvector)
- [MariaDB](./mariadb)
- [Elasticsearch](./elasticsearch)
- [OpenSearch](./opensearch)
- [Chroma](./chroma)
- [Qdrant](./qdrant)
- [阿里云 DashVector](./aliyun)
- [腾讯云向量数据库](./qcloud)

</div>
