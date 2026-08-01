/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.AgentInvocationContext;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunOptions;
import com.agentsflex.core.agent.AgentRunStatus;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.agent.context.InMemoryAgentArtifactStore;
import com.agentsflex.core.agent.context.MessageCountAgentContextManager;
import com.agentsflex.core.agent.context.ToolResultOffloadPolicy;
import com.agentsflex.core.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.core.agent.event.AgentRuntimeEvent;
import com.agentsflex.core.agent.event.AgentRuntimeEventType;
import com.agentsflex.core.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.core.agent.middleware.AgentMiddleware;
import com.agentsflex.core.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.core.agent.middleware.AgentModelCallChain;
import com.agentsflex.core.agent.middleware.AgentToolCallChain;
import com.agentsflex.core.agent.middleware.AgentToolCallContext;
import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.core.agent.tool.InMemoryAgentToolRegistry;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 演示调用上下文、Middleware、实时事件、上下文压缩和大型工具结果外置。 */
public final class RuntimeExtensionsAgentDemo {

    private RuntimeExtensionsAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 5 - Agent 运行时扩展");

        InMemoryAgentArtifactStore artifactStore = new InMemoryAgentArtifactStore();
        List<AgentRuntimeEvent> runtimeEvents = new ArrayList<>();

        // 工具可以读取调用上下文，并通过 ToolContext 主动报告可展示的执行进度。
        Tool reportTool = Tool.builder("build_report", "生成一份较大的分析报告")
            .function(arguments -> {
                AgentInvocationContext invocation = ToolContextHolder.currentContext()
                    .getAttribute(AgentInvocationContext.CONTEXT_ATTRIBUTE);
                AgentToolProgressEmitter progress = ToolContextHolder.currentContext()
                    .getAttribute(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE);
                progress.emit("正在生成报告", Collections.singletonMap("percent", 50));
                char[] body = new char[96];
                Arrays.fill(body, 'R');
                return invocation.getTenantId() + ":" + new String(body);
            })
            .build();

        // Middleware 适合做租户权限、限流、缓存、Prompt 处理和统一观测。
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                System.out.println("model tenant : "
                    + context.getInvocationContext().getTenantId());
                return chain.proceed(context);
            }

            @Override
            public Object aroundToolCall(AgentToolCallContext context,
                                         AgentToolCallChain chain) {
                System.out.println("tool request : "
                    + context.getInvocationContext().getRequestId());
                return chain.proceed(context);
            }
        };

        DemoScriptedChatModel model = new DemoScriptedChatModel()
            .enqueue(prompt -> DemoSupport.toolCalls(
                new ToolCall("report-call-1", "build_report", "{}")))
            .enqueue(prompt -> new AiMessage("报告已经生成，完整内容保存在 Artifact Store。"));

        Agent agent = Agent.builder("runtime-agent")
            .chatModel(model)
            .tool(reportTool)
            .middleware(middleware)
            // 历史超过 5 条时，将旧消息压缩为摘要并保留最近 3 条。
            .contextManager(new MessageCountAgentContextManager(5, 3,
                (messages, context) -> "已压缩 " + messages.size() + " 条较早消息"))
            // 大于 32 个字符的工具结果保存到 Artifact Store。
            .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32))
            .build();

        AgentRunner runner = AgentRunner.builder()
            .runStore(new InMemoryAgentRunStore())
            .agentRegistry(new InMemoryAgentRegistry())
            .toolRegistry(new InMemoryAgentToolRegistry())
            .eventStore(new InMemoryAgentRunEventStore())
            .commandStore(new InMemoryAgentRunCommandStore())
            .artifactStore(artifactStore)
            .build()
            .addRuntimeEventListener(runtimeEvents::add);

        AgentInvocationContext invocation = AgentInvocationContext.builder()
            .tenantId("tenant-demo")
            .userId("developer")
            .requestId("request-runtime-1")
            .streaming(true)
            .build();
        AgentRun run = AgentRun.start(agent, "开始生成报告",
            AgentRunOptions.builder().invocationContext(invocation).build());
        // 添加旧历史以触发压缩；这些消息会进入真实 AgentRun 和 Checkpoint。
        for (int i = 1; i <= 6; i++) {
            run.getPrompt().addUserMessage("旧消息 " + i);
        }

        AgentRun completed = runner.run(run);
        DemoSupport.require(completed.getStatus() == AgentRunStatus.COMPLETED,
            "运行时扩展场景应正常完成");

        ToolMessage toolMessage = null;
        for (Message message : completed.getPrompt().getMemory().getMessages(Integer.MAX_VALUE)) {
            if (message instanceof ToolMessage) toolMessage = (ToolMessage) message;
        }
        String artifactId = (String) toolMessage.getMetadata("agent.artifact.id");
        DemoSupport.require(artifactId != null, "大型工具结果应被外置");
        System.out.println("artifact id  : " + artifactId);
        System.out.println("artifact size: " + artifactStore.load(artifactId).length());

        System.out.println("runtime events:");
        for (AgentRuntimeEvent event : runtimeEvents) {
            if (event.getType() == AgentRuntimeEventType.MODEL_REASONING_DELTA
                || event.getType() == AgentRuntimeEventType.MODEL_TOOL_CALL_DELTA
                || event.getType() == AgentRuntimeEventType.TOOL_PROGRESS
                || event.getType() == AgentRuntimeEventType.CONTEXT_COMPACTED
                || event.getType() == AgentRuntimeEventType.TOOL_RESULT_OFFLOADED) {
                System.out.println("  " + event.getSequence() + " "
                    + event.getType() + " " + event.getData());
            }
        }
    }
}
