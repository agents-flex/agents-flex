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
 * <p>该对象组合稳定调用身份、已解析 Tool、模型 ToolCall、进度发布器和实时取消检查。业务 Tool
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
    private final int executionAttempt;
    private final AgentToolResumeInfo resumeInfo;
    private final ToolApprovalRecord policyApprovalRecord;
    private final ToolApprovalRecord toolApprovalRecord;
    private final Map<String, ToolApprovalRecord> toolApprovalRecordsByRequest;

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
     * @param cancellationRequested 实时取消检查
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested) {
        this(turnId, agentId, agentVersion, tool, toolCall,
            toolCallId, progressEmitter, cancellationRequested,
            Collections.<String, Object>emptyMap(), 1, AgentToolResumeInfo.none(), null, null);
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
     * @param cancellationRequested 实时取消检查
     * @param submittedFormData     恢复时提交的结构化表单数据
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested,
                            Map<String, ?> submittedFormData) {
        this(turnId, agentId, agentVersion, tool, toolCall, toolCallId,
            progressEmitter, cancellationRequested, submittedFormData, 1,
            AgentToolResumeInfo.none(), null, null);
    }

    /**
     * 创建包含执行次数和恢复来源的完整工具上下文。
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested,
                            Map<String, ?> submittedFormData, int executionAttempt,
                            AgentToolResumeInfo resumeInfo) {
        this(turnId, agentId, agentVersion, tool, toolCall, toolCallId,
            progressEmitter, cancellationRequested, submittedFormData, executionAttempt,
            resumeInfo, null, null);
    }

    /**
     * 创建同时包含中央策略记录和 Tool 主动审批记录的完整上下文。
     *
     * <p>中央策略记录和 Tool 主动审批记录分别注入，使本地 Tool 无法把平台边界的批准误当成
     * 本次只读预检结果的批准。</p>
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested,
                            Map<String, ?> submittedFormData, int executionAttempt,
                            AgentToolResumeInfo resumeInfo,
                            ToolApprovalRecord policyApprovalRecord,
                            ToolApprovalRecord toolApprovalRecord) {
        this(turnId, agentId, agentVersion, tool, toolCall, toolCallId,
            progressEmitter, cancellationRequested, submittedFormData, executionAttempt,
            resumeInfo, policyApprovalRecord, toolApprovalRecord,
            singletonToolApprovalRecord(toolApprovalRecord));
    }

    /**
     * 创建包含中央策略记录、最近一次 Tool 决定和全部 Tool 主动审批记录的上下文。
     *
     * <p>同一 ToolCall 可以依次请求多个不同批准。全部记录按 requestFingerprint 注入，确保 Tool
     * 每次从头执行时，前面已经通过的审批点仍然有效；最近记录单独保留，用于展示最后一次决定。</p>
     */
    public AgentToolContext(String turnId, String agentId, String agentVersion,
                            Tool tool, ToolCall toolCall,
                            String toolCallId, AgentToolProgressEmitter progressEmitter,
                            BooleanSupplier cancellationRequested,
                            Map<String, ?> submittedFormData, int executionAttempt,
                            AgentToolResumeInfo resumeInfo,
                            ToolApprovalRecord policyApprovalRecord,
                            ToolApprovalRecord toolApprovalRecord,
                            Map<String, ToolApprovalRecord> toolApprovalRecordsByRequest) {
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
        if (executionAttempt < 1) {
            throw new IllegalArgumentException("executionAttempt must be greater than zero");
        }
        this.executionAttempt = executionAttempt;
        this.resumeInfo = resumeInfo == null ? AgentToolResumeInfo.none() : resumeInfo;
        this.policyApprovalRecord = policyApprovalRecord;
        this.toolApprovalRecord = toolApprovalRecord;
        Map<String, ToolApprovalRecord> records = new LinkedHashMap<>();
        if (toolApprovalRecordsByRequest != null) {
            for (ToolApprovalRecord record : toolApprovalRecordsByRequest.values()) {
                if (isIndexableToolApproval(record)) {
                    records.put(record.getRequestFingerprint(), record);
                }
            }
        }
        // 兼容只提供最近记录的旧构造器和旧 Snapshot；同指纹时以最近记录为准。
        if (isIndexableToolApproval(toolApprovalRecord)) {
            records.put(toolApprovalRecord.getRequestFingerprint(), toolApprovalRecord);
        }
        this.toolApprovalRecordsByRequest = records.isEmpty()
            ? Collections.<String, ToolApprovalRecord>emptyMap()
            : Collections.unmodifiableMap(records);
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
     * 会从头执行原工具，此时返回截至当前轮的全部提交数据。多轮提交会合并字段，后提交值覆盖同名字段。
     * 该值来自 Snapshot，是不可修改的。</p>
     */
    public Map<String, Object> getSubmittedFormData() {
        return submittedFormData;
    }

    /**
     * @return 当前 ToolCall 实际进入工具函数的累计次数，从 1 开始
     */
    public int getExecutionAttempt() {
        return executionAttempt;
    }

    /**
     * @return 本次执行前最近一次恢复来源及其审计信息
     */
    public AgentToolResumeInfo getResumeInfo() {
        return resumeInfo;
    }

    /**
     * @return 本次执行前最近一次恢复类型
     */
    public AgentToolResumeType getResumeType() {
        return resumeInfo.getType();
    }

    /**
     * @return 当前 ToolCall 已发生的恢复次数
     */
    public int getResumeCount() {
        return resumeInfo.getResumeCount();
    }

    /**
     * @return 当前 ToolCall 的错误重试序号；当前执行不是由错误重试恢复时返回 0
     */
    public int getRetryAttempt() {
        return resumeInfo.getRetryAttempt();
    }

    /**
     * @return 当前错误重试计划的下一次执行时间；没有重试计划时返回 0
     */
    public long getRetryNextRunnableAt() {
        return resumeInfo.getRetryNextRunnableAt();
    }

    /**
     * @return 上一次恢复关联的错误类型；没有错误或不是错误恢复时返回 null
     */
    public String getPreviousErrorType() {
        return resumeInfo.getPreviousErrorType();
    }

    /**
     * @return 上一次恢复关联的错误消息；没有错误或不是错误恢复时返回 null
     */
    public String getPreviousErrorMessage() {
        return resumeInfo.getPreviousErrorMessage();
    }

    /**
     * @return 是否由表单提交恢复
     */
    public boolean isFormInputResumed() {
        return getResumeType() == AgentToolResumeType.FORM_INPUT;
    }

    /**
     * @return 是否由审批通过恢复
     */
    public boolean isApprovalResumed() {
        return getResumeType() == AgentToolResumeType.APPROVAL;
    }

    /**
     * @return 当前 ToolCall 是否已经通过中央 toolApprovalPolicy 的人工审批
     */
    public boolean isPolicyApproved() {
        return policyApprovalRecord != null && policyApprovalRecord.isApproved();
    }

    /**
     * 判断已有批准是否仍适用于本次 Tool 只读预检结果。
     *
     * <p>即使同一 ToolCall 曾获批准，只要金额、目标对象、业务版本或其他审批信息改变，决策指纹
     * 就不匹配，Tool 必须再次抛出 AgentApprovalRequiredException。必须传入本次请求，避免中央策略的
     * 批准或旧业务快照的批准被误用。</p>
     */
    public boolean isToolApproved(ToolApprovalDecision decision) {
        ToolApprovalRecord record = getToolApprovalRecord(decision);
        return record != null && record.isApproved();
    }

    /**
     * @return 中央审批完整记录；未发生中央人工审批时为 null
     */
    public ToolApprovalRecord getPolicyApprovalRecord() {
        return policyApprovalRecord;
    }

    /**
     * @return Tool 主动申请的审批记录；尚未申请或决定时为 null
     */
    public ToolApprovalRecord getToolApprovalRecord() {
        return toolApprovalRecord;
    }

    /**
     * 返回与指定审批请求指纹完全匹配的决定记录。
     *
     * @param decision 当前 Tool 根据最新只读预检构造的审批请求
     * @return 匹配记录；尚未决定该请求时返回 null
     */
    public ToolApprovalRecord getToolApprovalRecord(ToolApprovalDecision decision) {
        return decision == null ? null
            : toolApprovalRecordsByRequest.get(decision.getRequestFingerprint());
    }

    /**
     * 返回当前 ToolCall 已经决定的全部 Tool 主动审批，键为 requestFingerprint。
     *
     * <p>返回 Map 不可修改；每条 ToolApprovalRecord 本身也是不可变值对象。调用方可以使用它展示
     * 多级批准人和决定时间，但实际授权判断仍应调用 isToolApproved(decision)。</p>
     */
    public Map<String, ToolApprovalRecord> getToolApprovalRecords() {
        return toolApprovalRecordsByRequest;
    }

    /**
     * @return 是否由异常重试恢复
     */
    public boolean isRetryResumed() {
        return getResumeType() == AgentToolResumeType.RETRY;
    }

    /**
     * @return 本次工具执行是否由挂起恢复触发
     */
    public boolean isResumed() {
        return resumeInfo.isResumed();
    }

    /**
     * @return 当前 ToolCall 是否已经被工具函数执行过至少一次
     */
    public boolean isReplay() {
        return executionAttempt > 1;
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

    private static Map<String, ToolApprovalRecord> singletonToolApprovalRecord(
        ToolApprovalRecord record) {
        if (!isIndexableToolApproval(record)) return Collections.emptyMap();
        return Collections.singletonMap(record.getRequestFingerprint(), record);
    }

    private static boolean isIndexableToolApproval(ToolApprovalRecord record) {
        return record != null && record.getStage() == ToolApprovalStage.TOOL
            && record.getRequestFingerprint() != null;
    }
}
