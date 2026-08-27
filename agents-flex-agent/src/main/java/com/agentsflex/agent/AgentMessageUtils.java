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

/**
 * Agent Snapshot 使用的消息深拷贝工具。
 */
final class AgentMessageUtils {

    /**
     * 工具类只提供静态深复制操作，禁止实例化。
     */
    private AgentMessageUtils() {
    }

    /**
     * 深复制消息列表并保持原始顺序。
     *
     * @param messages 原始消息；为 {@code null} 或空时返回空列表
     * @return 不与输入共享消息对象的新列表
     */
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

    /**
     * 按具体消息类型执行深复制，确保 Snapshot 不共享可变消息状态。
     *
     * @param message 支持复制的消息；允许为空
     * @return 保持运行时类型的副本，或 {@code null}
     * @throws IllegalStateException 遇到尚未支持的消息类型时抛出
     */
    static Message copyMessage(Message message) {
        if (message == null) {
            return null;
        }
        if (message instanceof AbstractTextMessage) {
            return ((AbstractTextMessage<?>) message).copy();
        }
        throw new IllegalStateException("Unsupported snapshot message type: " + message.getClass().getName());
    }

    /**
     * 深复制工具调用列表及其参数，并保留列表中的空占位。
     *
     * @param toolCalls 原始调用列表
     * @return 可独立修改的新列表
     */
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
