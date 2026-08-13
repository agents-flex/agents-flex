# Store 持久化

`AsyncTaskStore` 是任务状态的事实来源，同时提供调度索引、CAS 版本和 Worker 租约。框架提供内存、JDBC 和 Redis 三种实现。

## 为什么 Store 不只是结果缓存

Store 除了保存最终结果，还决定哪个任务已经到期、哪个 Worker 获得执行权，以及并发更新能否成功。如果 Store 丢失或出现不一致，任务可能无法恢复、重复执行或长期停留在错误状态。因此生产环境应把它当作核心业务存储管理。

## 选择 Store

| 实现 | Maven 模块 | 适合场景 |
| --- | --- | --- |
| `InMemoryAsyncTaskStore` | `agents-flex-async-task` | 单元测试、单进程开发 |
| `JdbcAsyncTaskStore` | `agents-flex-async-task-store-jdbc` | 已有关系数据库、需要事务和持久化 |
| `RedisAsyncTaskStore` | `agents-flex-async-task-store-redis` | 低延迟调度、多 Worker、已有 Redis |

一般选择建议：已有可靠关系数据库且任务规模中等时优先 JDBC；已有 Redis 集群、调度频率高时选择 Redis。两者都只保证任务领取和状态更新的原子性，不会自动提供跨 JVM 的全局 QPS 或配额计数。

## JDBC Store

添加依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-async-task-store-jdbc</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

配置和建表：

```java
import com.agentsflex.asynctask.store.AsyncTaskStore;
import com.agentsflex.asynctask.store.jdbc.JdbcAsyncTaskStoreConfig;

import javax.sql.DataSource;

JdbcAsyncTaskStoreConfig storeConfig =
    JdbcAsyncTaskStoreConfig.builder(dataSource)
        .tablePrefix("af_async_")
        .binaryColumnType("BLOB")
        .build();

storeConfig.schema().createIfNotExists();
AsyncTaskStore store = storeConfig.store();
```

默认创建 `af_async_tasks`，并为待提交任务和待查询任务创建索引。`binaryColumnType` 应按数据库选择，例如 PostgreSQL 通常使用 `BYTEA`。生产环境建议由 Flyway、Liquibase 等迁移工具管理相同结构，而不是让应用账号持有 DDL 权限。

## Redis Store

添加依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-async-task-store-redis</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

使用 Redis URI：

```java
import com.agentsflex.asynctask.store.AsyncTaskStore;
import com.agentsflex.asynctask.store.redis.RedisAsyncTaskStoreConfig;

RedisAsyncTaskStoreConfig storeConfig =
    RedisAsyncTaskStoreConfig.builder("redis://localhost:6379/0")
        .keyPrefix("agents-flex:async-task:")
        .build();

AsyncTaskStore store = storeConfig.store();
```

应用关闭时：

```java
storeConfig.close();
```

也可以传入应用统一管理的 `JedisPooled`。此时关闭 Store 配置不会代替应用管理外部客户端。

Redis 实现使用 Hash、ZSet 和 Lua 完成 CAS、领取、续租、释放和取消。键使用相同 hash tag，支持在 Redis Cluster 同槽执行脚本。

## 序列化器白名单

JDBC 和 Redis 默认使用 `FastjsonAsyncTaskStoreSerializer` 保存 JSONB。它默认允许框架类和常见 JDK 类型，不会对 Store 内容开放无限制 AutoType。

如果 `enqueue()` 使用自己的 DTO，必须显式增加尽可能精确的业务包前缀：

```java
import com.agentsflex.asynctask.store.FastjsonAsyncTaskStoreSerializer;

FastjsonAsyncTaskStoreSerializer serializer =
    new FastjsonAsyncTaskStoreSerializer("com.example.ocr.task.");
```

JDBC：

```java
JdbcAsyncTaskStoreConfig.builder(dataSource)
    .serializer(serializer)
    .build();
```

Redis：

```java
RedisAsyncTaskStoreConfig.builder("redis://localhost:6379/0")
    .serializer(serializer)
    .build();
```

不要使用过宽的根包白名单。Store 内容应视为受保护数据，Redis 和数据库都需要访问控制、传输加密和备份策略。

## CAS 与租约

每次更新都携带任务 `version`。版本不匹配时抛出 `AsyncTaskVersionConflictException`，避免并发保存覆盖较新的取消或状态变化。

Worker 领取任务后获得 `leaseOwner`、`leaseId` 和 `leaseUntil`。`leaseId` 是 fencing token：即使使用相同 `workerId`，过期的上一轮执行也不能覆盖新 Worker 的结果。

当前内置 Worker 不会在单次 Handler 调用期间自动续租，因此 `leaseMillis` 必须覆盖供应商请求与结果保存的最长正常耗时。自行扩展执行器时可以使用 `AsyncTaskStore.renewLease()`，但必须携带当前 `workerId` 和 `leaseId`。`workerId` 在所有运行实例中必须唯一。

## 数据保留

当前 Store 保留终态任务，便于审计和查询。生产系统应根据业务要求另行实现归档或清理流程，并确保不会删除仍处于 `PENDING_SUBMIT`、`SUBMITTED`、`RUNNING` 或有效租约中的任务。

## 上线检查清单

- 使用 JDBC 或 Redis，不把内存 Store 用于需要恢复的生产任务。
- 为每个 Worker 配置全局唯一的 `workerId`。
- `leaseMillis` 覆盖最慢的一次供应商 HTTP 调用和 Store 保存。
- 验证业务 DTO 已实现 `Serializable`，并配置最小化反序列化白名单。
- 对数据库和 Redis 配置备份、访问控制、TLS 与容量告警。
- 建立终态任务归档策略，并监控长时间未推进的活动任务。

## 常见问题

### JDBC 或 Redis Store 是否自动实现全局限流？

不会。它们原子领取单个任务，但 Java `InMemoryAsyncTaskAdmissionPolicy` 的窗口和配置仍属于当前进程。严格全局限流需要共享策略。

### Store 暂时不可用时会怎样？

Manager 无法可靠创建或读取任务，Worker 也无法领取和保存状态。业务入口应返回可重试错误，不能绕过 Store 直接提交，否则可能留下无法跟踪的远端任务。

### 可以修改持久化 payload 的类名或字段吗？

要谨慎。历史任务需要按旧类型反序列化。DTO 变更应保持向后兼容，Handler Key 和查询参数协议也应进行版本管理。

## 下一步

- [异步任务概览](./overview)
- [自定义 Handler](./handler)
- [调度与准入控制](./scheduling)
