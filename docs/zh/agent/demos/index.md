---
title: Agent Demo 场景与运行方式
description: 运行仓库中的 Agent Demo，学习工具闭环、人工审批、持久化重试、Worker、任务规划和子 Agent。
---

# Agent Demo 场景与运行方式

<div v-pre>

## 模块位置

仓库根目录的 `demos/agent-demo` 是一个可以离线运行的 Maven 模块。它使用确定性的脚本模型模拟模型响应，因此不需要 API Key，也不会产生网络请求。

```text
demos/agent-demo/
├── pom.xml
├── README.md
└── src/main/java/com/agentsflex/demo/agent/
    ├── AgentDemoLauncher.java
    ├── ToolCallingAgentDemo.java
    ├── HumanApprovalAgentDemo.java
    ├── DurableWorkerAgentDemo.java
    ├── TaskPlanningAgentDemo.java
    ├── RuntimeExtensionsAgentDemo.java
    ├── DemoScriptedChatModel.java
    └── DemoSupport.java
```

脚本模型仍然接收真实 Prompt，并返回真实 `AiMessage` 和 `ToolCall`。因此除模型来源外，AgentRunner、Checkpoint、Registry、审批、Worker 和 TaskPlan 的运行路径都与接入在线 ChatModel 时一致。

## 编译

在仓库根目录执行：

```bash
mvn -pl demos/agent-demo -am install -DskipTests
```

`-am` 会同时构建当前工作区中的 `agents-flex-core`，避免 Demo 使用本地仓库中的旧版本。

## 运行全部场景

```bash
mvn -f demos/agent-demo/pom.xml exec:java
```

## 运行单个场景

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=tool
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=approval
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=worker
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=planning
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=runtime
```

## 场景对应关系

| 参数 | 主类 | 重点能力 |
| --- | --- | --- |
| `tool` | `ToolCallingAgentDemo` | ToolCall、ToolMessage、预算、Run metadata、工具幂等身份 |
| `approval` | `HumanApprovalAgentDemo` | ToolApprovalPolicy、Suspension、Checkpoint、跨 Runner 恢复、事件 |
| `worker` | `DurableWorkerAgentDemo` | RetryPolicy、pending ToolCall 恢复、Worker 领取、Lease |
| `planning` | `TaskPlanningAgentDemo` | AgentTaskPlanner、任务进度、专业子 Agent、根 Agent 汇总 |
| `runtime` | `RuntimeExtensionsAgentDemo` | Invocation Context、Middleware、实时 delta、工具进度、上下文压缩、Artifact 外置 |

## 替换为真实模型

Demo 中只有以下对象是模拟实现：

```java
DemoScriptedChatModel model = new DemoScriptedChatModel();
```

将它替换为任意 `ChatModel`：

```java
ChatModel model = yourChatModel;
```

随后保留 Agent 定义、Tool、Runner 和 Store 代码即可。真实模型必须支持项目 ToolCall 协议；生产部署还应把内存 Store 和 Registry 替换为共享实现。

## 阅读顺序

1. [基础工具 Agent](./tool-calling-agent.md)
2. [持久化工具审批](./durable-tool-agent.md)
3. [Worker 与自动重试](./durable-worker-agent.md)
4. [任务规划与子 Agent](./planned-agent.md)
5. [运行时扩展](./runtime-extensions-agent.md)

</div>
