/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.alibaba.fastjson2.JSON;
import com.agentsflex.core.message.UserMessage;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部系统用于恢复暂停任务的不可变命令。
 *
 * <p>命令类型必须与当前暂停原因匹配。例如，等待工具审批的 Turn 只接受批准或拒绝命令，等待用户输入
 * 的 Turn 只接受用户输入命令。correlationId 用于校验工具调用或外部事件，避免迟到事件恢复错误任务。</p>
 */
public final class AgentResumeCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 恢复动作类型。
     */
    private final AgentResumeCommandType type;
    /**
     * 用户输入、拒绝原因等文本内容。
     */
    private final String content;
    /**
     * 工具调用 ID 或外部事件关联 ID。
     */
    private final String correlationId;
    /**
     * 表单等结构化用户输入。审批命令通常为空。
     */
    private final Map<String, Object> data;
    /**
     * USER_MESSAGE 命令携带的完整用户消息；其他命令为空。
     */
    private final UserMessage userMessage;
    /**
     * 业务系统附加的只读元数据。
     */
    private final Map<String, Object> metadata;

    /**
     * 创建只携带文本和审计元数据的兼容恢复命令。
     *
     * @param type          恢复动作类型
     * @param content       用户文本或拒绝原因
     * @param correlationId 工具调用或外部事件关联 ID
     * @param metadata      业务审计元数据
     */
    public AgentResumeCommand(AgentResumeCommandType type, String content,
                              String correlationId, Map<String, Object> metadata) {
        this(type, content, correlationId, null, metadata);
    }

    /**
     * 创建同时支持文本和结构化输入的恢复命令。
     */
    public AgentResumeCommand(AgentResumeCommandType type, String content,
                              String correlationId, Map<String, Object> data,
                              Map<String, Object> metadata) {
        this(type, content, correlationId, data, null, metadata);
    }

    private AgentResumeCommand(AgentResumeCommandType type, String content,
                               String correlationId, Map<String, Object> data,
                               UserMessage userMessage, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.content = content;
        this.correlationId = correlationId;
        this.data = immutableMap(data);
        this.userMessage = userMessage == null ? null : userMessage.copy();
        this.metadata = metadata == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(metadata));
    }

    /**
     * 创建不携带额外数据的继续命令。
     */
    public static AgentResumeCommand continueTurn() {
        return new AgentResumeCommand(AgentResumeCommandType.CONTINUE, null, null, null);
    }

    /**
     * 创建补充用户输入的恢复命令。
     */
    public static AgentResumeCommand userInput(String content) {
        return new AgentResumeCommand(AgentResumeCommandType.USER_INPUT, content, null, null);
    }

    /**
     * 创建与 request_user_input ToolCall 关联的文本输入命令。
     */
    public static AgentResumeCommand userInput(String callId, String content) {
        return new AgentResumeCommand(AgentResumeCommandType.USER_INPUT,
            content, callId, null, null);
    }

    /**
     * 创建与 request_user_input ToolCall 关联的结构化表单提交命令。
     */
    public static AgentResumeCommand userInput(String callId, Map<String, ?> data) {
        Map<String, Object> values = data == null
            ? null : new LinkedHashMap<String, Object>(data);
        return new AgentResumeCommand(AgentResumeCommandType.USER_INPUT,
            null, callId, values, null);
    }

    /**
     * 创建批准指定工具调用的恢复命令。
     */
    public static AgentResumeCommand approveTool(String callId) {
        return new AgentResumeCommand(AgentResumeCommandType.APPROVE_TOOL, null, callId, null);
    }

    /**
     * 创建拒绝指定工具调用的恢复命令。
     */
    public static AgentResumeCommand rejectTool(String callId, String reason) {
        return new AgentResumeCommand(AgentResumeCommandType.REJECT_TOOL, reason, callId, null);
    }

    /**
     * 创建外部执行器成功结果命令。结构化对象会转换成 JSON ToolMessage 内容。
     */
    public static AgentResumeCommand toolResult(String callId, Object result) {
        return new AgentResumeCommand(AgentResumeCommandType.TOOL_RESULT,
            toolContent(result), callId, null);
    }

    /**
     * 创建外部执行器失败结果命令。错误会作为结构化 ToolMessage 返回给模型。
     */
    public static AgentResumeCommand toolError(String callId, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        if (code != null) body.put("code", code);
        if (message != null) body.put("message", message);
        return new AgentResumeCommand(AgentResumeCommandType.TOOL_ERROR,
            JSON.toJSONString(body), callId, null);
    }

    /**
     * 创建执行已到期自动重试的恢复命令。
     */
    public static AgentResumeCommand retry() {
        return new AgentResumeCommand(AgentResumeCommandType.RETRY, null, null, null);
    }

    /**
     * 创建修复模型条件后重试原模型调用的命令。
     *
     * @param failureId 当前 MODEL Suspension 的关联 ID
     */
    public static AgentResumeCommand retryModel(String failureId) {
        return new AgentResumeCommand(AgentResumeCommandType.RETRY_MODEL,
            null, failureId, null);
    }

    /**
     * 创建一条普通用户消息。
     *
     * <p>在 WAITING_FOR_USER 中，Runner 会优先把它解释为当前输入请求的回答；在其他阻塞状态中，
     * 它会放弃旧等待并触发模型重新规划。需要无条件中断当前输入请求时使用
     * {@link #replanWithMessage(String)}。</p>
     */
    public static AgentResumeCommand userMessage(String content) {
        return userMessage(new UserMessage(content));
    }

    /**
     * 创建一条保留多模态内容的普通用户消息。
     */
    public static AgentResumeCommand userMessage(UserMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        return new AgentResumeCommand(AgentResumeCommandType.USER_MESSAGE,
            null, null, null, message, null);
    }

    /**
     * 创建使用纯文本无条件打断当前阻塞并重新规划的命令。
     */
    public static AgentResumeCommand replanWithMessage(String content) {
        return replanWithMessage(new UserMessage(content));
    }

    /**
     * 创建使用完整多模态消息无条件打断当前阻塞并重新规划的命令。
     *
     * <p>Runner 会先为全部 pending ToolCall 写入中断 ToolMessage 和强类型审计记录，再追加该消息。
     * 外部工具已经派发时还会在 Snapshot 保存成功后发布取消请求事件。</p>
     */
    public static AgentResumeCommand replanWithMessage(UserMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        return new AgentResumeCommand(AgentResumeCommandType.REPLAN_WITH_MESSAGE,
            null, null, null, message, null);
    }

    /**
     * 返回附加一项审计元数据的新命令。
     */
    public AgentResumeCommand withMetadata(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("metadata key must not be null");
        }
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.put(key, value);
        return new AgentResumeCommand(type, content, correlationId, data, userMessage, values);
    }

    /**
     * 返回合并审计元数据后的新命令。
     */
    public AgentResumeCommand withMetadata(Map<String, ?> additions) {
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        if (additions != null) {
            values.putAll(additions);
        }
        return new AgentResumeCommand(type, content, correlationId, data, userMessage, values);
    }

    /**
     * @return 恢复动作类型
     */
    public AgentResumeCommandType getType() {
        return type;
    }

    /**
     * @return 用户补充文本或拒绝原因；不需要正文时为空
     */
    public String getContent() {
        return content;
    }

    /**
     * @return ToolCall ID 或其他阻塞事件关联标识
     */
    public String getCorrelationId() {
        return correlationId;
    }

    /**
     * @return 不可修改的结构化用户输入；非表单命令返回空 Map
     */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * @return USER_MESSAGE 命令携带的独立消息副本；其他命令返回 {@code null}
     */
    public UserMessage getUserMessage() {
        return userMessage == null ? null : userMessage.copy();
    }

    /**
     * @return 不可修改的命令审计元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 复制结构化提交数据并返回保持字段顺序的不可修改 Map。
     */
    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        return values == null || values.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String toolContent(Object value) {
        if (value == null) return "null";
        if (value instanceof CharSequence || value instanceof Number
            || value instanceof Boolean) {
            return value.toString();
        }
        return JSON.toJSONString(value);
    }
}
