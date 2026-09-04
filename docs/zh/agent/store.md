---
title: 任务快照持久化
description: 将 Agent 任务进度保存到 JDBC 或 Redis，使任务能够跨应用重启和服务实例继续处理。
---

# 任务快照持久化

## 概述

`AgentRunner` 在执行任务时，会持续保存任务的最新进度。默认情况下，这些进度保存在当前应用的内存中，
适合本地开发和简单测试。

但在正式应用中，任务可能需要等待几分钟甚至几天。例如：

- 退款申请正在等待主管审批；
- 用户还没有填写补充表单；
- 外部服务暂时不可用，任务等待下次重试；
- 后台 Worker 稍后才会领取任务；
- 应用正在发布新版本，需要重启或切换到其他实例。

如果任务进度只在内存中，应用一旦重启，这些等待中的任务就无法找回。多个服务实例也无法看到彼此保存的
状态。

`AgentTurnStore` 用来解决这个问题。它是 Agent 任务的存储接口，负责按 `turnId` 保存和读取任务进度。
Agents-Flex 提供内存、JDBC 和 Redis 三种选择：

```text
AgentRunner
    ↓ 自动保存和读取
AgentTurnStore
    ├─ 内存：本地开发和测试
    ├─ JDBC：保存到业务数据库
    └─ Redis：保存到共享 Redis
```

业务代码通常不需要直接操作 Store。将 Store 配置到 Runner 后，任务创建、等待、恢复、重试和结束时的
保存操作都由 Runner 完成。

## 如何选择

| Store | 适用场景 | 需要注意 |
| --- | --- | --- |
| `InMemoryAgentTurnStore` | 本地开发、单元测试、可以接受重启后丢失的演示 | 数据只存在当前进程中 |
| JDBC Store | 已有 MySQL、PostgreSQL 等数据库，希望统一备份和管理 | 需要创建表并配置数据库连接池 |
| Redis Store | 已有共享 Redis，任务读取和调度频繁 | 需要配置 Redis 持久化、容量和高可用 |
| 自定义 Store | 必须接入公司内部存储平台 | 需要完整实现并发保护和后台领取能力 |

大多数业务不需要自己实现 Store。已经有稳定业务数据库时，可以优先使用 JDBC；基础设施主要依赖 Redis
时，可以使用 Redis。需要根据实际负载选择时，可参考 [Store 压测对比](./store-benchmark)。

## 快速开始

JDBC 和 Redis 选择一种即可，不需要同时配置。

### 1. 添加依赖

使用 JDBC：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-agent-store-jdbc</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

使用 Redis：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-agent-store-redis</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

Store 模块的版本应与 `agents-flex-agent` 保持一致。JDBC 方式还需要应用自己提供数据库驱动和
`DataSource`；Redis 模块使用 Jedis 连接 Redis。

### 2. 创建 Store

根据实际存储选择下面一种配置。

#### 使用 JDBC

下面的 `dataSource` 表示应用已经配置好的数据库连接池：

```java
JdbcAgentStoreConfig storeConfig =
    JdbcAgentStoreConfig.builder(dataSource)
        .tablePrefix("af_agent_")
        .binaryColumnType("BLOB")
        .build();

storeConfig.schema().initialize();

AgentTurnStore turnStore =
    storeConfig.turnStore();
```

| 配置 | 作用 |
| --- | --- |
| `builder(dataSource)` | 使用应用已有的数据库连接池 |
| `tablePrefix(...)` | 设置表名前缀，默认值为 `af_agent_` |
| `binaryColumnType(...)` | 设置当前数据库保存二进制内容使用的列类型 |
| `schema().initialize()` | 创建 Store 所需的表和索引 |
| `turnStore()` | 获得可以配置到 Runner 的 `AgentTurnStore` |

`BLOB` 适用于 MySQL 等数据库；PostgreSQL 通常使用 `BYTEA`。应根据实际数据库选择列类型。

`schema().initialize()` 可以重复调用，适合本地开发和测试。生产环境通常应将相同的建表语句纳入 Flyway、
Liquibase 等数据库迁移流程，避免多个应用实例在启动时同时修改表结构。

`DataSource` 由业务应用创建和管理，关闭应用时仍由业务应用负责关闭。

#### 使用 Redis

可以直接使用 Redis URI：

```java
RedisAgentStoreConfig storeConfig =
    RedisAgentStoreConfig
        .builder("redis://127.0.0.1:6379")
        .keyPrefix("agents-flex:agent:")
        .build();

AgentTurnStore turnStore =
    storeConfig.turnStore();
```

| 配置 | 作用 |
| --- | --- |
| `builder(redisUri)` | 根据 URI 创建 Redis 客户端 |
| `keyPrefix(...)` | 设置所有 Agent Store 数据的键前缀 |
| `turnStore()` | 获得可以配置到 Runner 的 `AgentTurnStore` |

使用 URI 创建客户端时，应用关闭阶段需要关闭 `storeConfig`：

```java
storeConfig.close();
```

