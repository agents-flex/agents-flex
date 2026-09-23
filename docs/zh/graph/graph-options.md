# GraphOptions

GraphOptions 描述一次操作的路由、安全和资源限制。对象不可变，适合在多个线程之间共享。

## 常用字段

| 字段 | 作用 |
| --- | --- |
| space | 本次操作使用的 Graph Space |
| timeoutMillis | 查询或事务超时时间 |
| fetchSize | 后端建议的抓取批量 |
| maxRecords | 客户端最多物化的记录数 |
| readOnly | 是否拒绝 Native 写操作 |
| context | 调用链和 Schema 版本上下文 |

~~~java
GraphOptions options = GraphOptions.builder()
    .space("neo4j")
    .timeoutMillis(10_000L)
    .fetchSize(500)
    .maxRecords(1_000)
    .readOnly(true)
    .build();
~~~

maxRecords 是结果保护上限，不是数据库端的业务分页。超过上限时应改用 executePage 或 executeCursor。
readOnly(true) 会在适配器执行 Native Query 前检查查询类型。

Neo4j 可以将 timeout 下推到事务配置。Nebula SessionPool 没有完全等价的单语句硬超时，因此同一个
timeoutMillis 不应被解释为跨后端完全一致的 SLA。

