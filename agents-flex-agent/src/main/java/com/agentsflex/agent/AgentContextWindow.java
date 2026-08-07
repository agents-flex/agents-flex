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
        if (source == null) throw new IllegalArgumentException("source prompt must not be null");
        List<Message> history = readHistory(source.getMemory(), maxTurns);
        List<List<Message>> turns = splitTurns(history);
        List<Message> selected = new ArrayList<>();
        int from = Math.max(0, turns.size() - maxTurns);
        for (int index = from; index < turns.size(); index++) {
            List<Message> turn = turns.get(index);
            boolean current = index == turns.size() - 1;
            selected.addAll(compactCompletedToolTurns && !current
                ? compact(turn) : copy(turn));
        }

        // 只移除较早的完整 Turn，绝不从当前 Turn 中间切断协议消息。
        while (selected.size() > maxMessages && turns.size() - from > 1) {
            from++;
            selected.clear();
            for (int index = from; index < turns.size(); index++) {
                List<Message> turn = turns.get(index);
                boolean current = index == turns.size() - 1;
                selected.addAll(compactCompletedToolTurns && !current
                    ? compact(turn) : copy(turn));
            }
        }

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

    private static boolean containsToolProtocol(List<Message> turn) {
        for (Message message : turn) {
            if (message instanceof ToolMessage
                || (message instanceof AiMessage && ((AiMessage) message).hasToolCalls())) return true;
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