如果应用已经统一创建了 `JedisPooled`，也可以直接传入：

```java
RedisAgentStoreConfig storeConfig =
    RedisAgentStoreConfig.builder(jedisPooled)
        .keyPrefix("production:agents-flex:")
        .build();
```

这种方式下，Redis 客户端由业务应用管理，关闭 `storeConfig` 不会关闭传入的 `JedisPooled`。

不同环境和不同业务应使用不同的 `keyPrefix`，避免测试、预发布和生产数据写入同一组 Redis Key。

### 3. 配置 AgentRunner

无论选择 JDBC 还是 Redis，最终都需要将得到的 `turnStore` 配置到 Runner：

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `turnStore(...)` | 保存和读取任务进度 |
| `agentLoader(...)` | 恢复任务时加载原来的 Agent 版本 |

`agentLoader` 表示已经创建好的 `AgentLoader`。Store 只保存任务数据，不保存模型客户端和 Java 工具函数，
因此需要 Loader 重新获得完整 Agent。配置方式见 [Agent 加载与版本](./agent-loader)。

配置完成后，仍然按照正常方式执行和恢复任务：

```java
AgentTurn turn = runner.run(agent, "生成月度报告");
String turnId = turn.getId();

AgentTurn restored = runner.restore(turnId);
```

Runner 会自动使用当前 Store，不需要业务代码手动调用 `turnStore.save(...)`。

## 多实例部署

当多个应用实例或 Worker 共同处理任务时，它们必须连接同一个 JDBC 数据库或 Redis 集群：

```text
应用实例 A ─┐
应用实例 B ─┼─→ 同一个 AgentTurnStore
后台 Worker ─┘
```

同时还应保证：

- 所有实例能通过 `AgentLoader` 加载相同的 Agent ID 和历史版本；
- 每个实例都具备对应版本需要的模型、工具和 Middleware；
- 数据库连接池或 Redis 客户端可以承受所有实例的总请求量；
- 写入类工具使用幂等键，避免故障接管时重复产生业务影响。

Store 可以避免多个 Worker 静默覆盖同一份任务进度，但不能替代退款、发货、发送邮件等业务操作自身的
幂等设计。具体做法见[工具运行上下文](./tool-context)。

## Store 保存哪些数据

`AgentTurnStore` 主要保存任务 Snapshot，包括任务状态、消息、等待信息、重试时间、统计数据和业务
metadata。完整说明见[任务快照](./snapshot)。

以下内容不由 AgentTurnStore 保存：

| 内容 | 应由谁负责 |
| --- | --- |
| Agent 配置、模型和工具 | `AgentLoader` 与应用配置系统 |
| 多轮聊天记录 | `ChatMemory` |
| 实时进度和执行事件 | `AgentEventListener` 或业务消息系统 |
| 订单、工单等业务数据 | 业务数据库 |
| 合规审计记录 | 业务审计库或可靠事件系统 |

Store 负责的是“这项 Agent 任务执行到哪里”，不是应用所有数据的统一存储。

## 与 ChatMemory 的区别

`AgentTurnStore` 和 `ChatMemory` 都可能保存消息，但用途不同：

| 组件 | 主要用途 |
| --- | --- |
| `AgentTurnStore` | 保存一项任务的执行进度，使它可以恢复 |
| `ChatMemory` | 保存多轮对话，使下一项任务理解之前聊过什么 |

例如，退款任务正在等待审批时，Store 保存待审批工具和当前任务状态；用户之后说“继续刚才的退款”时，
ChatMemory 帮助模型理解前面的对话。

需要连续对话时，应单独配置共享的 `ChatMemory`。它不能替代 Store，Store 也不应被当作聊天记录查询接口。
会话配置见[上下文管理](./context-management)。

## 数据安全与运维

任务 Snapshot 可能包含用户消息、工具参数、表单数据、错误摘要和业务 metadata。生产环境应至少考虑：

1. 数据库或 Redis 的访问权限与网络隔离；
2. 传输加密、存储加密和备份保护；
3. 密码、API Key 等敏感信息不得写入 metadata；
4. JDBC 连接超时、Redis 超时和连接池容量；
5. Store 保存失败、版本冲突和可执行任务积压的监控；
6. 已完成、失败和取消任务的保留期限与清理策略。

Redis Store 是否能在服务重启后保留数据，还取决于 Redis 自身的持久化、高可用和备份配置。不要仅因为
使用 Redis Store 就默认数据一定不会丢失。

清理历史任务时，应先确认任务已经结束，并使用明确的时间范围和小批次删除，避免长事务、全表锁或 Redis
阻塞。业务审计和聊天记录可能有不同的保留期限，应分别制定策略。

## 进阶：自定义 AgentTurnStore

只有框架内置的 JDBC 和 Redis 实现无法满足要求时，才建议实现自己的 `AgentTurnStore`。

接口中的主要方法如下：

