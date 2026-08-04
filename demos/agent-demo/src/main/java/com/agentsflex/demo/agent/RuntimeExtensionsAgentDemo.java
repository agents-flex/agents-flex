/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentRunOptions;
import com.agentsflex.agent.AgentRunStatus;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.context.MessageCountAgentContextManager;
import com.agentsflex.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.middleware.AgentToolCallContext;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolContextHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 演示 Middleware、实时事件和上下文压缩。 */
public final class RuntimeExtensionsAgentDemo {

    private RuntimeExtensionsAgentDemo() {
    }

    public static void main(String[] args) {
        run();
    }

    static void run() {
        DemoSupport.section("Demo 5 - Agent 运行时扩展");

        List<AgentEvent> events = new ArrayList<>();

        // 工具通过 ToolContext 主动报告可展示的执行进度。
        Tool reportTool = Tool.builder("build_report", "生成一份较大的分析报告")
            .function(arguments -> {
                AgentToolProgressEmitter progress = ToolContextHolder.currentContext()
                    .getAttribute(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE);
                progress.emit("正在生成报告", Collections.singletonMap("percent", 50));
                char[] body = new char[96];
                Arrays.fill(body, 'R');
                return new String(body);
            })
            .build();

        // Middleware 适合做租户权限、限流、缓存、Prompt 处理和统一观测。
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                System.out.println("model tenant : "
                    + context.getRun().getMetadata().get("tenantId"));
                return chain.proceed(context);
            }

            @Override
            public Object aroundToolCall(AgentToolCallContext context,
                                         AgentToolCallChain chain) {
                System.out.println("tool request : "
                    + context.getRun().getMetadata().get("requestId"));
                return chain.proceed(context);
            }
        };

        DemoScriptedChatModel model = new DemoScriptedChatModel()
            .enqueue(prompt -> DemoSupport.toolCalls(
                new ToolCall("report-call-1", "build_report", "{}")))
            .enqueue(prompt -> new AiMessage("报告已经生成。"));

        Agent agent = Agent.builder("runtime-agent")
            .chatModel(model)
            .tool(reportTool)
            .middleware(middleware)
            // 历史超过 5 条时，将旧消息压缩为摘要并保留最近 3 条。
            .contextManager(new MessageCountAgentContextManager(5, 3,
                messages -> "已压缩 " + messages.size() + " 条较早消息"))
            .build();

        AgentRunner runner = AgentRunner.builder()
            .runStore(new InMemoryAgentRunStore())
            .agentLoader(new InMemoryAgentLoader(agent))
            .commandStore(new InMemoryAgentRunCommandStore())
            .build()
            .addEventListener(events::add);

        // 使用 Runner 的公开入口携带旧历史创建 Run，确保初始状态立即写入 Snapshot。
        List<Message> conversationHistory = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            conversationHistory.add(new UserMessage("旧消息 " + i));
        }
        AgentRun completed = runner.run(agent, conversationHistory,
            new UserMessage("开始生成报告"),
            AgentRunOptions.builder()
                .metadata("tenantId", "tenant-demo")
                .metadata("userId", "developer")
                .metadata("requestId", "request-runtime-1")
                .streaming(true)
                .build());
        DemoSupport.require(completed.getStatus() == AgentRunStatus.COMPLETED,
            "运行时扩展场景应正常完成");

        ToolMessage toolMessage = null;
        for (Message message : completed.getPrompt().getMemory().getMessages(Integer.MAX_VALUE)) {
            if (message instanceof ToolMessage) toolMessage = (ToolMessage) message;
        }
        DemoSupport.require(toolMessage != null && toolMessage.getContent().length() == 96,
            "工具结果应原样写入运行历史");
        System.out.println("tool result size: " + toolMessage.getContent().length());

        System.out.println("events:");
        for (AgentEvent event : events) {
            if (event.getType() == AgentEventType.MODEL_REASONING_DELTA
                || event.getType() == AgentEventType.MODEL_TOOL_CALL_DELTA
                || event.getType() == AgentEventType.TOOL_PROGRESS
                || event.getType() == AgentEventType.CONTEXT_COMPACTED) {
                System.out.println("  " + event.getSequence() + " "
                    + event.getType() + " " + event.getData());
            }
        }
    }
}
