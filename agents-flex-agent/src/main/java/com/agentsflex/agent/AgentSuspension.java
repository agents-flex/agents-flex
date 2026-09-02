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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentTurn 的可持久化暂停信息。
 *
 * <p>该对象不执行审批或用户交互，只记录暂停原因、关联事件以及恢复后的执行阶段。生命周期、
 * 调度和交互协议字段使用强类型属性保存；{@code metadata} 仅用于业务扩展，并随
 * AgentTurnSnapshot 持久化，使另一个进程可以判断应接受哪一种恢复命令。</p>
 */
public final class AgentSuspension implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String RESERVED_REQUESTED_AT = "requestedAt";
    private static final String RESERVED_TIMEOUT_MILLIS = "timeoutMillis";
    private static final String RESERVED_NEXT_RUNNABLE_AT = "nextRunnableAt";
    private static final String RESERVED_TOOL_NAME = "toolName";
    private static final String RESERVED_ARGUMENTS = "arguments";
    private static final String RESERVED_APPROVAL_OUTCOME = "approvalOutcome";
    private static final String RESERVED_APPROVAL_CODE = "approvalCode";
    private static final String RESERVED_APPROVAL_REASON = "approvalReason";
    private static final String RESERVED_FORM_KEY = "formKey";
    private static final String RESERVED_SCHEMA = "schema";
    private static final String RESERVED_INPUT_TARGET = "inputTarget";

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
     * 挂起创建时间（毫秒时间戳）；0 表示未设置。
     */
    private final long requestedAt;
    /**
     * 挂起等待期限（毫秒）；0 表示不限制。
     */
    private final long timeoutMillis;
    /**
     * RETRY 挂起的下一次可运行时间；非 RETRY 时为 0。
     */
    private final long nextRunnableAt;
    /**
     * TOOL_APPROVAL 或 EXTERNAL_TOOL 对应的工具名称。
     */
    private final String toolName;
    /**
     * EXTERNAL_TOOL 发给外部执行器的原始参数文本。
     */
    private final String arguments;
    /**
     * 审批策略返回的固定处理结果。
     */
    private final ToolApprovalDecision.Outcome approvalOutcome;
    /**
     * 审批策略返回的稳定业务代码。
     */
    private final String approvalCode;
    /**
     * 审批策略返回的审计原因。
     */
    private final String approvalReason;
    /**
     * USER_INPUT 表单的稳定标识。
     */
    private final String formKey;
    /**
     * USER_INPUT 表单的结构化 Schema，只读快照。
     */
    private final Map<String, Object> schema;
    /**
     * USER_INPUT 的目标类型，例如业务 Tool；使用固定字符串避免继续依赖 metadata 键。
     */
    private final String inputTarget;
    /**
     * 业务侧自定义扩展信息，不承载上述框架协议字段。
     */
    private final Map<String, Object> metadata;

    /**
     * 创建可持久化挂起点，并复制恢复所需元数据。
     *
     * @param type                 挂起原因
     * @param correlationId        工具调用关联 ID
     * @param message              面向调用方的等待说明
     * @param resumeExecutionPoint 恢复后继续执行的阶段
     * @param metadata             可序列化业务扩展数据；框架保留键不会写入扩展 Map
     */
    public AgentSuspension(AgentSuspensionType type, String correlationId, String message,
                           AgentTurnExecutionPoint resumeExecutionPoint, Map<String, Object> metadata) {
        this(type, correlationId, message, resumeExecutionPoint, 0L, 0L, 0L,
            null, null, null, null, null, null, null, null, extensionMetadata(metadata));
    }

    /**
     * 保存强类型字段并冻结所有 Map，供工厂方法和 copy/withRequestedAt 使用。
     */
    private AgentSuspension(AgentSuspensionType type, String correlationId, String message,
                            AgentTurnExecutionPoint resumeExecutionPoint,
                            long requestedAt, long timeoutMillis, long nextRunnableAt,
                            String toolName, String arguments,
                            ToolApprovalDecision.Outcome approvalOutcome,
                            String approvalCode, String approvalReason,
                            String formKey, Map<String, ?> schema, String inputTarget,
                            Map<String, ?> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        this.type = type;
        this.correlationId = correlationId;
        this.message = message;
        this.resumeExecutionPoint = resumeExecutionPoint == null ? AgentTurnExecutionPoint.INVOKE_MODEL : resumeExecutionPoint;
        this.requestedAt = Math.max(0L, requestedAt);
        this.timeoutMillis = Math.max(0L, timeoutMillis);
        this.nextRunnableAt = this.type == AgentSuspensionType.RETRY
            ? Math.max(0L, nextRunnableAt) : 0L;
        this.toolName = toolName;
        this.arguments = arguments;
        this.approvalOutcome = approvalOutcome;
        this.approvalCode = approvalCode;
        this.approvalReason = approvalReason;
        this.formKey = formKey;
        this.schema = immutableMap(schema);
        this.inputTarget = inputTarget;
        this.metadata = immutableMap(metadata);
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

    /**
     * 创建由表单定义驱动的结构化用户输入暂停点。
     *
     * <p>表单字段属于暂停协议，直接保存为属性；{@code metadata} 只保留业务扩展，避免表单恢复
     * 依赖字符串键和运行时类型转换。</p>
     *
     * @param callId        原 ToolCall ID
     * @param message       面向用户的提示
     * @param formKey       表单稳定标识
     * @param schema        表单结构定义
     * @param toolName      关联工具名
     * @param inputTarget   输入目标类型；业务 Tool 通常为 {@code TOOL}
     * @param timeoutMillis 等待期限，0 表示不限制
     */
    public static AgentSuspension userInput(String callId, String message,
                                            String formKey, Map<String, ?> schema,
                                            String toolName, String inputTarget,
                                            long timeoutMillis) {
        validateCallIdAndTimeout(callId, timeoutMillis);
        return new AgentSuspension(AgentSuspensionType.USER_INPUT, callId, message,
            AgentTurnExecutionPoint.PROCESS_TOOLS, System.currentTimeMillis(), timeoutMillis,
            0L, toolName, null, null, null, null, formKey, schema, inputTarget, null);
    }

    private static AgentSuspension createUserInput(String callId, String message,
                                                   AgentTurnExecutionPoint resumePoint,
                                                   Map<String, Object> sourceMetadata,
                                                   long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        return new AgentSuspension(AgentSuspensionType.USER_INPUT, callId, message,
            resumePoint, System.currentTimeMillis(), timeoutMillis, 0L,
            null, null, null, null, null, null, null, null,
            extensionMetadata(sourceMetadata));
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
        String message = decision != null && decision.getMessage() != null
            ? decision.getMessage() : "Tool approval is required: " + toolName;
        ToolApprovalDecision.Outcome outcome = decision == null ? null : decision.getOutcome();
        String code = decision == null ? null : decision.getCode();
        String reason = decision == null ? null : decision.getReason();
        // 决策允许携带业务扩展，但保留键不能重新进入扩展 Map 覆盖类型化字段。
        Map<String, Object> metadata = decision == null
            ? null : extensionMetadata(decision.getMetadata());
        return new AgentSuspension(AgentSuspensionType.TOOL_APPROVAL, callId,
            message, AgentTurnExecutionPoint.PROCESS_TOOLS, System.currentTimeMillis(),
            timeoutMillis, 0L, toolName, null, outcome, code, reason,
            null, null, null, metadata);
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
        Map<String, Object> metadata = null;
        if (toolMetadata != null && !toolMetadata.isEmpty()) {
            metadata = new LinkedHashMap<>();
            metadata.put("toolMetadata", new LinkedHashMap<String, Object>(toolMetadata));
        }
        return new AgentSuspension(AgentSuspensionType.EXTERNAL_TOOL, callId,
            "External tool result is required: " + toolName,
            AgentTurnExecutionPoint.PROCESS_TOOLS, System.currentTimeMillis(), timeoutMillis,
            0L, toolName, arguments, null, null, null, null, null, null, metadata);
    }

    /**
     * 创建等待指定时间后自动重试的暂停点。
     */
    public static AgentSuspension retry(String message, AgentTurnExecutionPoint resumeExecutionPoint, long nextRunnableAt) {
        return new AgentSuspension(AgentSuspensionType.RETRY, null, message, resumeExecutionPoint,
            0L, 0L, nextRunnableAt, null, null, null, null, null, null, null, null, null);
    }

    private static void validateCallIdAndTimeout(String callId, long timeoutMillis) {
        if (callId == null || callId.trim().isEmpty()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
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
     * @return 挂起创建时间；旧快照没有该字段时返回 0
     */
    public long getRequestedAt() {
        return requestedAt;
    }

    /**
     * @return 挂起等待期限（毫秒）；0 表示不限制
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * @return RETRY 挂起的下一次可运行时间；非 RETRY 挂起返回 0
     */
    public long getNextRunnableAt() {
        return nextRunnableAt;
    }

    /**
     * @return 审批或外部工具对应的工具名称；当前挂起类型不适用时为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * @return 外部工具调用参数文本；非 EXTERNAL_TOOL 挂起时为 {@code null}
     */
    public String getArguments() {
        return arguments;
    }

    /**
     * @return 审批策略的结构化结果；非审批挂起时为 {@code null}
     */
    public ToolApprovalDecision.Outcome getApprovalOutcome() {
        return approvalOutcome;
    }

    /**
     * @return 审批策略代码；非审批挂起时为 {@code null}
     */
    public String getApprovalCode() {
        return approvalCode;
    }

    /**
     * @return 审批策略审计原因；非审批挂起时为 {@code null}
     */
    public String getApprovalReason() {
        return approvalReason;
    }

    /**
     * @return 用户输入表单的稳定标识；非表单挂起时为 {@code null}
     */
    public String getFormKey() {
        return formKey;
    }

    /**
     * @return 用户输入表单 Schema 的只读快照；没有 Schema 时为空 Map
     */
    public Map<String, Object> getSchema() {
        return schema;
    }

    /**
     * @return 用户输入目标类型；非结构化用户输入时为 {@code null}
     */
    public String getInputTarget() {
        return inputTarget;
    }

    /**
     * 使用 TurnStore 的统一时钟重写等待起点，避免多节点本机时钟偏差。
     */
    AgentSuspension withRequestedAt(long value) {
        if (getTimeoutMillis() <= 0) return this;
        return new AgentSuspension(type, correlationId, message, resumeExecutionPoint,
            value, timeoutMillis, nextRunnableAt, toolName, arguments, approvalOutcome,
            approvalCode, approvalReason, formKey, schema, inputTarget, metadata);
    }

    /**
     * @return 与当前暂停信息隔离的副本
     */
    AgentSuspension copy() {
        return new AgentSuspension(type, correlationId, message, getResumeExecutionPoint(),
            requestedAt, timeoutMillis, nextRunnableAt, toolName, arguments, approvalOutcome,
            approvalCode, approvalReason, formKey, schema, inputTarget, metadata);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        return source == null || source.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
    }

    /**
     * 复制业务扩展并移除框架保留键；该操作不负责从旧格式推断任何属性。
     */
    private static Map<String, Object> extensionMetadata(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || isReservedKey(key)) continue;
            values.put(key, entry.getValue());
        }
        return values;
    }

    private static boolean isReservedKey(String key) {
        return RESERVED_REQUESTED_AT.equals(key) || RESERVED_TIMEOUT_MILLIS.equals(key)
            || RESERVED_NEXT_RUNNABLE_AT.equals(key) || RESERVED_TOOL_NAME.equals(key)
            || RESERVED_ARGUMENTS.equals(key) || RESERVED_APPROVAL_OUTCOME.equals(key)
            || RESERVED_APPROVAL_CODE.equals(key) || RESERVED_APPROVAL_REASON.equals(key)
            || RESERVED_FORM_KEY.equals(key) || RESERVED_SCHEMA.equals(key)
            || RESERVED_INPUT_TARGET.equals(key);
    }
}
