/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.AbstractTextMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Agent Snapshot 使用的消息深拷贝工具。 */
final class AgentMessageUtils {

    private AgentMessageUtils() {
    }

    static List<Message> copyMessages(List<? extends Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> copies = new ArrayList<>(messages.size());
        for (Message message : messages) {
            copies.add(copyMessage(message));
        }
        return copies;
    }

    static Message copyMessage(Message message) {
        if (message == null) {
            return null;
        }
        if (message instanceof AbstractTextMessage) {
            return ((AbstractTextMessage<?>) message).copy();
        }
        throw new IllegalStateException("Unsupported snapshot message type: " + message.getClass().getName());
    }

    static List<ToolCall> copyToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolCall> copies = new ArrayList<>(toolCalls.size());
        for (ToolCall toolCall : toolCalls) {
            copies.add(toolCall == null ? null : toolCall.copy());
        }
        return copies;
    }
}
