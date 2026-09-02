/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.tool.ToolApprovalDecision;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * AgentTurn 的可持久化暂停信息。
 *
 * <p>该对象不执行审批或用户交互，只记录暂停原因、关联事件以及恢复后的执行阶段。它随
 * AgentTurnSnapshot 持久化，使另一个进程可以判断应接受哪一种恢复命令。</p>
 */
public final class AgentSuspension implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 暂停原因，决定允许使用的恢复命令类型。
     */
    private final AgentSuspensionType type;
    /**
     * ToolCall ID 等外部事件关联标识。
     */
    private final String correlationId;
    /**
     * 面向调用方展示的等待说明。
     */
    private final String message;
    /**
     * 恢复后继续执行的模型或工具阶段。
     */
    private final AgentTurnExecutionPoint resumeExecutionPoint;
    /**
     * 审批策略、重试时间等可持久化附加信息。
     */
    private final Map<String, Object> metadata;

    /**
     * 创建可持久化挂起点，并复制恢复所需元数据。
     *
     * @param type                 挂起原因
     * @param correlationId        工具调用关联 ID
     * @param message              面向调用方的等待说明
     * @param resumeExecutionPoint 恢复后继续执行的阶段
     * @param metadata             可序列化扩展数据
     */
    public AgentSuspension(AgentSuspensionType type, String correlationId, String message,
                           AgentTurnExecutionPoint resumeExecutionPoint, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.correlationId = correlationId;
        this.message = message;
        this.resumeExecutionPoint = resumeExecutionPoint == null ? AgentTurnExecutionPoint.INVOKE_MODEL : resumeExecutionPoint;
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * 创建等待使用者补充信息的暂停点。
     */
    public static AgentSuspension userInput(String message) {
        return userInput(message, 0);
    }

    /**
     * 创建带等待期限的用户输入暂停点；0 表示永不过期。
     */
    public static AgentSuspension userInput(String message, long timeoutMillis) {
        return createUserInput(null, message, AgentTurnExecutionPoint.INVOKE_MODEL, null, timeoutMillis);
    }

    /**
     * 创建由 request_user_input ToolCall 产生的结构化输入暂停点。
     *
     * <p>correlationId 绑定原 ToolCall，恢复到 PROCESS_TOOLS 阶段后由 Runner 写入匹配的 ToolMessage。</p>
     */
    public static AgentSuspension userInput(String callId, String message,
                                            Map<String, Object> metadata) {
        return userInput(callId, message, metadata, 0);
    }

    /**
     * 创建带等待期限的结构化用户输入暂停点；0 表示永不过期。
     */
    public static AgentSuspension userInput(String callId, String message,
                                            Map<String, Object> metadata, long timeoutMillis) {
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        return createUserInput(callId, message, AgentTurnExecutionPoint.PROCESS_TOOLS, metadata, timeoutMillis);
    }

    private static AgentSuspension createUserInput(String callId, String message,
                                                   AgentTurnExecutionPoint resumePoint,
                                                   Map<String, Object> sourceMetadata,
                                                   long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        Map<String, Object> metadata = sourceMetadata == null
            ? new HashMap<String, Object>() : new HashMap<>(sourceMetadata);
        metadata.put("requestedAt", System.currentTimeMillis());
        metadata.put("timeoutMillis", timeoutMillis);
        return new AgentSuspension(AgentSuspensionType.USER_INPUT, callId, message,
            resumePoint, metadata);
    }

    /**
     * 创建使用默认结构化决策的工具审批暂停点。
     */
    public static AgentSuspension toolApproval(String callId, String toolName) {
        return toolApproval(callId, toolName, ToolApprovalDecision.REQUIRE_APPROVAL);
    }

    /**
     * 创建携带策略代码、原因和审计元数据的工具审批暂停信息。
     */
    public static AgentSuspension toolApproval(String callId, String toolName,
                                               ToolApprovalDecision decision) {
        return toolApproval(callId, toolName, decision, 0);
    }

    /**
     * 创建带等待期限的工具审批暂停点；0 表示永不过期。
     */
    public static AgentSuspension toolApproval(String callId, String toolName,
                                               ToolApprovalDecision decision, long timeoutMillis) {
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolName", toolName);
        if (decision != null) {
            metadata.put("approvalOutcome", decision.getOutcome().name());
            metadata.put("approvalCode", decision.getCode());
            metadata.put("approvalReason", decision.getReason());
            metadata.putAll(decision.getMetadata());
        }
        metadata.put("requestedAt", System.currentTimeMillis());
        metadata.put("timeoutMillis", timeoutMillis);
        String message = decision != null && decision.getMessage() != null
            ? decision.getMessage() : "Tool approval is required: " + toolName;
        return new AgentSuspension(AgentSuspensionType.TOOL_APPROVAL, callId,
            message, AgentTurnExecutionPoint.PROCESS_TOOLS, metadata);
    }

    /**
     * 创建等待外部执行器返回指定 ToolCall 结果的暂停点。
     */
    public static AgentSuspension externalTool(String callId, String toolName,
                                               String arguments,
                                               Map<String, Object> toolMetadata) {
        return externalTool(callId, toolName, arguments, toolMetadata, 0);
    }

    /**
     * 创建带结果等待期限的外部工具暂停点。
     *
     * @param timeoutMillis 等待期限，0 表示不限制
     */
    public static AgentSuspension externalTool(String callId, String toolName,
                                               String arguments,
                                               Map<String, Object> toolMetadata,
                                               long timeoutMillis) {
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolName", toolName);
        metadata.put("arguments", arguments);
        metadata.put("requestedAt", System.currentTimeMillis());
        metadata.put("timeoutMillis", timeoutMillis);
        if (toolMetadata != null && !toolMetadata.isEmpty()) {
            metadata.put("toolMetadata", new HashMap<String, Object>(toolMetadata));
        }
        return new AgentSuspension(AgentSuspensionType.EXTERNAL_TOOL, callId,
            "External tool result is required: " + toolName,
            AgentTurnExecutionPoint.PROCESS_TOOLS, metadata);
    }

    /**
     * 创建等待指定时间后自动重试的暂停点。
     */
    public static AgentSuspension retry(String message, AgentTurnExecutionPoint resumeExecutionPoint, long nextRunnableAt) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nextRunnableAt", nextRunnableAt);
        return new AgentSuspension(AgentSuspensionType.RETRY, null, message, resumeExecutionPoint, metadata);
    }

    /**
     * @return 暂停原因类型
     */
    public AgentSuspensionType getType() {
        return type;
    }

    /**
     * @return ToolCall ID 等关联标识
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * @return 面向调用方的等待说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 应在恢复后继续执行的阶段
     */
    public AgentTurnExecutionPoint getResumeExecutionPoint() {
        return resumeExecutionPoint;
    }

    /**
     * @return 不可修改的暂停元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * @return 挂起创建时间；旧快照没有该字段时返回 0。
     */
    public long getRequestedAt() {
        Object value = metadata.get("requestedAt");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * @return 挂起等待期限；0 表示不限制。
     */
    public long getTimeoutMillis() {
        Object value = metadata.get("timeoutMillis");
        return value instanceof Number ? Math.max(0L, ((Number) value).longValue()) : 0L;
    }

    /**
     * 使用 TurnStore 的统一时钟重写等待起点，避免多节点本机时钟偏差。
     */
    AgentSuspension withRequestedAt(long value) {
        if (getTimeoutMillis() <= 0) return this;
        Map<String, Object> values = new HashMap<>(metadata);
        values.put("requestedAt", value);
        return new AgentSuspension(type, correlationId, message, resumeExecutionPoint, values);
    }

    /**
     * @return 与当前暂停信息隔离的副本
     */
    AgentSuspension copy() {
        return new AgentSuspension(type, correlationId, message, getResumeExecutionPoint(), metadata);
    }
}
