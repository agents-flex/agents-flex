/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.message;

import com.agentsflex.core.message.AbstractTextMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示在业务会话时间线中的结构化表单请求。
 *
 * <p>消息携带业务表单标识、JSON Schema 和当前提交状态。该消息默认不发送给模型；模型只读取
 * 恢复后生成的 ToolMessage。</p>
 */
public final class AgentFormMessage extends AbstractTextMessage<AgentFormMessage> {

    /**
     * 表单请求在页面上的当前状态。
     */
    public enum Status {
        /**
         * 等待用户填写，页面可以显示提交操作。
         */
        PENDING,
        /**
         * 用户输入已经校验并提交给原 AgentTurn。
         */
        SUBMITTED,
        /**
         * 原 AgentTurn 已取消或终止，不再接受输入。
         */
        CANCELLED
    }

    private String turnId;
    private String actionId;
    private String formKey;
    private Map<String, Object> schema = Collections.emptyMap();
    private Status status = Status.PENDING;
    private Map<String, Object> submittedValues = Collections.emptyMap();
    private String submittedBy;
    private long submittedAt;

    /**
     * 创建默认待提交且不进入模型上下文的表单消息。
     */
    public AgentFormMessage() {
        setModelVisible(false);
    }

    /**
     * 创建与 USER_INPUT Suspension 对应的待填写表单消息。
     */
    public static AgentFormMessage request(String turnId, String actionId,
                                           String formKey, Map<String, ?> schema) {
        if (blank(turnId) || blank(actionId) || blank(formKey)
            || schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException(
                "turnId, actionId, formKey and schema must not be empty");
        }
        AgentFormMessage result = new AgentFormMessage();
        result.setMessageId(turnId + ":input:" + actionId);
        result.turnId = turnId;
        result.actionId = actionId;
        result.formKey = formKey;
        result.schema = immutableMap(schema);
        Object title = schema.get("title");
        result.content = title == null ? formKey : String.valueOf(title);
        return result;
    }

    /**
     * 返回标记为已提交的新消息，原消息保持不变。
     */
    public AgentFormMessage submitted(Map<String, ?> values, String operator, long time) {
        AgentFormMessage result = copy();
        result.status = Status.SUBMITTED;
        result.submittedValues = immutableMap(values);
        result.submittedBy = operator;
        result.submittedAt = time;
        return result;
    }

    /**
     * 返回标记为已取消的新消息，原消息保持不变。
     */
    public AgentFormMessage cancelled(long time) {
        AgentFormMessage result = copy();
        result.status = Status.CANCELLED;
        result.submittedAt = time;
        return result;
    }

    /**
     * 待填写时页面显示 SUBMIT，其他状态不再接受操作。
     */
    public List<String> getActions() {
        return status == Status.PENDING
            ? Collections.singletonList("SUBMIT") : Collections.<String>emptyList();
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String value) {
        turnId = value;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String value) {
        actionId = value;
    }

    public String getFormKey() {
        return formKey;
    }

    public void setFormKey(String value) {
        formKey = value;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }

    public void setSchema(Map<String, ?> value) {
        schema = immutableMap(value);
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status value) {
        status = value == null ? Status.PENDING : value;
    }

    public Map<String, Object> getSubmittedValues() {
        return submittedValues;
    }

    public void setSubmittedValues(Map<String, ?> value) {
        submittedValues = immutableMap(value);
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String value) {
        submittedBy = value;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(long value) {
        submittedAt = value;
    }

    /**
     * 深复制表单定义、提交值和通用消息状态，供乐观更新使用。
     *
     * @return 与当前消息隔离的副本
     */
    @Override
    public AgentFormMessage copy() {
        AgentFormMessage copy = new AgentFormMessage();
        copy.content = content;
        copy.turnId = turnId;
        copy.actionId = actionId;
        copy.formKey = formKey;
        copy.schema = immutableMap(schema);
        copy.status = status;
        copy.submittedValues = immutableMap(submittedValues);
        copy.submittedBy = submittedBy;
        copy.submittedAt = submittedAt;
        return copyMessageStateTo(copy);
    }

    /**
     * 复制表单 JSON 数据并返回保持字段顺序的不可修改 Map。
     */
    private static Map<String, Object> immutableMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Collections.emptyMap();
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    /**
     * @return 字符串为 {@code null}、空或仅含空白时返回 {@code true}
     */
    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
