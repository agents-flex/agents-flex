package com.agentsflex.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.prompt.MemoryPrompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 为一次模型调用构建完整消息边界的上下文窗口。
 *
 * <p>该类只生成模型视图，不修改业务 ChatMemory 或 Turn 中保存的完整历史。历史按 UserMessage
 * 划分为 Turn，旧的已完成工具 Turn 可以压缩为用户问题和最终 AI 回复；当前 Turn 始终保留完整
 * ToolCall/ToolMessage 协议，避免模型收到孤立的 ToolMessage 或未闭合 ToolCall。</p>
 */
final class AgentContextWindow {

    private AgentContextWindow() {
    }

    static MemoryPrompt build(MemoryPrompt source, int maxTurns, int maxMessages,
                              boolean compactCompletedToolTurns) {
        return build(source, maxTurns, maxMessages, compactCompletedToolTurns, 0, null);
    }

    static MemoryPrompt build(MemoryPrompt source, int maxTurns, int maxMessages,
                              boolean compactCompletedToolTurns, int keepRecentTurns,
                              AgentContextCompressor contextCompressor) {
        if (source == null) throw new IllegalArgumentException("source prompt must not be null");
        List<Message> history = readHistory(source.getMemory(), maxTurns);
        List<List<Message>> turns = splitTurns(history);
        int from = Math.max(0, turns.size() - maxTurns);
        // 当前 Turn 永远不能被压缩；即使配置为 0，也至少保护当前这一轮。
        int compressionEnd = Math.max(from, turns.size() - Math.max(1, keepRecentTurns));
        List<Message> semanticInput = new ArrayList<>();
        boolean allCompressible = true;
        for (int index = from; index < compressionEnd; index++) {
            semanticInput.addAll(turns.get(index));
            allCompressible &= isCompletedTurn(turns.get(index));
        }
        List<Message> semanticOutput = null;
        if (contextCompressor != null && allCompressible && !semanticInput.isEmpty()) {
            semanticOutput = contextCompressor.compress(Collections.unmodifiableList(copy(semanticInput)));
            validateCompressedMessages(semanticOutput);
        }
        // 每个元素都是不可拆分的历史单元：语义压缩结果、一个旧 Turn 或一个受保护 Turn。
        // 超过消息上限时只删除最早单元，永远不会从 ToolCall/ToolMessage 中间截断。
        List<List<Message>> units = new ArrayList<>();
        int protectedStart;
        if (semanticOutput != null) {
            units.add(copy(semanticOutput));
            protectedStart = 1;
        } else {
            protectedStart = compressionEnd - from;
        }
        for (int index = compressionEnd; index < turns.size(); index++) {
            units.add(copy(turns.get(index)));
        }
        if (semanticOutput == null) {
            units.clear();
            for (int index = from; index < compressionEnd; index++) {
                List<Message> turn = turns.get(index);
                boolean current = index == turns.size() - 1;
                units.add(compactCompletedToolTurns && !current ? compact(turn) : copy(turn));
            }
            for (int index = compressionEnd; index < turns.size(); index++) {
                units.add(copy(turns.get(index)));
            }
        }
        int firstUnit = 0;
        int size = countMessages(units);
        while (size > maxMessages && firstUnit < protectedStart) {
            size -= units.get(firstUnit++).size();
        }
        List<Message> selected = new ArrayList<>();
        for (int index = firstUnit; index < units.size(); index++) selected.addAll(units.get(index));

        MemoryPrompt result = new MemoryPrompt();
        result.setMetadataMap(source.getMetadataMap());
        result.setSystemMessage(source.getSystemMessage() == null
            ? null : source.getSystemMessage().copy());
        result.setTools(source.getTools());
        result.setToolGroups(source.getToolGroups());
        result.setChatInterceptorProviders(source.getChatInterceptorProviders());
        result.setToolChoice(source.getToolChoice());
        result.setMaxAttachedMessageCount(Math.max(1, selected.size()));
        result.addMessages(selected);
        if (source.getTemporaryMessages() != null) {
            for (Message message : source.getTemporaryMessages()) {
                result.addMessageTemporary(AgentMessageUtils.copyMessage(message));
            }
        }
        return result;
    }

    private static List<Message> readHistory(ChatMemory memory, int maxTurns) {
        if (memory == null) return Collections.emptyList();
        List<Message> chronological = new ArrayList<>();
        int offset = 0;
        int pageSize = 64;
        while (true) {
            List<Message> page = memory.getMessages(offset, pageSize);
            if (page == null || page.isEmpty()) break;
            List<Message> visible = new ArrayList<>();
            for (Message message : page) {
                if (message != null && message.isModelVisible()) visible.add(message);
            }
            chronological.addAll(0, visible);
            if (countUsers(chronological) >= maxTurns + 1 || page.size() < pageSize) break;
            offset += page.size();
        }
        return chronological;
    }

