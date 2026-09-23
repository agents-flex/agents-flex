# Explain 执行计划

GraphQueryExecutor 提供 explain 方法，用于在不返回业务结果的情况下请求后端执行计划。它适合查询工作台、性能回归和上线前检查。

## 基本用法

~~~java
GraphExplainResult plan = graph.query().explain(query, GraphOptions.ofSpace("neo4j"));

System.out.println(plan.getBackend());
System.out.println(plan.getPlanText());
Map<String, Object> details = plan.getDetails();
~~~

GraphExplainResult 统一承载三部分：

| 属性 | 说明 |
| --- | --- |
| backend | 产生计划的后端标识，例如 neo4j 或 nebula |
| planText | 面向日志和人工阅读的计划文本 |
| details | 后端特有的结构化详情，不能假设跨后端字段一致 |

也可以对 NativeGraphQuery 调用 explain。查询意图必须与原查询一致；对于写入或管理语句，应使用数据库支持的 EXPLAIN 语法并明确声明类型。

## 后端差异

Neo4j 适配器返回驱动提供的计划树文本及可用详情。Nebula 适配器执行 EXPLAIN nGQL 并把结果行包装到 planText 和 details 中。两者都支持 GraphFeature.QUERY_EXPLAIN，但计划节点、成本估算和字段名称并不相同。

适配器或数据库版本不支持计划时会抛出 UnsupportedGraphFeatureException。调用方应先检查：

~~~java
if (!graph.capabilities().supports(GraphFeature.QUERY_EXPLAIN)) {
    // 在产品中展示“不支持执行计划”，不要尝试猜测计划。
}
~~~

## 性能检查建议

1. 先用固定数据集和固定数据库版本保存基线计划。
2. 检查是否使用了节点/边索引，是否出现全图扫描或不受控的变长路径。
3. 对用户可编辑的查询设置 GraphOptions.maxRecords 和 timeoutMillis。
4. 只把计划作为诊断信息展示，不要把计划文本当作稳定 API 进行解析。
5. 升级 Neo4j、Nebula 或索引后重新执行基线，计划变化不一定意味着功能回归，应结合耗时和结果验证。

Explain 本身不等于权限审计，也不会替代数据库的慢查询、锁等待和资源监控。生产环境仍需结合数据库监控系统观察 CPU、内存、连接池和磁盘。
