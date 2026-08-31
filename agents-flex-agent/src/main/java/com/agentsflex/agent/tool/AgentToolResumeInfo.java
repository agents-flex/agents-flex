/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具本次执行对应的恢复来源、重试序号和可持久化审计信息。
 */
public final class AgentToolResumeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private final AgentToolResumeType type;
    private final int resumeCount;
    private final Map<String, Object> metadata;
    private final String previousErrorType;
    private final String previousErrorMessage;

    public AgentToolResumeInfo(AgentToolResumeType type, int resumeCount,
                               Map<String, ?> metadata, String previousErrorType,
                               String previousErrorMessage) {
        this.type = type == null ? AgentToolResumeType.NONE : type;
        this.resumeCount = Math.max(0, resumeCount);
        this.metadata = metadata == null || metadata.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.previousErrorType = previousErrorType;
        this.previousErrorMessage = previousErrorMessage;
    }

    public static AgentToolResumeInfo none() {
        return new AgentToolResumeInfo(AgentToolResumeType.NONE, 0, null, null, null);
    }

    public AgentToolResumeType getType() {
        return type;
    }

    public int getResumeCount() {
        return resumeCount;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getPreviousErrorType() {
        return previousErrorType;
    }

    public String getPreviousErrorMessage() {
        return previousErrorMessage;
    }

    public boolean isResumed() {
        return type != AgentToolResumeType.NONE;
    }
}
