/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import java.io.Serializable;
import java.util.Map;

/**
 * 按 ToolCall 和审批阶段持久化的人工决定。
 *
 * <p>请求信息与人工响应信息分开保存，后续表单输入或错误重试不会覆盖审批人、审批意见及原始预检
 * 快照。Tool 主动审批还通过 requestFingerprint 与恢复后重新执行的只读预检结果绑定；同一 ToolCall
 * 的多个请求分别保存，使多级批准在每次从头执行 Tool 时都能继续生效。</p>
 */
public final class ToolApprovalRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    private final ToolApprovalStage stage;
    private final boolean approved;
    private final String requestFingerprint;
    private final String code;
    private final String message;
    private final String reason;
    private final Map<String, Object> requestMetadata;
    private final Map<String, Object> responseMetadata;
    private final String rejectionReason;
    private final long decidedAt;

    public ToolApprovalRecord(ToolApprovalStage stage, boolean approved,
                              String requestFingerprint, String code, String message,
                              String reason, Map<String, ?> requestMetadata,
                              Map<String, ?> responseMetadata, String rejectionReason,
                              long decidedAt) {
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        this.stage = stage;
        this.approved = approved;
        this.requestFingerprint = requestFingerprint;
        this.code = code;
        this.message = message;
        this.reason = reason;
        this.requestMetadata = ToolApprovalValues.immutableMap(requestMetadata);
        this.responseMetadata = ToolApprovalValues.immutableMap(responseMetadata);
        this.rejectionReason = rejectionReason;
        this.decidedAt = Math.max(0L, decidedAt);
    }

    public ToolApprovalStage getStage() {
        return stage;
    }

    public boolean isApproved() {
        return approved;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getRequestMetadata() {
        return ToolApprovalValues.immutableMap(requestMetadata);
    }

    public Map<String, Object> getResponseMetadata() {
        return ToolApprovalValues.immutableMap(responseMetadata);
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public long getDecidedAt() {
        return decidedAt;
    }
}
