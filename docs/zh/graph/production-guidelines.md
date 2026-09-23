# 生产使用建议

Graph SDK 是嵌入式执行层。连接凭据、权限、审计、任务调度、备份和数据库高可用仍由使用 SDK 的应用与运维平台负责。

## 连接与资源

- 每个长期存活的 GraphStore 对应一个明确的连接配置和生命周期；
- 进程退出或应用重载时关闭 GraphStore；
- 不要为每个 HTTP 请求创建 Driver 或 SessionPool；
- 多连接场景使用 GraphConnectionRegistry，并用稳定名称区分租户或后端；
- 将 health 检查接入应用探活，但不要把探活成功等同于所有 DDL 权限都可用。

## 安全

- 使用密钥管理系统注入密码和 TLS 材料；
- 为查询、写入、Schema 和 ADMIN 操作配置最小权限账号；
- 对原生查询建立白名单或审核机制；
- NativeGraphQuery 的值必须参数化，结构标识符必须经过白名单校验；
- 日志、GraphResult.queryText、错误信息和 Explain 计划都要脱敏。

GraphOptions.readOnly 是 SDK 层保护，不是数据库授权替代品。真正的租户隔离和 RBAC 应在应用层及数据库账号层同时实现。

## Schema 发布

推荐把 GraphSchema 当作版本化制品：

1. 在 CI 中执行 validateSchema、比较器和迁移计划；
2. 对删除、类型变更和唯一约束变更要求审批；
3. 发布时先应用兼容的 ADDITIVE 变更；
4. 等待 Nebula 属性传播和索引重建；
5. 执行抽样读写和 Explain 验证；
6. 在单独窗口处理破坏性清理。

不要把 DROP_SPACE 或破坏性 Schema 操作放进每次启动自动初始化。

## 查询与容量

- 对用户可编辑查询设置 maxRecords、分页和有限跳数；
- 对高成本查询先执行 explain；
- Neo4j 使用流式游标时及时关闭；
- Nebula 结果当前物化，必须限制查询规模；
- 监控数据库 CPU、内存、磁盘、连接池、锁等待、查询耗时和失败率；
- 对导入按批次控制吞吐，并以 checkpoint 支持恢复。

timeoutMillis 不是所有后端都具备相同语义。对端到端 SLA，应在应用层增加请求截止时间、线程池隔离和熔断；不要只依赖数据库事务超时。

## 导入与一致性

GraphImportService 的默认异步实现是进程内服务，适合单实例 SDK 场景。生产控制面应把任务状态、checkpoint、租户配额和重试策略持久化到自己的任务系统。

导入数据应先节点后边，使用稳定 ID 和幂等 upsert。对边删除、重复边和 rank 语义写出明确业务规则。跨多个请求的业务事务不能假设 Nebula SessionPool 提供原子性。

## 备份与灾难恢复

Neo4j 和 Nebula 的备份、恢复、集群拓扑和升级由数据库运维工具负责，Graph SDK 不替代这些能力。上线前至少验证：

- 备份是否包含 Schema、索引和业务数据；
- 恢复后 space/database 名称和权限是否一致；
- 应用能否重新执行 Schema 校验和导入恢复；
- 失败切换期间连接池和重试策略是否会造成重复写入。

## 可观测性与变更管理

在统一日志中关联 backend、space、请求 ID、Schema 版本、GraphQueryKind、耗时、记录数和错误码。数据库升级、驱动升级和 Graph SDK 升级都应运行 [Docker 真实环境测试](/zh/graph/docker-integration-testing) 及后端特定回归。

SDK 不负责 UI、权限后台、文件解析和分布式调度。应用可以基于本模块构建自己的控制面，但应把这些职责留在应用边界内，避免把 GraphStore 再次扩展成不可维护的全能门面。
