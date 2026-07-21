<div v-pre>

# Observability 故障排查与生产建议

## 先确认问题属于哪一层

初次接入时，不要同时修改所有配置。先根据现象定位层次：

```text
完全没有数据
├── 没有 Span -> 检查开关、SDK/Exporter 和模型开关
└── 有 Span
    ├── 没有 Metric -> 等待导出周期，检查 Metric Reader
    ├── 本地有、APM 没有 -> 检查 OTLP、Collector、认证和网络
    ├── APM 有、JDBC 没有 -> 检查 DataSource、表结构和 Exporter 日志
    └── 数据有但调用链断开 -> 检查异步 Context 传播和父 Span
```

建议先切换到 Logging Exporter。若本地能看到数据，说明 Agents-Flex 埋点通常已经工作，问题更可能位于
OTLP、Collector、APM 或 JDBC 出口；如果本地也没有数据，再检查应用内配置。

## 完全没有 Span 或 Metrics

按顺序检查：

1. `agentsflex.otel.enabled` 是否为 `true`；
2. `agentsflex.otel.exporter.type` 是否仍为默认的 `none`；
3. 使用 `none` 时，应用或 Java Agent 是否真的注册了全局 OpenTelemetry SDK；
4. `setOpenTelemetry(...)` 或 `setCustomExporters(...)` 是否在首次模型、工具或 HTTP 调用之前执行；
5. ChatModel 的 `config.isObservabilityEnabled()` 是否为 `true`；
6. Exporter、Collector 或数据库日志中是否存在初始化和写入错误。

默认配置 `exporter.type=none` 表示复用宿主 SDK，不表示自动输出到日志。如果宿主没有 SDK，
`GlobalOpenTelemetry` 是 no-op，看不到数据属于预期行为。本地验证可以先使用：

```bash
-Dagentsflex.otel.exporter.type=logging
```

## 有 Span，但暂时没有 Metrics

Metrics 由 `PeriodicMetricReader` 周期导出，默认间隔 60 秒。短命令行程序可能在第一次周期到达前退出。

调试时可以缩短间隔：

```bash
-Dagentsflex.otel.metric.export.interval=5
```

内建 SDK 会在 JVM shutdown hook 中关闭 Provider。对于应用管理的 SDK，应由应用在退出前执行
`forceFlush()` 和 `shutdown()`。

## Logging Exporter 没有输出

- 检查应用是否有可用的 SLF4J 实现；只有 `slf4j-api` 时日志会被丢弃；
- 确认日志级别没有过滤 OpenTelemetry exporter 输出；
- 确认 exporter 类型是在 SDK 第一次初始化前设置；
- Metrics 不是每次调用立即输出，应等待导出周期。

## OTLP 无法连接

检查 endpoint 的协议和端口。OTLP gRPC 常用 4317，OTLP HTTP 常用 4318；Agents-Flex 内建 exporter 使用
OTLP gRPC：

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
```

然后检查：

- Collector 是否监听对应地址；
- 容器内的 `127.0.0.1` 是否错误地指向应用容器自身；
- TLS endpoint 是否缺少证书或使用了错误的 `http/https`；
- 认证 header 是否通过 OTel 标准配置传入；
- 防火墙、代理或 Kubernetes NetworkPolicy 是否允许出站；
- Collector 日志是否报告拒绝、限流或后端失败。

Exporter 初始化失败时，Agents-Flex 会记录 warning 并降级为 no-op，避免影响模型调用。初始化状态不会在
运行中自动重试；修正启动配置后应重启应用。

## Chat Span 缺失，但 HTTP Span 存在

通常是模型级开关关闭：

```java
config.setObservabilityEnabled(false);
```

该开关只关闭 `ChatObservabilityInterceptor`，不会关闭全局 `AgentsFlexHttpClient` 观测。需要全部关闭时：

```bash
-Dagentsflex.otel.enabled=false
```

## Tool Span 缺失

检查工具是否被排除：

```bash
-Dagentsflex.otel.tool.excluded=heartbeat,debug_cache
```

`ToolObservabilityInterceptor` 已由 `ToolExecutor` 自动加入，不需要手动注册。工具名匹配区分大小写。如果
工具绕过 `ToolExecutor`，直接调用 `Tool.invoke(...)`，则不会经过任何 Tool interceptor，也不会自动产生
工具 Span。

## 模型响应或工具内容缺失

这是默认的隐私行为：

```properties
agentsflex.otel.capture.content=false
```

显式开启：

```bash
-Dagentsflex.otel.capture.content=true
```

仍看不到工具参数时，检查原始参数是否为合法 JSON。无法解析的内容会被替换为
`[UNPARSEABLE_CONTENT_REDACTED]`，不会为了调试而原样上报。

模型拦截器只采集响应，不采集 Prompt。需要采集 Prompt 时应由应用自行实现 interceptor，并先完成授权、
脱敏、长度限制和数据保留评审。

## Trace 被拆成多条链

常见原因是自定义异步代码没有传播 OpenTelemetry `Context`：

- 把工具调用提交到线程池后直接在其他线程执行；
- 在自定义 Stream listener 中启动后台任务；
- 使用不支持 Context 传播的 reactive 或 callback 框架；
- 创建了新的 root Span，而不是使用当前 Context 作为 parent。

Agents-Flex 会处理自身流式回调和 HTTP 请求的 Context，但无法自动覆盖任意业务线程池。跨线程时应捕获并
恢复 Context：

```java
Context parent = Context.current();

