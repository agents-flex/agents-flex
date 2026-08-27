/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.message;

import com.agentsflex.core.message.AbstractTextMessage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 展示在对话页面中的 Agent 待处理操作。
 *
 * <p>该消息不会发送给模型。页面根据 {@link #getStatus()} 渲染按钮或处理结果；业务端仍必须使用
 * AgentTurn 中的 Suspension 校验操作，不能仅凭本消息恢复执行。</p>
 */
public final class AgentActionMessage extends AbstractTextMessage<AgentActionMessage> {

    public enum Type {
        /**
         * 工具产生外部副作用之前需要用户批准。
         */
        TOOL_APPROVAL
    }

    /**
     * 页面操作的当前状态；除 PENDING 外均不再接受用户操作。
     */
    public enum Status {
        /**
         * 尚未处理，页面可显示操作按钮。
         */
        PENDING,
        /**
         * 用户已经批准。
         */
        APPROVED,
        /**
         * 用户已经拒绝。
         */
        REJECTED
    }

    /**
     * 真实执行状态所在的 AgentTurn ID。
     */
    private String turnId;
    /**
     * 与 Suspension correlationId 相同的稳定操作 ID。
     */
    private String actionId;
    /**
     * 页面操作类型。
     */
    private Type actionType;
    /**
     * 页面渲染按钮和结果使用的当前状态。
     */
    private Status status = Status.PENDING;
    /**
     * 批准或拒绝操作的业务用户 ID。
     */
    private String resolvedBy;
    /**
     * 拒绝理由等面向业务的处理说明。
     */
    private String resolutionReason;
    /**
     * 处理完成的毫秒时间戳；待处理时为 0。
     */
    private long resolvedAt;

    /**
     * 创建默认待处理且仅供页面展示的动作消息。
     */
    public AgentActionMessage() {
        setModelVisible(false);
    }

    /**
     * 创建与工具审批 Suspension 对应的稳定页面消息。
     *
     * @param turnId   执行事实所在 Turn ID
     * @param actionId 工具调用关联 ID
     * @param message  面向审批人的说明
     * @return 状态为 PENDING、模型不可见的审批消息
     */
    public static AgentActionMessage toolApproval(String turnId, String actionId,
                                                  String message) {
        if (turnId == null || turnId.trim().isEmpty()
            || actionId == null || actionId.trim().isEmpty()) {
            throw new IllegalArgumentException("turnId and actionId must not be blank");
        }
        AgentActionMessage result = new AgentActionMessage();
        result.setMessageId(turnId + ":approval:" + actionId);
        result.turnId = turnId;
        result.actionId = actionId;
        result.actionType = Type.TOOL_APPROVAL;
        result.content = message;
        return result;
    }

    /**
     * 创建终态副本，原消息保持不变；调用方应通过 ChatMemory 的 expectedVersion CAS 提交副本。
     */
    public AgentActionMessage resolved(Status value, String operator,
                                       String reason, long time) {
        if (value == null || value == Status.PENDING) {
            throw new IllegalArgumentException("resolved status must be terminal");
        }
        AgentActionMessage result = copy();
        result.status = value;
        result.resolvedBy = operator;
        result.resolutionReason = reason;
        result.resolvedAt = time;
        return result;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public Type getActionType() {
        return actionType;
    }

    public void setActionType(Type actionType) {
        this.actionType = actionType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public void setResolutionReason(String resolutionReason) {
        this.resolutionReason = resolutionReason;
    }

    public long getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    /**
     * @return 待处理时页面可展示的操作；终态消息返回空列表
     */
    public List<String> getActions() {
        return status == Status.PENDING
            ? Collections.unmodifiableList(Arrays.asList("APPROVE", "REJECT"))
            : Collections.<String>emptyList();
    }

    /**
     * 深复制页面动作及所有通用消息状态，供 CAS 更新时使用。
     *
     * @return 与当前消息不共享可变元数据的副本
     */
    @Override
    public AgentActionMessage copy() {
        AgentActionMessage copy = new AgentActionMessage();
        copy.content = content;
        copy.turnId = turnId;
        copy.actionId = actionId;
        copy.actionType = actionType;
        copy.status = status;
        copy.resolvedBy = resolvedBy;
        copy.resolutionReason = resolutionReason;
        copy.resolvedAt = resolvedAt;
        return copyMessageStateTo(copy);
    }
}
