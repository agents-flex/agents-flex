/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.message.AgentActionMessage;
import com.agentsflex.agent.message.AgentFormMessage;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.ChatMemoryProvider;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将已保存的 Turn 消息幂等投影到业务 ChatMemory。
 */
final class AgentRunnerChatMemory {

    private static final Logger log = LoggerFactory.getLogger(AgentRunnerChatMemory.class);
    private final ChatMemoryProvider provider;

    /**
     * 创建会话消息投影器。
     *
     * @param provider 按 conversationId 提供 ChatMemory 的工厂；为 {@code null} 时禁用投影
     */
    AgentRunnerChatMemory(ChatMemoryProvider provider) {
        this.provider = provider;
    }

    boolean isEnabled() {
        return provider != null;
    }

    /**
     * 读取指定会话最近的模型可见历史，并移除持久化的旧系统指令。
     *
     * @param conversationId 业务会话 ID
     * @param maxMessages    最大消息数
     * @return 按时间正序排列的历史；存储返回空值时返回空列表
     */
    List<Message> loadModelHistory(String conversationId, int maxMessages) {
        List<Message> messages = memory(conversationId).getModelMessages(maxMessages);
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        List<Message> history = new ArrayList<>(messages.size());
        for (Message message : messages) {
            // AgentTurn 始终使用当前 Agent 的系统指令，不继承业务会话里可能保存的旧 SystemMessage。
            if (!(message instanceof SystemMessage)) history.add(message);
        }
        return history;
    }

    /**
     * 将 Turn 新增消息及交互动作幂等投影到业务 ChatMemory。
     *
     * <p>投影不是执行事实来源，因此存储异常只记录日志；后续 Snapshot 保存或恢复会再次尝试同步，
     * 不允许投影失败改变 Turn 状态。</p>
     *
     * @param turn 已保存或刚完成状态转换的 Turn
     */
    void sync(AgentTurn turn) {
        if (!isEnabled() || turn == null || !StringUtil.hasText(turn.getConversationId())) return;
        try {
            ChatMemory memory = memory(turn.getConversationId());
            List<Message> messages = turn.getConversationHistory();
            int start = Math.min(turn.getConversationBaseMessageCount(), messages.size());
            for (int index = start; index < messages.size(); index++) {
                memory.addMessageIfAbsent(AgentMessageUtils.copyMessage(messages.get(index)));
            }
            syncActions(memory, turn);
        } catch (RuntimeException error) {
            // Snapshot 是执行事实来源；对话投影失败由后续 restore/save 自动补偿，不能反向破坏 Turn。
            log.warn("Agent ChatMemory synchronization failed, turnId={}", turn.getId(), error);
        }
    }

    /**
     * 根据挂起、恢复或终态信息创建并更新审批卡片、用户输入表单。
     *
     * @param memory 目标会话存储
     * @param turn   提供当前挂起状态和最近恢复命令的 Turn
     */
    private void syncActions(ChatMemory memory, AgentTurn turn) {
        AgentSuspension suspension = turn.getSuspension();
        if (suspension != null) {
            if (suspension.getType() == AgentSuspensionType.TOOL_APPROVAL) {
                AgentActionMessage action = AgentActionMessage.toolApproval(turn.getId(),
                    suspension.getCorrelationId(), suspension.getMessage());
                copyMetadata(suspension, action);
                memory.addMessageIfAbsent(action);
                return;
            }
            if (suspension.getType() == AgentSuspensionType.USER_INPUT
                && StringUtil.hasText(suspension.getCorrelationId())
                && StringUtil.hasText(stringValue(suspension.getMetadata().get("formKey")))) {
                AgentFormMessage form = AgentFormMessage.request(
                    turn.getId(), suspension.getCorrelationId(),
                    stringValue(suspension.getMetadata().get("formKey")),
                    mapValue(suspension.getMetadata().get("schema")));
                memory.addMessageIfAbsent(form);
                return;
            }
        }

        if (turn.getStatus().isTerminal()) {
            for (ToolCall call : turn.getPendingToolCalls()) {
                if (call != null) cancelForm(memory, turn, call.getId());
            }
        }

        Object commandType = turn.getMetadata().get("lastResumeCommand");
        Object correlationId = turn.getMetadata().get("lastResumeCorrelationId");
        if (correlationId == null || commandType == null) return;
        if (AgentResumeCommandType.USER_INPUT.name().equals(commandType)) {
            resolveForm(memory, turn, String.valueOf(correlationId));
            return;
        }
        AgentActionMessage.Status status;
        if (AgentResumeCommandType.APPROVE_TOOL.name().equals(commandType)) {
            status = AgentActionMessage.Status.APPROVED;
        } else if (AgentResumeCommandType.REJECT_TOOL.name().equals(commandType)) {
            status = AgentActionMessage.Status.REJECTED;
        } else return;
        resolve(memory, turn, String.valueOf(correlationId), status);
    }