executor.execute(() -> {
    try (Scope ignored = parent.makeCurrent()) {
        runToolOrModel();
    }
});
```

`Scope` 必须在创建它的线程中关闭，不要在请求线程 `makeCurrent()`，再交给回调线程关闭。

## JDBC 表没有数据

检查：

1. 是否执行了 `mysql-schema.sql` 或等价 migration；
2. `DataSource` 是否连接到预期 schema；
3. 自定义表名是否与实际表名一致；
4. 数据库账号是否有 INSERT 权限；
5. 连接池是否耗尽或已经关闭；
6. Exporter warning 中是否存在列类型、字段长度、唯一约束或 batch 错误；
7. Metrics 是否尚未达到导出周期。

每个 batch 在同一事务内执行，任意一行失败会 rollback 整批。Span 表默认有 `(trace_id, span_id)` 唯一约束；
重复写入同一个 Span 会触发约束错误，需要检查是否重复装配了 SDK Processor 或 Exporter。

`BatchSpanProcessor` 使用内存队列，不是磁盘队列，也不保证数据库恢复后的自动重试。数据库不可用期间的
数据可能丢失。强送达场景应优先使用 OTLP Collector 加持久化缓冲或消息队列。

## 应用退出后仍有后台线程

先确认 SDK 所有权：

- `logging`、`otlp`、`setCustomExporters(...)`：Provider 由 Agents-Flex 创建，可调用
  `Observability.shutdown()`；
- `setOpenTelemetry(...)` 或默认全局 SDK：由应用、Spring 或 Java Agent 管理，Agents-Flex 不会关闭；
- JDBC `DataSource` 始终由应用管理，需要单独关闭连接池。

不要同时让多个组件关闭同一个 Provider。应用服务应在统一生命周期回调中按所有权释放资源。

## Metrics 数量快速增长

以下属性不应进入 Metrics：

- user、account、conversation、request、trace、span ID；
- 完整 URL、文件路径、工具参数；
- 动态错误消息；
- 未受控的模型输出。

Agents-Flex 内置 Metrics 已避免这些维度。如果自定义 instrument 加入高基数属性，后端时间序列数会快速
增长。业务关联维度应写入 Span，或在数据库查询层按 trace 聚合。

## 内容与凭证泄露

如果 Exporter 中出现不应采集的数据：

1. 立即关闭 `agentsflex.otel.capture.content`；
2. 停止或限制下游数据访问；
3. 按组织流程删除已导出的敏感数据并轮换泄露凭证；
4. 检查自定义 interceptor 是否写入原始 Prompt、header、URL query 或异常消息；
5. 为 Collector、数据库和查询平台增加访问控制与保留策略；
6. 使用测试数据验证脱敏后再重新开启内容采集。

工具 JSON 脱敏只能识别字段名，不能识别自由文本中的全部 PII。不要把自动脱敏当作数据合规授权。

## 生产环境建议

1. 明确 SDK 所有者，只保留一套 Resource、Sampler、Processor 和生命周期管理；
2. 生产环境优先使用 OTLP Collector，不让每个应用直接绑定具体 APM 后端；
3. 为 `service.name`、部署环境和实例设置稳定 Resource 属性；
4. 默认关闭内容采集，按具体工具和数据类型完成安全评审后再开启；
5. conversation、account 等高基数信息只放 Span，不放 Metrics；
6. Collector 和 JDBC exporter 错误必须进入应用日志与告警；
7. 根据吞吐量配置 Collector、数据库连接池、索引、分区和数据保留周期；
8. 数据库写入账号使用最小权限，DDL migration 使用独立账号；
9. 对观测平台实施租户隔离、访问审计和敏感字段删除流程；
10. 压测时同时观察业务延迟、Span queue、export timeout 和数据库 batch 耗时；
11. 应用关闭时 flush 并按所有权关闭 Provider、Exporter 和连接池；
12. 升级 OpenTelemetry 或语义属性前，确认现有查询、Dashboard 和告警仍然兼容。

## 新手常见误区

| 误区 | 实际情况 |
| --- | --- |
| 开启 `enabled=true` 就一定有日志 | 默认 exporter 是 `none`，没有宿主 SDK 时仍是 no-op |
| 一次请求结束后 Metric 应立即出现 | Metric 按 Reader 周期导出，默认 60 秒 |
| Tool 一定是 Chat Span 的子节点 | 需要执行工具时仍保留同一个父 Context |
| 开启内容采集就能看到 Prompt | 当前 Chat 埋点只采集响应，不自动采集 Prompt |
| JDBC Exporter 会自动建表和管理连接池 | DDL、driver、DataSource 和生命周期都由应用负责 |
| BatchSpanProcessor 能保证数据不丢 | 它是内存队列，不是持久化消息队列 |
| 多 destination 等于可靠复制 | 后端长期不可用、队列满或进程崩溃仍可能丢失遥测数据 |

## 相关文档

- [Observability 模块概述](./observability)
- [零基础理解可观测](./concepts)
- [典型场景与实践](./scenarios)
- [Observability 快速开始](./getting-started)
- [模型与 HTTP 可观测](./model)
- [工具调用可观测](./tool)
- [JDBC 持久化](./jdbc)

</div>
