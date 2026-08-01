---
title: Demo：Worker 与自动重试
description: 从可运行源码理解工具失败、持久化重试、Worker 领取、Lease 和稳定幂等键。
---

# Demo：Worker 与自动重试

<div v-pre>

## 源码

```text
demos/agent-demo/src/main/java/com/agentsflex/demo/agent/DurableWorkerAgentDemo.java
```

运行：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=worker
```

## 场景

库存同步 Tool 第一次执行时模拟临时故障，第二次恢复。Runner 不在当前线程内立即循环重试，而是保存：

```text
status = RETRY_SCHEDULED
retryCount = 1
nextRunAt = 到期时间
phase = TOOLS
pendingToolCalls = [inventory-call-1]
```

随后 `AgentWorker` 从 RunStore 原子领取任务、写入 Lease，并恢复同一个 ToolCall。工具成功后 Runner 才回到 MODEL 阶段生成最终回答。

## 为什么模型没有重新决定

第一次模型响应和工具引用已经保存。工具失败发生在 TOOLS 阶段，因此恢复点仍然是 TOOLS。Demo 会校验：

- 工具总尝试次数为 2；
- 模型只调用 2 次：一次产生 ToolCall，一次读取成功结果；
- 两次工具尝试使用相同的 `AgentToolInvocation.getIdempotencyKey()`；
- 事件流包含 `RETRY_SCHEDULED`、工具完成和 Run 完成。

## 生产调整

Demo 将重试延迟设置为 0，便于立即运行。生产环境应配置指数退避，并使用共享 RunStore：

```java
AgentRetryPolicy.builder()
    .maxRetries(3)
    .initialDelayMillis(1_000)
    .maxDelayMillis(30_000)
    .multiplier(2.0)
    .build();
```

Lease 只能避免有效期内多个 Worker 同时推进，不能消除外部副作用成功但 Checkpoint 尚未保存的窗口，写工具仍须按幂等键处理重复请求。

## 延伸阅读

- [Worker 与 Lease](../worker-lease.md)
- [Checkpoint 与中断恢复](../checkpoint-resume.md)
- [长任务恢复场景](../scenarios/long-running-task.md)

</div>
