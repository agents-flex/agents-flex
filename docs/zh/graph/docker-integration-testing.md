# Docker 真实环境测试

真实数据库测试用于验证驱动、协议、DDL、索引状态和后端版本行为。它不能替代单元测试，也不应把本地开发容器直接当作生产部署方案。

## 测试环境

建议为 Neo4j 和 Nebula 使用独立 Docker Compose 项目、固定镜像版本和独立网络。测试启动前检查端口、健康状态、账号和版本，测试结束后销毁容器或使用一次性 volume。

测试矩阵至少包含：

| 维度 | Neo4j | Nebula |
| --- | --- | --- |
| 镜像版本 | 固定 Community；Enterprise 另行验证建库权限 | 固定 Graph/Meta/Storage 版本 |
| 连接 | Bolt URI、用户名、密码 | Graph service、SessionPool |
| 默认空间 | database，例如 neo4j | space，例如 agents_flex |
| 能力 | 事务、流式游标、Cypher | 单 TAG、物化游标、nGQL |

## 测试顺序

1. 启动容器并等待健康检查，不要只等待端口打开。
2. 创建 GraphStore，调用 health 进行真实探活。
3. 创建唯一测试空间；Neo4j Community 不要把“自动创建 database 成功”作为必过条件。
4. 应用 GraphSchema，验证索引和约束实际存在。
5. 写入节点和边，覆盖重复 upsert、属性更新、删除和 detach delete。
6. 执行统一遍历、过滤、聚合、Union、分页、游标和 Explain。
7. 执行原生 Cypher 或 nGQL，验证只读保护和参数绑定。
8. 执行批量及异步导入，检查报告、失败项、取消和恢复点。
9. 验证连接断开、超时、close 和重复 close。
10. finally 清理空间、关闭 Store、关闭任务执行器并收集日志。

## 关键真实断言

### Neo4j

- GraphOptions.space 确实选择目标 database；
- 事务提交后可见，回滚后不可见；
- executeCursor 是流式读取且 close 释放资源；
- UNIQUE_CONSTRAINT 和多标签行为与 GraphSchema 一致；
- Community 对创建/删除 database 的限制被正确报告。

### Nebula

- GraphOptions.space 确实选择目标 space；
- TAG/EDGE DDL 可重复执行，新增属性传播完成后才能写入；
- 创建索引后等待重建，属性过滤真实可执行；
- 单 TAG Writer 的限制得到验证；
- transactions() 明确抛出 UnsupportedGraphFeatureException；
- executeCursor 不被误认为服务端流式游标；
- 断连重试不会在关闭后重建 SessionPool。
- 同一 VID 或同一边 rank 的并发 UPSERT 在 Nebula Storage 返回冲突时会有限退避重试，最终结果保持单一业务键。

## Maven 执行建议

为真实测试设置显式 profile 和环境变量，例如 GRAPH_NEO4J_IT、GRAPH_NEBULA_IT。未启用 profile 时测试应跳过，而不是因为本机没有容器导致普通构建失败。CI 中使用固定镜像和等待脚本，保存失败时的数据库日志、GraphResultMetadata 和后端版本。

真实测试必须断言业务结果，而不是只断言没有抛异常。对于最终一致的 DDL、索引重建和 Nebula 服务启动，使用有上限的轮询；超时后输出最后一次后端错误。

仓库中的 `agents-flex-graph-integration-tests` 会对 Neo4j 和 Nebula 执行同一套跨模块场景：候选抽取、
增量计划、真实写图、提交结果不确定时的幂等重放、共享来源关系以及逐文档撤回。容器就绪后执行：

```bash
env -u DEEPSEEK_API_KEY GRAPH_INTEGRATION=true mvn \
  -pl agents-flex-graph/agents-flex-graph-integration-tests -am \
  -Dtest=RealGraphExtractionIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该跨模块测试使用确定性 Extractor，不消耗模型额度；真实 DeepSeek 测试由
`GRAPH_LLM_INTEGRATION=true` 和 `DEEPSEEK_API_KEY` 单独启用，使数据库故障与模型供应商故障可以独立诊断。

当前 Docker 验收还包含两个不可用端点探活场景，验证 `health()` 返回 DOWN、`close()` 可重复调用，
以及 Neo4j/Nebula 两个后端各自的同键并发写入场景。

相关章节：[单元测试与契约测试](/zh/graph/testing)、[故障排查](/zh/graph/troubleshooting)。
