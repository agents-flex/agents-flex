package com.agentsflex.agent;

import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.message.*;
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

    /**
     * 使用默认压缩配置构建模型上下文，保留旧调用方的包内兼容入口。
     */
    static MemoryPrompt build(MemoryPrompt source, int maxTurns, int maxMessages,
                              boolean compactCompletedToolTurns) {
        return build(source, maxTurns, maxMessages, compactCompletedToolTurns, 0, null);
    }

    /**
     * 根据完整 Turn 边界创建一次模型调用的消息视图。
     *
     * <p>这里的裁剪是非破坏性的：只复制消息并组装新的 Prompt，绝不会调用 ChatMemory.clear()
     * 或修改持久化历史。算法先按 Turn 数选取历史，再对更早历史执行语义/规则压缩，最后只从
     * 可压缩的最早单元开始处理消息数上限；最近 Turn 和当前 Turn 永远不被拆开。</p>
     */
    static MemoryPrompt build(MemoryPrompt source, int maxTurns, int maxMessages,
                              boolean compactCompletedToolTurns, int keepRecentTurns,
                              AgentContextCompressor contextCompressor) {
        if (source == null) throw new IllegalArgumentException("source prompt must not be null");
        // 多取一轮用于识别边界；最终发送窗口仍严格限制为 maxTurns。
        List<Message> history = readHistory(source.getMemory(), maxTurns);
        List<List<Message>> turns = splitTurns(history);
        int from = Math.max(0, turns.size() - maxTurns);
        // 当前 Turn 永远不能被压缩；即使配置为 0，也至少保护当前这一轮。
        int compressionEnd = Math.max(from, turns.size() - Math.max(1, keepRecentTurns));
        List<Message> semanticInput = new ArrayList<>();
        boolean allCompressible = true;
        // 语义压缩只接收较早、已完成的 Turn；挂起或失败的协议消息不能交给摘要器猜测。
        for (int index = from; index < compressionEnd; index++) {
            List<Message> turn = turns.get(index);
            // compactCompletedToolTurns 是独立的压缩策略；配置语义压缩器时，先将较早工具 Turn
            // 压缩为 UserMessage + 最终 AiMessage，再把结果交给语义压缩器。
            semanticInput.addAll(compactCompletedToolTurns ? compact(turn) : copy(turn));
            allCompressible &= isCompletedTurn(turn);
        }
        List<Message> semanticOutput = null;
        if (contextCompressor != null && allCompressible && !semanticInput.isEmpty()) {
            // 传入不可修改副本，防止业务压缩器意外修改 Runner 正在使用的历史对象。
            semanticOutput = contextCompressor.compress(Collections.unmodifiableList(copy(semanticInput)));
            validateCompressedMessages(semanticOutput);
        }
        // 每个元素都是不可拆分的历史单元：语义压缩结果、一个旧 Turn 或一个受保护 Turn。
        // 超过消息上限时只删除最早单元，永远不会从 ToolCall/ToolMessage 中间截断。
        List<List<Message>> units = new ArrayList<>();
        if (semanticOutput != null) {
            units.add(copy(semanticOutput));
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
        // Drop the oldest complete units until the message budget is met. The
        // newest unit is always retained so the current turn cannot disappear.
        while (size > maxMessages && firstUnit < units.size() - 1) {
            size -= units.get(firstUnit++).size();
        }
        List<Message> selected = new ArrayList<>();
        for (int index = firstUnit; index < units.size(); index++) selected.addAll(units.get(index));

        // 只复制 Prompt 的配置和模型可见消息；temporaryMessages 仍作为 UI/运行时消息单独复制。
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
            // ChatMemory 的分页结果按旧到新返回；逐页前插后得到全局旧到新顺序，避免一次性读取 MAX_VALUE。
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
            // 一个 UserMessage 开始一个 Turn；前置孤立的 AI/Tool 消息没有合法会话起点，直接忽略。
            if (message instanceof UserMessage) {
                current = new ArrayList<>();
                turns.add(current);
            }
            if (current != null) current.add(message);
        }
        return turns;
    }

    private static List<Message> compact(List<Message> turn) {
        // 没有工具协议的普通 Turn 不需要规则压缩，保留原有 User/AI 消息。
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
        int firstConversationMessage = messages.get(0) instanceof SystemMessage ? 1 : 0;
        if (firstConversationMessage >= messages.size()
            || !(messages.get(firstConversationMessage) instanceof UserMessage)) {
            throw new IllegalArgumentException("contextCompressor result must start with SystemMessage optionally followed by UserMessage");
        }
        // ToolMessage 可以连续出现，因此不能只检查它的直接前一条消息；使用最近一条
        // ToolCall AiMessage 和待回收 ID 集合校验完整配对关系。
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
        // 一个 AiMessage 可能同时声明多个工具调用，结果只要命中其中一个 ID 即合法。
        if (result.getToolCallId() == null) return false;
        for (com.agentsflex.core.message.ToolCall call : assistant.getToolCalls()) {
            if (result.getToolCallId().equals(call.getId())) return true;
        }
        return false;
    }

    private static int countMessages(List<List<Message>> units) {
        // 单元是裁剪的最小粒度，统计时不能把其中的协议消息拆开计算。
        int count = 0;
        for (List<Message> unit : units) count += unit.size();
        return count;
    }

    private static boolean containsToolProtocol(List<Message> turn) {
        // 只有真正包含 ToolCall/ToolMessage 的 Turn 才需要删除中间工具消息并保留最终回复。
        for (Message message : turn) {
            if (message instanceof ToolMessage
                || (message instanceof AiMessage && ((AiMessage) message).hasToolCalls())) return true;
        }
        return false;
    }

    private static boolean isCompletedTurn(List<Message> turn) {
        // 最后一条 AI 正文代表该 Turn 已收束；最后仍是 ToolCall 或没有正文时视为未完成。
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
        // Message.copy 还需要由 AgentMessageUtils 处理具体子类，避免共享可变 ToolCall 列表。
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) result.add(AgentMessageUtils.copyMessage(message));
        return result;
    }

    private static int countUsers(List<Message> messages) {
        // UserMessage 数量用于分页停止判断，确保至少读到 maxTurns 个完整 Turn 的起点。
        int count = 0;
        for (Message message : messages) if (message instanceof UserMessage) count++;
        return count;
    }
}
