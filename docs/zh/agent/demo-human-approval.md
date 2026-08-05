---
title: Demo：人工审批
description: 构建高风险工具审批流程，并通过 submitResume 和 Worker 跨请求恢复。
---

# Demo：人工审批

## 概述

本示例模拟生产发布：模型生成部署参数后，Runner 在工具函数执行前保存 Snapshot 并等待人工批准；
ChatMemory 同时出现待处理审批消息；审批服务调用 `submitResume` 后 CAS 更新原消息；后台 Worker 使用
另一个 Runner 领取原 Turn 并执行一次部署。

完整源码位于 `demos/agent-demo/src/main/java/com/agentsflex/demo/agent/HumanApprovalAgentDemo.java`。

## 定义高风险工具

```java
AtomicInteger deployments = new AtomicInteger();

Tool deployTool = Tool.builder("deploy_service", "将服务部署到生产环境")
    .addParameter(Parameter.builder()
        .name("service").type("string").required(true).build())
    .addParameter(Parameter.builder()
        .name("version").type("string").required(true).build())
    .metadata("riskLevel", "HIGH")
    .metadata("sideEffect", true)
    .function(arguments -> {
        deployments.incrementAndGet();
        return "已部署 " + arguments.get("service")
            + ":" + arguments.get("version");
    })
    .build();
```

生产工具应另外使用 `AgentToolInvocation` 的稳定调用 ID 访问真实发布平台，并以该 ID 实现幂等。计数器只用于 Demo 证明审批前没有副作用。

## 配置审批策略

```java
Agent agent = Agent.builder("release-agent")
    .id("release-agent")
    .version("1")
    .instructions("部署生产环境必须调用 deploy_service，并等待人工批准。")
    .chatModel(model)
    .tool(deployTool)
    .toolApprovalPolicy((turn, call, tool) ->
        Boolean.TRUE.equals(tool.getMetadata().get("sideEffect"))
            ? ToolApprovalDecision.requireApproval()
                .code("PRODUCTION_DEPLOYMENT_REVIEW")
                .message("生产发布需要人工批准")
                .reason("部署工具会修改生产环境")
                .metadata("riskLevel", tool.getMetadata().get("riskLevel"))
                .build()
            : ToolApprovalDecision.ALLOW)
    .build();
```

策略返回结构化 Decision，而不仅是 boolean，方便审批 UI 和审计记录策略代码、说明、理由与风险元数据。

## 执行到审批点

两个 Runner 共享 TurnStore、AgentLoader 和业务 ChatMemory：

```java
AgentRunner firstRunner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .chatMemoryProvider(id -> chatMemory)
    .build();
```

```java
AgentTurn waiting = firstRunner.run(
    agent, "release-conversation-1", "发布 order-api 2.4.0");

if (waiting.getStatus() != AgentTurnStatus.WAITING_FOR_APPROVAL) {
    throw new IllegalStateException("expected approval");
}
if (deployments.get() != 0) {
    throw new IllegalStateException("tool ran before approval");
}
```

此时模型返回的 `deploy_service` ToolCall 已在 Snapshot 的 `pendingToolCalls` 中。页面通过
`chatMemory.getMessages(...)` 读取 `AgentActionMessage`；其状态为 `PENDING`，但
`getModelMessages(...)` 不会返回这条页面消息。

## 提交审批结果

```java
String callId = waiting.getSuspension().getCorrelationId();
AgentResumeCommand approval = AgentResumeCommand.approveTool(callId)
    .withMetadata("approverId", "admin-1001")
    .withMetadata("approvalSource", "release-console");

secondRunner.submitResume(waiting.getId(), approval);
```

`submitResume` 只将 Turn 保存为可运行状态，不会在审批 HTTP 请求中执行部署。成功后，原
`AgentActionMessage` 通过 expectedVersion CAS 更新为 `APPROVED`，`getActions()` 返回空列表，页面
无需额外关联一条结果消息。生产环境应先由审批业务保存唯一事件 ID 并完成幂等消费，再调用该方法。

## Worker 恢复

```java
List<AgentTurn> processed;
try (AgentWorker worker = new AgentWorker(
    "release-worker-01", secondRunner, 30_000)) {
    processed = worker.pollAndRun(10);
}

AgentTurn completed = processed.get(0);
System.out.println(completed.getFinalOutput());
```

Worker 领取已恢复的 Turn。Runner 从 TOOLS phase 执行原调用，写入 ToolMessage 后请求模型生成最终答案，不会重新生成部署参数。

## 拒绝审批

```java
AgentResumeCommand rejection = AgentResumeCommand.rejectTool(
    callId, "变更窗口尚未开始");
```

拒绝不会调用工具函数。Runner 把拒绝结果关联到原 ToolCall，再由模型形成面向用户的说明。

## 生产化改造

- 用 JDBC/Redis Store 替换全部内存 Store。
- 审批 API 校验当前用户、租户、Turn 状态与 correlationId。
- ToolCall 参数只展示白名单字段并脱敏。
- 业务审批事件 ID、turnId、callId 与 approverId 进入审计记录。
- 部署平台以稳定调用 ID 建唯一幂等记录。
- 对超时未审批任务设置业务撤销或过期策略。

## 运行仓库 Demo

```bash
mvn -pl demos/agent-demo -am test
mvn -f demos/agent-demo/pom.xml exec:java \
  -Dexec.mainClass=com.agentsflex.demo.agent.HumanApprovalAgentDemo
```
