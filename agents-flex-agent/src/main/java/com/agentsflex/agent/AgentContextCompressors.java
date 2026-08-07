package com.agentsflex.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 常用的本地上下文压缩策略工厂；不调用模型，也不修改 ChatMemory。
 */
public final class AgentContextCompressors {
    private AgentContextCompressors() {
    }

    /**
     * 返回输入的独立副本，适合调试或暂时关闭压缩。
     */
    public static AgentContextCompressor identity() {
        return AgentContextCompressors::copy;
    }

    /**
     * 每个完整 Turn 只保留 UserMessage 和最终 AI 正文，不完整 Turn 原样保留。
     */
    public static AgentContextCompressor compactCompletedTurns() {
        return messages -> {
            List<Message> result = new ArrayList<>();
            List<Message> turn = new ArrayList<>();
            for (Message message : messages) {
                if (message instanceof UserMessage && !turn.isEmpty()) {
                    appendCompact(result, turn);
                    turn.clear();
                }
                turn.add(message);
            }
            appendCompact(result, turn);
            return result;
        };
    }

    /**
     * 将历史文本提取为单条用户摘要，并限制字符数，适合作为简单兜底策略。
     */
    public static AgentContextCompressor textExcerpt(int maxCharacters) {
        if (maxCharacters <= 0) throw new IllegalArgumentException("maxCharacters must be greater than 0");
        return messages -> {
            StringBuilder text = new StringBuilder();
            for (Message message : messages) {
                if (message instanceof UserMessage || message instanceof AiMessage) {
                    if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
                }
            }
            String value = text.length() <= maxCharacters ? text.toString() : text.substring(0, maxCharacters);
            return Collections.<Message>singletonList(new UserMessage("历史上下文摘要：\n" + value));
        };
    }

    /**
     * 按顺序组合多个压缩器，后一个处理前一个的输出。
     */
    public static AgentContextCompressor chain(AgentContextCompressor... compressors) {
        return messages -> {
            List<Message> current = copy(messages);
            if (compressors != null) for (AgentContextCompressor compressor : compressors) {
                if (compressor != null) current = compressor.compress(Collections.unmodifiableList(current));
            }
            return copy(current);
        };
    }

    private static void appendCompact(List<Message> result, List<Message> turn) {
        if (turn.isEmpty()) return;
        Message user = turn.get(0);
        AiMessage answer = null;
        for (int i = turn.size() - 1; i >= 0; i--) {
            if (turn.get(i) instanceof AiMessage) {
                AiMessage ai = (AiMessage) turn.get(i);
                if (!ai.hasToolCalls() && ai.getTextContent() != null) answer = ai;
                break;
            }
        }
        if (user instanceof UserMessage && answer != null) {
            result.add(((UserMessage) user).copy());
            result.add(answer.copy());
        } else result.addAll(copy(turn));
    }

    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(AgentMessageUtils.copyMessage(message));
        }
        return result;
    }
}
