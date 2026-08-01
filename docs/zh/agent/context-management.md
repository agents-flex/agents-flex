---
title: 多模态、调用上下文与上下文管理
description: 处理多模态消息、持续对话身份、模型消息窗口、历史压缩和大型工具结果外置。
---

# 多模态、调用上下文与上下文管理

## 概述

Agent 的“上下文”包含三种不同数据：对话消息、请求身份和可恢复业务状态。把它们全部塞进 Prompt 或 metadata 会造成泄密、序列化失败和恢复语义混乱。

- Conversation/Memory 保存模型需要看到的消息；
- AgentInvocationContext 保存本次进程内调用身份和服务；
- Run metadata/modeState 保存必须跨进程恢复的业务状态。

## 快速开发

```java
UserMessage message = new UserMessage("分析附件并给出风险摘要");
message.addImageUrl(imageUrl);
message.addFileUrl(fileUrl);

AgentRunOptions options = AgentRunOptions.builder()
    .invocationContext(AgentInvocationContext.builder()
        .tenantId("tenant-a")
        .userId("user-42")
        .sessionId("conversation-1001")
        .requestId("request-9001")
        .streaming(true)
        .build())
    .metadata("taskType", "risk-analysis")
    .build();

AgentRun run = runner.run(agent, message, options);
```

## 多模态消息

`UserMessage` 支持文本、imageUrls、audioUrls、videoUrls 和 fileUrls。Runner 将完整消息复制进 Run，Conversation 会在后续轮次保留它。模型是否支持图片、音频或文件由 ChatModel 适配器与目标模型共同决定，框架不会把不支持的模态自动降级为文本。

大型 base64 内容会显著增大 Snapshot。生产系统通常保存受控对象存储 URL，并在访问层处理短期签名和权限。

## Invocation Context

```java
AgentInvocationContext context = AgentInvocationContext.builder()
    .tenantId("tenant-a")
    .userId("user-42")
    .requestId("req-1")
    .attribute("region", "cn-east")
    .attribute(PermissionService.class, permissionService)
    .build();
```

Middleware、Tool 和 Worker 恢复流程可以读取该对象。它不会进入 Snapshot，适合请求身份、Trace、权限服务和进程内客户端，不适合必须在重启后保留的审批参数。

Worker 每次恢复 Run 时重新构建：

```java
AgentInvocationContextProvider provider = snapshot ->
    invocationContextService.forRun(snapshot);

AgentWorker worker = new AgentWorker(
    "worker-01", runner, 30_000, provider);
```

不要相信 Snapshot metadata 自己完成鉴权；Worker 应从可信身份系统重新解析权限。

## 模型可见窗口

`AgentContextPolicy` 只控制每次模型调用附带多少消息，不删除 Checkpoint 历史：

```java
Agent agent = Agent.builder("assistant")
    .chatModel(chatModel)
    .contextPolicy(AgentContextPolicy.recentMessages(20))
    .build();
```

也可以使用 `fullHistory()`。消息数量按协议消息计算，不按自然语言轮次计算，包含 ToolCall 时要给 AiMessage 和 ToolMessage 留出完整边界。

## 持久化历史压缩

只缩小发送窗口不能降低长期 Snapshot 体积。`MessageCountAgentContextManager` 在超过阈值时把旧消息变成摘要：

```java
AgentContextManager manager = new MessageCountAgentContextManager(
    50,
    12,
    (messages, invocation) -> summarizer.summarize(messages)
);
```

Runner 在模型调用前执行 Manager，并保存压缩后的 Checkpoint。实现会避免从 ToolMessage 中间切断工具协议。摘要模型应保留事实、约束、用户偏好和未完成事项，不应仅生成泛泛概括。

## 大型工具结果外置

```java
Agent agent = Agent.builder("data-agent")
    .chatModel(chatModel)
    .tool(exportTool)
    .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32_000))
    .build();

AgentRunner runner = AgentRunner.builder()
    .artifactStore(artifactStore)
    .build();
```

超过阈值后，原文写入 Artifact Store，ToolMessage 保存 artifactId、mediaType、size、checksum 和必要预览。Artifact 应有独立鉴权、保留期限和清理策略；不能因为拿到 runId 就默认允许下载所有内容。

## 数据选择原则

需要模型理解的内容放 Memory；需要跨进程恢复的纯数据放 metadata 或 modeState；只对当前调用有效的身份与服务放 Invocation Context；过大的正文放 Artifact Store。按这四类分开后，Prompt、Checkpoint 和安全边界都会更清晰。

