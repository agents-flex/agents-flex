/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验上下文压缩器输出仍然满足模型会话协议。
 *
 * <p>即时压缩和增量压缩必须使用同一套规则，否则非法摘要可能只在增量路径中进入持久化状态，
 * 并在后续所有模型调用中持续产生孤立 ToolMessage 或未闭合 ToolCall。</p>
 */
public final class AgentContextCompressionValidator {

    private AgentContextCompressionValidator() {
    }

    /**
     * 校验压缩结果的角色顺序、可见性和工具调用配对关系。
     *
     * @param messages 压缩器生成的模型消息
     * @throws IllegalArgumentException 角色非法或工具协议不完整时抛出
     * @throws IllegalStateException    结果为空时抛出
     */
    public static void validate(List<Message> messages) {
        validate(messages, true);
    }

    /**
     * 校验压缩结果；需要时额外要求摘要以 UserMessage（可带 SystemMessage）开始。
     * 即时和增量摘要都作为模型上下文的历史事实使用，因此统一要求合法的会话起始角色。
     */
    public static void validate(List<Message> messages, boolean requireConversationStart) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("contextCompressor returned no summary messages");
        }
        AiMessage lastToolCallMessage = null;
        Set<String> returnedToolCallIds = new HashSet<>();
        Set<String> expectedToolCallIds = new HashSet<>();
        Set<String> allToolCallIds = new HashSet<>();
        Set<String> allToolMessageIds = new HashSet<>();
        for (Message message : messages) {
            if (message == null || !message.isModelVisible()) {
                throw new IllegalArgumentException("contextCompressor result contains invalid message");
            }
            if (message instanceof ToolMessage) {
                ToolMessage result = (ToolMessage) message;
                if (lastToolCallMessage == null || !matchesToolCall(lastToolCallMessage, result)
                    || !returnedToolCallIds.add(result.getToolCallId())
                    || !allToolMessageIds.add(result.getToolCallId())) {
                    throw new IllegalArgumentException("contextCompressor result contains orphan ToolMessage");
                }
                expectedToolCallIds.remove(result.getToolCallId());
            } else if (message instanceof AiMessage && ((AiMessage) message).hasToolCalls()) {
                if (!expectedToolCallIds.isEmpty()) {
                    throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
                }
                lastToolCallMessage = (AiMessage) message;
                returnedToolCallIds.clear();
                for (ToolCall call : lastToolCallMessage.getToolCalls()) {
                    if (call == null || call.getId() == null || call.getId().trim().isEmpty()
                        || !expectedToolCallIds.add(call.getId())
                        || !allToolCallIds.add(call.getId())) {
                        throw new IllegalArgumentException(
                            "contextCompressor result contains ToolCall without a unique id");
                    }
                }
            } else {
                if (!expectedToolCallIds.isEmpty()) {
                    throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
                }
                lastToolCallMessage = null;
                returnedToolCallIds.clear();
            }
        }
        if (!expectedToolCallIds.isEmpty()) {
            throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
        }
        if (requireConversationStart) {
            int firstConversationMessage = messages.get(0) instanceof SystemMessage ? 1 : 0;
            if (firstConversationMessage >= messages.size()
                || !(messages.get(firstConversationMessage) instanceof UserMessage)) {
                throw new IllegalArgumentException(
                    "contextCompressor result must start with SystemMessage optionally followed by UserMessage");
            }
        }
    }

    private static boolean matchesToolCall(AiMessage assistant, ToolMessage result) {
        if (result.getToolCallId() == null) return false;
        for (ToolCall call : assistant.getToolCalls()) {
            if (call != null && result.getToolCallId().equals(call.getId())) return true;
        }
        return false;
    }
}
