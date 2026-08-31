/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolContextHolder;
import com.agentsflex.core.util.StringUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * AgentRunner 执行 Tool 时提供的受控只读上下文。
 *
 * <p>该对象组合稳定调用身份、已解析 Tool、模型 ToolCall、进度发布器和动态取消检查。业务 Tool
 * 可以使用这些能力实现幂等、追踪、进度展示和协作式取消，但不能通过本对象修改 AgentTurn、保存
 * Snapshot 或推进 Runner 状态机。</p>
 *
 * <p>上下文只在当前 ToolExecutor 的同步调用范围内可用，不会自动传播到其他线程。需要异步执行时，
 * 应在提交任务前复制所需的稳定 ID，不应长期持有本对象。</p>
 */
public final class AgentToolContext {

    /**
     * AgentRunner 写入 Core ToolContext 的属性键。
     */
    public static final String CONTEXT_ATTRIBUTE = AgentToolContext.class.getName();

    private final String turnId;
    private final String agentId;
    private final String agentVersion;
    private final Tool tool;
    private final ToolCall toolCall;
    private final String toolCallId;
    private final AgentToolProgressEmitter progressEmitter;
    private final BooleanSupplier cancellationRequested;
    private final Map<String, Object> submittedFormData;

    /**
     * 创建不携带恢复表单数据的工具上下文。
     *
     * @param turnId                当前 Turn ID
     * @param agentId               Agent ID
     * @param agentVersion          Agent 配置版本
     * @param tool                  已解析工具
     * @param toolCall              模型工具调用
     * @param toolCallId            跨恢复稳定调用 ID
     * @param progressEmitter       进度发布器
     * @param cancellationRequested 动态取消检查
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested) {
        this(turnId, agentId, agentVersion, tool, toolCall,
            toolCallId, progressEmitter, cancellationRequested,
            Collections.<String, Object>emptyMap());
    }

    /**
     * 创建包含恢复表单数据的完整工具执行上下文，并复制提交字段。
     *
     * @param turnId                当前 Turn ID
     * @param agentId               Agent ID
     * @param agentVersion          Agent 配置版本
     * @param tool                  已解析工具
     * @param toolCall              模型工具调用
     * @param toolCallId            跨恢复稳定调用 ID
     * @param progressEmitter       进度发布器
     * @param cancellationRequested 动态取消检查
     * @param submittedFormData     恢复时提交的结构化表单数据
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested,
                            Map<String, ?> submittedFormData) {
        if (!StringUtil.hasText(turnId) || !StringUtil.hasText(agentId)
            || !StringUtil.hasText(agentVersion) || tool == null || toolCall == null
            || !StringUtil.hasText(toolCallId) || progressEmitter == null
            || cancellationRequested == null) {
            throw new IllegalArgumentException(
                "turnId, agentId, agentVersion, tool, toolCall, toolCallId, progressEmitter "
                    + "and cancellationRequested must be provided");
        }
        this.turnId = turnId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.tool = tool;
        this.toolCall = toolCall;
        this.toolCallId = toolCallId;
        this.progressEmitter = progressEmitter;
        this.cancellationRequested = cancellationRequested;
        this.submittedFormData = submittedFormData == null || submittedFormData.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(submittedFormData));
    }

    /**
     * 返回当前线程的 Agent Tool 上下文；非 AgentRunner 调用时返回 {@code null}。
     */
    public static AgentToolContext current() {
        com.agentsflex.core.model.chat.tool.ToolContext context =
            ToolContextHolder.currentContext();
        return context == null ? null : context.getAttribute(CONTEXT_ATTRIBUTE);
    }

    /**
     * @return 直接执行工具的 Turn ID
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * @return 工具所属 Agent ID
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @return 工具所属 Agent 配置版本
     */
    public String getAgentVersion() {
        return agentVersion;
    }

    /**
     * @return 当前 Agent 中解析出的 Tool
     */
    public Tool getTool() {
        return tool;
    }

    /**
     * @return 模型生成并等待执行的 ToolCall
     */
    public ToolCall getToolCall() {
        return toolCall;
    }

    /**
     * @return 当前 ToolCall 的跨恢复稳定 ID
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * @return 当前工具名称
     */
    public String getToolName() {
        return tool.getName();
    }

    /**
     * @return 由 turnId 和 toolCallId 组成的默认业务幂等键
     */
    public String getIdempotencyKey() {
        return turnId + ":" + toolCallId;
    }

    /**
     * @return 当前时刻是否已收到协作式取消请求
     */
    public boolean isCancellationRequested() {
        return cancellationRequested.getAsBoolean();
    }

    /**
     * @return 当前调用的进度发布器
     */
    public AgentToolProgressEmitter getProgressEmitter() {
        return progressEmitter;
    }

    /**
     * 返回该 ToolCall 上一次表单暂停后用户提交的数据。
     *
     * <p>首次执行工具时返回空 Map；工具抛出 {@link AgentFormRequiredException} 并恢复后，Runner
     * 会从头执行原工具，此时返回提交数据。该值来自 Snapshot，是不可修改的。</p>
     */
    public Map<String, Object> getSubmittedFormData() {
        return submittedFormData;
    }

    /**
     * 发布不修改 Turn 状态的工具进度。
     */
    public void emitProgress(String message) {
        progressEmitter.emit(message, Collections.<String, Object>emptyMap());
    }

    /**
     * 发布带结构化数据且不修改 Turn 状态的工具进度。
     */
    public void emitProgress(String message, Map<String, ?> data) {
        progressEmitter.emit(message, data);
    }
}