    /**
     * 把挂起元数据中的非空值复制到页面动作消息。
     *
     * @param suspension 元数据来源
     * @param message    目标动作消息
     */
    private void copyMetadata(AgentSuspension suspension, Message message) {
        // Metadata 基于 ConcurrentHashMap，不接受策略或模型参数中的可选 null 值。
        for (Map.Entry<String, Object> entry : suspension.getMetadata().entrySet()) {
            if (entry.getValue() != null) message.putMetadata(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 使用乐观锁把待处理审批动作更新为批准或拒绝。
     *
     * <p>发生版本冲突时最多重新读取并重试三次；动作已经结束或不存在时直接返回。</p>
     *
     * @param memory   会话存储
     * @param turn     包含审计信息和拒绝原因的 Turn
     * @param actionId 工具调用关联 ID
     * @param status   目标审批终态
     */
    private void resolve(ChatMemory memory, AgentTurn turn, String actionId,
                         AgentActionMessage.Status status) {
        String messageId = turn.getId() + ":approval:" + actionId;
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentActionMessage current = findAction(memory, messageId);
            if (current == null || current.getStatus() != AgentActionMessage.Status.PENDING) return;
            Map<String, Object> audit = auditMetadata(turn);
            String operator = stringValue(audit.get("operatorId"));
            if (operator == null) operator = stringValue(audit.get("approverId"));
            if (operator == null) operator = stringValue(audit.get("resolvedBy"));
            String reason = stringValue(turn.getMetadata().get("toolRejectionReason." + actionId));
            AgentActionMessage updated = current.resolved(status, operator, reason,
                System.currentTimeMillis());
            if (memory.updateMessage(updated, current.getVersion())) return;
        }
    }

    /**
     * 按稳定消息 ID 查询审批动作，并返回避免共享可变状态的副本。
     *
     * @param memory    会话存储
     * @param messageId 审批消息 ID
     * @return 审批动作副本；消息不存在或类型不匹配时返回 {@code null}
     */
    private AgentActionMessage findAction(ChatMemory memory, String messageId) {
        Message message = memory.getMessage(messageId);
        return message instanceof AgentActionMessage
            ? ((AgentActionMessage) message).copy() : null;
    }

    /**
     * 使用提交结果和操作者信息完成待处理表单。
     *
     * @param memory   会话存储
     * @param turn     包含提交数据与审计元数据的 Turn
     * @param actionId 用户输入工具调用 ID
     */
    private void resolveForm(ChatMemory memory, AgentTurn turn, String actionId) {
        String messageId = turn.getId() + ":input:" + actionId;
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentFormMessage current = findForm(memory, messageId);
            if (current == null || current.getStatus() != AgentFormMessage.Status.PENDING) return;
            Map<String, Object> audit = auditMetadata(turn);
            String operator = stringValue(audit.get("submittedBy"));
            if (operator == null) operator = stringValue(audit.get("operatorId"));
            AgentFormMessage updated = current.submitted(
                submittedValues(turn, actionId), operator, System.currentTimeMillis());
            if (memory.updateMessage(updated, current.getVersion())) return;
        }
    }

    /**
     * 在 Turn 进入终态但表单仍待处理时，以乐观锁将其标记为取消。
     *
     * @param memory   会话存储
     * @param turn     表单所属 Turn
     * @param actionId 用户输入工具调用 ID；空值会被忽略
     */
    private void cancelForm(ChatMemory memory, AgentTurn turn, String actionId) {
        if (!StringUtil.hasText(actionId)) return;
        String messageId = turn.getId() + ":input:" + actionId;
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentFormMessage current = findForm(memory, messageId);
            if (current == null || current.getStatus() != AgentFormMessage.Status.PENDING) return;
            if (memory.updateMessage(current.cancelled(System.currentTimeMillis()),
                current.getVersion())) return;
        }
    }

