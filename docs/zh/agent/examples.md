---
title: 示例与 Demo
---

# 示例与 Demo

## 概述

仓库提供两组 Demo。`agent-demo` 使用确定性脚本模型，不需要 API Key，适合逐步观察状态和测试机制；`agent-console-demo` 连接真实 OpenAI-compatible 模型，展示同一会话中的持续对话、工具调用和人工审批。

建议先运行离线 Demo 理解 Runner，再运行控制台 Demo 验证所用模型是否正确支持结构化 ToolCall。

## 快速开发

在仓库根目录编译离线 Demo：

```bash
mvn -pl demos/agent-demo -am install -DskipTests
mvn -f demos/agent-demo/pom.xml exec:java
```

运行单个场景：

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=tool
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=approval
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=worker
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=planning
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=runtime
```

## Tool 调用

`ToolCallingAgentDemo` 演示模型返回 ToolCall、Runner 执行 Tool、ToolMessage 回到模型并生成最终回答。代码还展示执行策略、预算与工具调用上下文，是理解最小完整循环的起点。

重点观察一条用户消息只创建一个 AgentRun，而一次 Run 内可以发生多轮模型与工具交互。

## 人工审批

`HumanApprovalAgentDemo` 给高风险工具配置审批策略。第一次执行停在 `WAITING_FOR_APPROVAL` 并保存 Checkpoint；审批方查看工具名称和参数后提交结构化决定，另一个 Runner 恢复并继续执行。

这个过程适用于创建工单、发送通知、修改数据等需要人在副作用发生前确认的操作。

## Worker 与重试

`DurableWorkerAgentDemo` 让工具首次失败，Runner 将 Run 设置为 `RETRY_SCHEDULED`，Worker 到期后从 Store 领取任务。示例展示 Lease、持久化重试与恢复，不使用线程睡眠占住一次 HTTP 请求。

## 规划与子 Agent

`TaskPlanningAgentDemo` 开启 `AgentPlanningPolicy`。模型根据用户目标自主调用规划能力，创建可查询的任务列表，并把专业步骤派发给允许的 Agent。父 Run 等待子 Run 完成后继续汇总，整个过程仍使用统一 Snapshot 与 Worker。

## 运行时扩展

`RuntimeExtensionsAgentDemo` 集中演示：

- `AgentInvocationContext` 传递租户与业务服务；
- Middleware 拦截 step、模型和工具；
- 实时事件与持久化事件；
- 历史上下文压缩；
- 大型工具结果写入 Artifact Store。

这组能力常一起出现在生产系统中，因此 Demo 保留组合使用方式，而不是只测试单个接口。

## 真实模型控制台

先设置连接参数，API Key 不要写入源码：

```bash
export AGENT_DEMO_API_KEY="your-api-key"
export AGENT_DEMO_MODEL="gpt-4o-mini"

mvn -pl demos/agent-console-demo -am install -DskipTests
mvn -f demos/agent-console-demo/pom.xml exec:java
```

其他 OpenAI-compatible 服务还可配置：

```bash
export AGENT_DEMO_ENDPOINT="https://your-provider.example.com"
export AGENT_DEMO_REQUEST_PATH="/v1/chat/completions"
export AGENT_DEMO_MODEL="your-tool-calling-model"
```

进入控制台后可以连续输入：

```text
你好，我叫小明。
你还记得我叫什么吗？
上海现在几点？
帮我创建一个高优先级登录故障工单。
```

前两条展示 Conversation 维护多次 Run 的消息历史，第三条由模型决定调用查询工具，最后一条在工具执行前要求输入 `y` 或 `n`。图片等多模态消息可以使用 `UserMessage` 的内容 API 接入；具体支持格式取决于所选 ChatModel。

## 阅读源码

Demo 中有详细中文注释，建议按以下顺序阅读：

1. `ToolCallingAgentDemo`
2. `HumanApprovalAgentDemo`
3. `DurableWorkerAgentDemo`
4. `TaskPlanningAgentDemo`
5. `RuntimeExtensionsAgentDemo`
6. `AgentConsoleDemo`

离线 Demo 的 `DemoScriptedChatModel` 只返回预设消息，让每次运行都可重复。接入业务时，替换 Agent 的 `chatModel` 和 Tool 实现即可，Runner 的生命周期代码不需要随模型供应商改变。

