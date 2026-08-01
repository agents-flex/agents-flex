/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.demo.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Function;

/**
 * 按入队顺序返回 AiMessage 的确定性模型。
 *
 * <p>Demo 的目标是学习 Agent 状态机，而不是依赖某个外部模型服务。每个脚本函数仍会收到真实
 * Prompt，因此可以检查 ToolMessage、子 Agent 结果和上下文是否按预期进入下一轮模型调用。</p>
 */
final class DemoScriptedChatModel implements ChatModel {

    private final Deque<Function<Prompt, AiMessage>> responses = new ArrayDeque<>();
    private int callCount;

    DemoScriptedChatModel enqueue(Function<Prompt, AiMessage> response) {
        // 每个函数对应一次模型调用，按入队顺序消费，便于精确表达多轮 ToolCall 场景。
        responses.add(response);
        return this;
    }

    int getCallCount() {
        return callCount;
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        if (responses.isEmpty()) {
            throw new IllegalStateException("脚本模型没有可用的下一条响应");
        }
        callCount++;
        // 脚本函数可以读取真实 Prompt，并对 Runner 维护的上下文做断言。
        AiMessage message = responses.removeFirst().apply(prompt);
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        return new AiMessageResponse(context, null, message);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        if (responses.isEmpty()) {
            throw new IllegalStateException("脚本模型没有可用的下一条响应");
        }
        callCount++;
        AiMessage full = responses.removeFirst().apply(prompt);
        ChatContext chatContext = new ChatContext();
        chatContext.setPrompt(prompt);
        StreamContext context = new StreamContext(this, chatContext, null);
        listener.onOpen(context);

        // 先发送可观察的增量，再发送带完整消息的结束帧。
        AiMessage delta = new AiMessage();
        if (full.hasToolCalls()) {
            delta.setReasoningContent("正在选择合适的工具");
            delta.setToolCalls(new ArrayList<>(full.getToolCalls()));
        } else {
            delta.setContent(full.getContent());
        }
        listener.onMessage(context, new AiMessageResponse(chatContext, null, delta));
        full.setFinished(true);
        context.setFullMessage(full);
        listener.onMessage(context, new AiMessageResponse(chatContext, null, full));
        listener.onClose(context);
    }
}