    /**
     * 按稳定消息 ID 查询表单消息并返回副本。
     *
     * @param memory    会话存储
     * @param messageId 表单消息 ID
     * @return 表单副本；不存在或类型不匹配时返回 {@code null}
     */
    private AgentFormMessage findForm(ChatMemory memory, String messageId) {
        Message message = memory.getMessage(messageId);
        return message instanceof AgentFormMessage
            ? ((AgentFormMessage) message).copy() : null;
    }

    /**
     * 提取指定用户输入调用实际提交的字段。
     *
     * <p>优先读取 Turn 的结构化工具输入；兼容旧 Snapshot 时再解析对应 ToolMessage JSON。
     * 无法解析或找不到数据时返回空 Map。</p>
     *
     * @param turn     数据来源 Turn
     * @param actionId 用户输入工具调用 ID
     * @return 提交字段的可修改副本
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> submittedValues(AgentTurn turn, String actionId) {
        Map<String, Object> toolInput = turn.getToolInputData(actionId);
        if (!toolInput.isEmpty()) return new LinkedHashMap<>(toolInput);
        for (Message message : turn.getConversationHistory()) {
            if (!(message instanceof ToolMessage)
                || !actionId.equals(((ToolMessage) message).getToolCallId())) continue;
            try {
                Map<String, Object> body = JSON.parseObject(message.getTextContent());
                Object data = body == null ? null : body.get("data");
                if (data instanceof Map) return new LinkedHashMap<>((Map<String, Object>) data);
                Object content = body == null ? null : body.get("content");
                if (content != null) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("content", content);
                    return result;
                }
            } catch (RuntimeException ignored) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    /**
     * 将任意 Map 值转换为保持插入顺序的字符串键 Map 副本。
     *
     * @param value 待转换值
     * @return Map 副本；类型不匹配时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) value) : Collections.emptyMap();
    }

    /**
     * 读取最近恢复命令携带的审计元数据。
     *
     * @param turn 数据来源 Turn
     * @return 审计 Map；没有有效记录时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata(AgentTurn turn) {
        Object value = turn.getMetadata().get("lastResumeCommandMetadata");
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    /**
     * 将可选元数据值转换为字符串。
     *
     * @param value 原始值
     * @return 字符串表示；输入为 {@code null} 时返回 {@code null}
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 校验会话 ID 并从 Provider 获取对应 ChatMemory。
     *
     * @param conversationId 非空业务会话 ID
     * @return Provider 返回的会话存储
     * @throws IllegalArgumentException conversationId 为空时抛出
     * @throws IllegalStateException    Provider 未配置或没有返回存储时抛出
     */
    private ChatMemory memory(String conversationId) {
        if (!StringUtil.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        ChatMemory memory = provider == null ? null : provider.getMemory(conversationId);
        if (memory == null) {
            throw new IllegalStateException("ChatMemory cannot be loaded: " + conversationId);
        }
        return memory;
    }
}
