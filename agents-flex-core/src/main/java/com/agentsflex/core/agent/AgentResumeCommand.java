/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部系统用于恢复暂停任务的不可变命令。
 *
 * <p>命令类型必须与当前暂停原因匹配。例如，等待工具审批的 Run 只接受批准或拒绝命令，等待用户输入
 * 的 Run 只接受用户输入命令。correlationId 用于校验工具调用或子任务，避免迟到事件恢复错误任务。</p>
 */
public final class AgentResumeCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 恢复动作类型。 */
    private final AgentResumeCommandType type;
    /** 用户输入、拒绝原因等文本内容。 */
    private final String content;
    /** 工具调用 ID 或子运行 ID。 */
    private final String correlationId;
    /** 业务系统附加的只读元数据。 */
    private final Map<String, Object> metadata;

    public AgentResumeCommand(AgentResumeCommandType type, String content,
                              String correlationId, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.content = content;
        this.correlationId = correlationId;
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /** 创建不携带额外数据的继续命令。 */
    public static AgentResumeCommand continueRun() {
        return new AgentResumeCommand(AgentResumeCommandType.CONTINUE, null, null, null);
    }

    /** 创建补充用户输入的恢复命令。 */
    public static AgentResumeCommand userInput(String content) {
        return new AgentResumeCommand(AgentResumeCommandType.USER_INPUT, content, null, null);
    }

    /** 创建批准指定工具调用的恢复命令。 */
    public static AgentResumeCommand approveTool(String callId) {
        return new AgentResumeCommand(AgentResumeCommandType.APPROVE_TOOL, null, callId, null);
    }

    /** 创建拒绝指定工具调用的恢复命令。 */
    public static AgentResumeCommand rejectTool(String callId, String reason) {
        return new AgentResumeCommand(AgentResumeCommandType.REJECT_TOOL, reason, callId, null);
    }

    /** 创建执行已到期自动重试的恢复命令。 */
    public static AgentResumeCommand retry() {
        return new AgentResumeCommand(AgentResumeCommandType.RETRY, null, null, null);
    }

    /** 创建通知指定子运行已经结束的恢复命令。 */
    public static AgentResumeCommand childCompleted(String childRunId) {
        return new AgentResumeCommand(AgentResumeCommandType.CHILD_COMPLETED, null, childRunId, null);
    }

    /** 返回附加一项审计元数据的新命令。 */
    public AgentResumeCommand withMetadata(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("metadata key must not be null");
        }
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.put(key, value);
        return new AgentResumeCommand(type, content, correlationId, values);
    }

    /** 返回合并审计元数据后的新命令。 */
    public AgentResumeCommand withMetadata(Map<String, ?> additions) {
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        if (additions != null) {
            values.putAll(additions);
        }
        return new AgentResumeCommand(type, content, correlationId, values);
    }

    public AgentResumeCommandType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
