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

    AgentRunnerChatMemory(ChatMemoryProvider provider) {
        this.provider = provider;
    }

    boolean isEnabled() {
        return provider != null;
    }

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

    private void copyMetadata(AgentSuspension suspension, Message message) {
        // Metadata 基于 ConcurrentHashMap，不接受策略或模型参数中的可选 null 值。
        for (Map.Entry<String, Object> entry : suspension.getMetadata().entrySet()) {
            if (entry.getValue() != null) message.putMetadata(entry.getKey(), entry.getValue());
        }
    }

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

    private AgentActionMessage findAction(ChatMemory memory, String messageId) {
        Message message = memory.getMessage(messageId);
        return message instanceof AgentActionMessage
            ? ((AgentActionMessage) message).copy() : null;
    }

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

    private AgentFormMessage findForm(ChatMemory memory, String messageId) {
        Message message = memory.getMessage(messageId);
        return message instanceof AgentFormMessage
            ? ((AgentFormMessage) message).copy() : null;
    }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) value) : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata(AgentTurn turn) {
        Object value = turn.getMetadata().get("lastResumeCommandMetadata");
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

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
