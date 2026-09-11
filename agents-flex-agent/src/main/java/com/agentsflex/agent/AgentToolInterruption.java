/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import java.io.Serializable;

/**
 * 一次尚未完成的 ToolCall 被用户消息中断的持久化审计记录。
 *
 * <p>中断记录与写入 Prompt 的 {@code ToolMessage} 分工不同：ToolMessage 用于闭合模型协议，
 * 本对象用于控制面审计、外部执行器取消和迟到结果诊断。两者必须在同一个 Snapshot 中保存，
 * 这样进程重启后仍能解释某个 ToolCall 为什么没有继续执行。</p>
 *
 * <p>{@code sourceMessageId} 是触发中断的用户消息 ID。调用方重试提交同一条消息时，Runner
 * 可以据此识别重复请求，避免再次追加 ToolMessage 或用户消息。</p>
 */
public final class AgentToolInterruption implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String toolCallId;
    private final String toolName;
    private final AgentSuspensionType suspensionType;
    private final String reason;
    private final long occurredAt;
    private final String sourceMessageId;

    public AgentToolInterruption(String toolCallId, String toolName,
                                 AgentSuspensionType suspensionType, String reason,
                                 long occurredAt, String sourceMessageId) {
        if (toolCallId == null || toolCallId.trim().isEmpty()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (suspensionType == null) {
            throw new IllegalArgumentException("suspensionType must not be null");
        }
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.suspensionType = suspensionType;
        this.reason = reason;
        this.occurredAt = Math.max(0L, occurredAt);
        this.sourceMessageId = sourceMessageId;
    }

    AgentToolInterruption copy() {
        return new AgentToolInterruption(toolCallId, toolName, suspensionType, reason,
            occurredAt, sourceMessageId);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public AgentSuspensionType getSuspensionType() {
        return suspensionType;
    }

    public String getReason() {
        return reason;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }
}
