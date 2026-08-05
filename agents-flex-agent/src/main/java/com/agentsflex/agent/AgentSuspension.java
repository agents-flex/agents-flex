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
     * ToolCall ID 或子 Turn ID 等外部事件关联标识。
     */
    private final String correlationId;
    /**
     * 面向调用方展示的等待说明。
     */
    private final String message;
    /**
     * 恢复后继续执行的模型或工具阶段。
     */
    private final AgentTurnPhase resumePhase;
    /**
     * 审批策略、重试时间等可持久化附加信息。
     */
    private final Map<String, Object> metadata;

    public AgentSuspension(AgentSuspensionType type, String correlationId, String message,
                           AgentTurnPhase resumePhase, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.correlationId = correlationId;
        this.message = message;
        this.resumePhase = resumePhase == null ? AgentTurnPhase.MODEL : resumePhase;
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * 创建等待使用者补充信息的暂停点。
     */
    public static AgentSuspension userInput(String message) {
        return new AgentSuspension(AgentSuspensionType.USER_INPUT, null, message,
            AgentTurnPhase.MODEL, null);
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
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolName", toolName);
        if (decision != null) {
            metadata.put("approvalOutcome", decision.getOutcome().name());
            metadata.put("approvalCode", decision.getCode());
            metadata.put("approvalReason", decision.getReason());
            metadata.putAll(decision.getMetadata());
        }
        String message = decision != null && decision.getMessage() != null
            ? decision.getMessage() : "Tool approval is required: " + toolName;
        return new AgentSuspension(AgentSuspensionType.TOOL_APPROVAL, callId,
            message, AgentTurnPhase.TOOLS, metadata);
    }

    /**
     * 创建等待指定时间后自动重试的暂停点。
     */
    public static AgentSuspension retry(String message, AgentTurnPhase resumePhase, long nextRunnableAt) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nextRunnableAt", nextRunnableAt);
        return new AgentSuspension(AgentSuspensionType.RETRY, null, message, resumePhase, metadata);
    }

    /**
     * 创建等待指定子 Turn 结束的暂停点。
     */
    public static AgentSuspension child(String childTurnId) {
        return new AgentSuspension(AgentSuspensionType.CHILD_AGENT, childTurnId,
            "Waiting for child AgentTurn", AgentTurnPhase.MODEL, null);
    }

    /**
     * @return 暂停原因类型
     */
    public AgentSuspensionType getType() {
        return type;
    }

    /**
     * @return ToolCall ID 或子 Turn ID 等关联标识
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
    public AgentTurnPhase getResumePhase() {
        return resumePhase;
    }

    /**
     * @return 不可修改的暂停元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * @return 与当前暂停信息隔离的副本
     */
    AgentSuspension copy() {
        return new AgentSuspension(type, correlationId, message, resumePhase, metadata);
    }
}