    private static List<List<Message>> splitTurns(List<Message> messages) {
        List<List<Message>> turns = new ArrayList<>();
        List<Message> current = null;
        for (Message message : messages) {
            if (message instanceof UserMessage) {
                current = new ArrayList<>();
                turns.add(current);
            }
            if (current != null) current.add(message);
        }
        return turns;
    }

    private static List<Message> compact(List<Message> turn) {
        if (!containsToolProtocol(turn)) return copy(turn);
        Message user = turn.get(0);
        for (int index = turn.size() - 1; index > 0; index--) {
            Message message = turn.get(index);
            if (message instanceof AiMessage) {
                AiMessage ai = (AiMessage) message;
                if (!ai.hasToolCalls() && (ai.getContent() != null || ai.getReasoningContent() != null)) {
                    List<Message> result = new ArrayList<>(2);
                    result.add(AgentMessageUtils.copyMessage(user));
                    result.add(AgentMessageUtils.copyMessage(ai));
                    return result;
                }
                break;
            }
        }
        // 未闭合或无最终正文的历史 Turn 不能猜测其结果，保留完整协议。
        return copy(turn);
    }

    private static void validateCompressedMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("contextCompressor must return at least one message");
        }
        if (!(messages.get(0) instanceof UserMessage)) {
            throw new IllegalArgumentException("contextCompressor result must start with UserMessage");
        }
        AiMessage lastToolCallMessage = null;
        java.util.Set<String> returnedToolCallIds = new java.util.HashSet<>();
        java.util.Set<String> expectedToolCallIds = new java.util.HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message == null || !message.isModelVisible()) {
                throw new IllegalArgumentException("contextCompressor result contains invalid message");
            }
            if (message instanceof ToolMessage) {
                if (lastToolCallMessage == null || !matchesToolCall(lastToolCallMessage, (ToolMessage) message)
                    || !returnedToolCallIds.add(((ToolMessage) message).getToolCallId())) {
                    throw new IllegalArgumentException("contextCompressor result contains orphan ToolMessage");
                }
                expectedToolCallIds.remove(((ToolMessage) message).getToolCallId());
            } else if (message instanceof AiMessage && ((AiMessage) message).hasToolCalls()) {
                if (!expectedToolCallIds.isEmpty()) {
                    throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
                }
                lastToolCallMessage = (AiMessage) message;
                returnedToolCallIds.clear();
                expectedToolCallIds.clear();
                for (com.agentsflex.core.message.ToolCall call : lastToolCallMessage.getToolCalls()) {
                    if (call.getId() != null) expectedToolCallIds.add(call.getId());
                }
            } else {
                if (!expectedToolCallIds.isEmpty()) {
                    throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
                }
                lastToolCallMessage = null;
                returnedToolCallIds.clear();
            }
        }
        if (!expectedToolCallIds.isEmpty()) {
            throw new IllegalArgumentException("contextCompressor result contains incomplete ToolCall");
        }
    }

    private static boolean matchesToolCall(AiMessage assistant, ToolMessage result) {
        if (result.getToolCallId() == null) return false;
        for (com.agentsflex.core.message.ToolCall call : assistant.getToolCalls()) {
            if (result.getToolCallId().equals(call.getId())) return true;
        }
        return false;
    }

    private static int countMessages(List<List<Message>> units) {
        int count = 0;
        for (List<Message> unit : units) count += unit.size();
        return count;
    }

    private static boolean containsToolProtocol(List<Message> turn) {
        for (Message message : turn) {
            if (message instanceof ToolMessage
                || (message instanceof AiMessage && ((AiMessage) message).hasToolCalls())) return true;
        }
        return false;
    }

    private static boolean isCompletedTurn(List<Message> turn) {
        if (turn.isEmpty()) return false;
        for (int index = turn.size() - 1; index >= 0; index--) {
            Message message = turn.get(index);
            if (message instanceof AiMessage) {
                AiMessage ai = (AiMessage) message;
                return !ai.hasToolCalls()
                    && (ai.getContent() != null || ai.getReasoningContent() != null);
            }
        }
        return false;
    }

    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) result.add(AgentMessageUtils.copyMessage(message));
        return result;
    }

    private static int countUsers(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (message instanceof UserMessage) count++;
        return count;
    }
}