| 方法 | 需要实现的行为 |
| --- | --- |
| `load(turnId)` | 读取指定任务的最新 Snapshot |
| `save(snapshot, expectedVersion)` | 只有版本符合预期时才保存新进度 |
| `findActiveTurn(conversationId)` | 查找某段会话中尚未结束的任务 |
| `currentTimeMillis()` | 提供任务调度使用的统一当前时间 |
| `requestCancellation(turnId)` | 记录取消请求，并且不能被旧状态清除 |
| `claimRunnable(...)` | 让一个 Worker 独占领取当前可以执行的任务 |
| `renewLease(...)` | 延长 Worker 对任务的临时执行权 |
| `releaseLease(...)` | Worker 完成当前处理后释放执行权 |

### 防止旧进度覆盖新进度

`save(snapshot, expectedVersion)` 中的 `expectedVersion` 表示调用方读取任务时看到的版本。只有 Store 中的
当前版本仍然相同，才能保存；否则说明其他执行者已经先更新任务，应报告版本冲突。

JDBC 实现通常可以使用带版本条件的更新，并检查实际影响行数：

```sql
UPDATE af_agent_turns
SET version = ?, payload = ?
WHERE turn_id = ? AND version = ?
```

这段 SQL 只用于展示版本条件。完整实现还需要原子更新任务状态、下次执行时间、取消标记和 Worker 执行权
等字段，并在更新行数为 0 时报告版本冲突。

不能采用“先查询版本，再无条件更新”的方式，因为查询和更新之间可能已经有其他实例写入。

### 防止多个 Worker 同时执行

Worker 领取任务后会获得一段有期限的执行权。Store 需要记录 Worker ID、每次领取生成的唯一凭证和到期
时间。只有仍持有有效凭证的 Worker 才能续期或提交进度。

这样即使旧 Worker 暂时失去网络、任务随后被新 Worker 接管，旧 Worker 恢复连接后也不能覆盖新进度。
这只是任务状态的并发保护，外部业务操作仍需幂等。

多实例部署时，`currentTimeMillis()` 应使用数据库或 Redis 服务端时间，避免不同应用服务器的时钟误差导致
任务被提前接管或延迟执行。

### 序列化

JDBC 和 Redis Store 默认使用 `FastjsonAgentStoreSerializer` 将 Snapshot 编码为二进制数据。自定义
Serializer 时需要保证：

- 应用重启后可以读取之前保存的数据；
- 新版本可以读取需要保留的旧数据；
- 只允许反序列化明确可信的业务类型；
- 无法序列化的对象不会被写入 metadata。

如果 metadata 只使用字符串、数字、布尔值、列表和 Map，通常不需要修改默认 Serializer。

### 测试要求

自定义 Store 至少应测试以下情况：

- 两个执行者同时保存同一版本时，只有一个成功；
- Worker 执行权到期后，可以由另一个 Worker 接管；
- 旧 Worker 不能提交或续期已经被接管的任务；
- 取消请求不会被旧 Snapshot 覆盖；
- 尚未到期的重试任务不会被提前领取；
- 应用重启后，Snapshot 仍能完整读取。

## 事务边界

Store 只能保证 Agent 任务进度自身的原子更新，不能自动与订单数据库、审批系统或消息队列组成同一个事务。

例如，审批服务已经记录“同意”，但调用恢复接口前发生故障，Agent 任务仍然保持等待状态。需要可靠衔接
外部回调时，业务系统应保存回调事件，使用唯一事件 ID 防止重复处理，并在失败后重试或扫描补偿。

同样地，工具已经完成退款，但 Snapshot 还没来得及保存时，也可能再次执行。写入类工具仍应使用业务幂等
键，不能依赖 Store 事务解决所有外部一致性问题。

## 版本升级

升级 Agents-Flex 前，应在测试环境验证现有任务数据可以被新版本读取。不要在没有确认兼容性的情况下，
让不同框架版本的 Runner 同时读写同一批 Store 数据。

从早期 `AgentRunStore` 升级到当前 `AgentTurnStore` 时，框架不会自动迁移旧数据。已有系统应先完成旧任务，
或者在停机窗口迁移仍需保留的数据。Schema 初始化只会创建当前表，不会自动删除旧表或旧 Redis Key。

## 使用建议

1. 本地开发使用内存 Store，生产环境使用所有实例可访问的持久化 Store。
2. JDBC 与 Redis 选择一种即可，并按现有基础设施进行备份、高可用和容量规划。
3. Runner、Worker 和回调服务必须连接同一套 Store。
4. 配置能够加载历史 Agent 版本的 `AgentLoader`。
5. 写入类工具同时实现幂等，不把 Store 当作业务事务。
6. 为敏感数据、历史任务和版本升级制定明确策略。

## 相关文档

- 了解 Store 中保存的任务数据：[任务快照](./snapshot)
- 配置 Agent 历史版本加载：[Agent 加载与版本](./agent-loader)
- 配置后台任务执行：[后台任务 Worker](./worker)
- 防止工具重复操作：[工具运行上下文](./tool-context)
- 配置连续对话记录：[上下文管理](./context-management)
- 对比 JDBC 与 Redis 性能：[Store 压测对比](./store-benchmark)
