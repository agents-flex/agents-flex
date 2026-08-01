/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.agentsflex.core.agent.tool.ToolApprovalDecision;

/**
 * AgentRun 的可持久化暂停信息。
 *
 * <p>该对象不执行审批或用户交互，只记录暂停原因、关联事件以及恢复后的执行阶段。</p>
 */
public final class AgentSuspension implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AgentSuspensionType type;
    private final String correlationId;
    private final String message;
    private final AgentRunPhase resumePhase;
    private final Map<String, Object> metadata;

    public AgentSuspension(AgentSuspensionType type, String correlationId, String message,
                           AgentRunPhase resumePhase, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.correlationId = correlationId;
        this.message = message;
        this.resumePhase = resumePhase == null ? AgentRunPhase.MODEL : resumePhase;
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    public static AgentSuspension userInput(String message) {
        return new AgentSuspension(AgentSuspensionType.USER_INPUT, null, message,
            AgentRunPhase.MODEL, null);
    }

    public static AgentSuspension toolApproval(String callId, String toolName) {
        return toolApproval(callId, toolName, ToolApprovalDecision.REQUIRE_APPROVAL);
    }

    /** 创建携带策略代码、原因和审计元数据的工具审批暂停信息。 */
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
            message, AgentRunPhase.TOOLS, metadata);
    }

    public static AgentSuspension retry(String message, AgentRunPhase resumePhase, long nextRunAt) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nextRunAt", nextRunAt);
        return new AgentSuspension(AgentSuspensionType.RETRY, null, message, resumePhase, metadata);
    }

    public static AgentSuspension child(String childRunId) {
        return new AgentSuspension(AgentSuspensionType.CHILD_AGENT, childRunId,
            "Waiting for child AgentRun", AgentRunPhase.MODEL, null);
    }

    public AgentSuspensionType getType() {
        return type;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getMessage() {
        return message;
    }

    public AgentRunPhase getResumePhase() {
        return resumePhase;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    AgentSuspension copy() {
        return new AgentSuspension(type, correlationId, message, resumePhase, metadata);
    }
}
