---
title: Demo：运行上下文与实时事件
description: 运行 Invocation Context、Middleware、模型流式增量、工具进度、上下文压缩和大型结果外置组合场景。
---

# Demo：运行上下文与实时事件

<div v-pre>

对应源码：`demos/agent-demo/src/main/java/com/agentsflex/demo/agent/RuntimeExtensionsAgentDemo.java`

## 运行

```bash
mvn -f demos/agent-demo/pom.xml exec:java -Dexec.args=runtime
```

## 场景内容

该 Demo 在一次可离线执行的 Run 中组合以下能力：

1. `AgentInvocationContext` 携带 tenantId、userId 和 requestId；
2. 模型与工具 Middleware 读取同一份调用上下文；
3. `streaming=true` 让脚本模型通过 `chatStream` 返回增量；
4. 实时事件输出 reasoning delta 和 ToolCall delta；
5. 长工具通过 `AgentToolProgressEmitter` 上报 50% 进度；
6. 旧消息由 `MessageCountAgentContextManager` 压缩为摘要；
7. 大型工具结果保存到 `InMemoryAgentArtifactStore`；
8. ToolMessage 只保留 Artifact 引用，最终模型继续完成回答。

## Agent 定义

```java
Agent agent = Agent.builder("runtime-agent")
    .chatModel(model)
    .tool(reportTool)
    .middleware(middleware)
    .contextManager(new MessageCountAgentContextManager(
        5,
        3,
        (messages, context) -> "已压缩 " + messages.size() + " 条较早消息"
    ))
    .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32))
    .build();
```

## 调用上下文

```java
AgentInvocationContext invocation = AgentInvocationContext.builder()
    .tenantId("tenant-demo")
    .userId("developer")
    .requestId("request-runtime-1")
    .streaming(true)
    .build();
```

Invocation Context 不进入 Snapshot。Demo 在同一进程连续执行，所以 Middleware 和 Tool 都能读取它；
跨进程恢复时应由新的请求或 Worker 重新构造。

## 工具进度

```java
AgentToolProgressEmitter progress = ToolContextHolder.currentContext()
    .getAttribute(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE);

progress.emit(
    "正在生成报告",
    Collections.singletonMap("percent", 50)
);
```

它会生成 `TOOL_PROGRESS` Runtime Event，不修改 Run 状态，也不会为每次进度都写 Snapshot。

## 结果外置

Demo 最后从 ToolMessage metadata 读取：

```java
String artifactId = (String) toolMessage.getMetadata("agent.artifact.id");
String fullContent = artifactStore.load(artifactId);
```

真实平台应替换为共享 Artifact Store，并通过受控工具让模型分页查询完整内容，而不是自动把大型原文重新
装回 Prompt。

## 预期输出

输出中会包含：

```text
model tenant : tenant-demo
tool request : request-runtime-1
artifact id  : ...
runtime events:
  ... CONTEXT_COMPACTED ...
  ... MODEL_REASONING_DELTA ...
  ... MODEL_TOOL_CALL_DELTA ...
  ... TOOL_PROGRESS ...
  ... TOOL_RESULT_OFFLOADED ...
```

事件 sequence 在同一个 Runner 和 Run 内递增，可直接映射到 WebSocket 或 SSE 前端消息顺序。

</div>
