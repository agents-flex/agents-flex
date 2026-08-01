---
title: Demo：基础工具 Agent
description: 从可运行源码理解模型 ToolCall、工具执行、ToolMessage、预算和 AgentToolInvocation。
---

# Demo：基础工具 Agent

<div v-pre>

## 源码

完整可运行源码位于：

```text
demos/agent-demo/src/main/java/com/agentsflex/demo/agent/ToolCallingAgentDemo.java
```

运行：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=tool
```

## 执行过程

```text
用户输入
  → 脚本模型返回 query_weather ToolCall
  → Runner 保存 ToolCall 和 AgentToolReference
  → 执行天气 Tool
  → 保存 ToolMessage
  → 模型读取工具结果并返回最终回答
  → Run 进入 COMPLETED
```

Demo 给 Agent 配置了稳定的 `id + version`、系统指令、Tool、迭代限制、最长时间和工具次数预算。创建 Run 时还通过 `AgentRunOptions` 原子保存 tenantId 与 requestId。

## 工具幂等上下文

工具内部读取：

```java
AgentToolInvocation invocation = AgentToolInvocation.current();
String idempotencyKey = invocation.getIdempotencyKey();
```

输出中的幂等键格式为：

```text
<runId>:weather-call-1
```

同一个 pending ToolCall 跨 Checkpoint 恢复后仍使用相同键。示例工具只是查询，但生产写工具可以把该键发送给订单、支付或部署服务。

## 需要观察的输出

- 状态最终为 `COMPLETED`；
- 模型迭代次数为 2；
- 工具调用次数为 1；
- 最终回答来自第二轮模型响应；
- 幂等键包含 Run ID 和 ToolCall ID。

## 延伸阅读

- [快速开始](../getting-started.md)
- [执行循环与生命周期](../execution-lifecycle.md)
- [工具执行与审批](../tools-and-approval.md)

</div>
