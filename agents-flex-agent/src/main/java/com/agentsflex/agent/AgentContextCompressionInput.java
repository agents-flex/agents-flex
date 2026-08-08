package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提供给压缩触发器的新增历史统计。
 */
public final class AgentContextCompressionInput {
    private final List<Message> pendingMessages;
    private final List<Message> summaryMessages;
    private final long estimatedPendingTokens;
    private final int pendingTurnCount;
    private final AgentContextCompressionState state;

    public AgentContextCompressionInput(List<Message> pendingMessages, List<Message> summaryMessages,
                                        long estimatedPendingTokens, int pendingTurnCount,
                                        AgentContextCompressionState state) {
        this.pendingMessages = copy(pendingMessages);
        this.summaryMessages = copy(summaryMessages);
        this.estimatedPendingTokens = estimatedPendingTokens;
        this.pendingTurnCount = pendingTurnCount;
        this.state = state;
    }

    public List<Message> getPendingMessages() {
        return pendingMessages;
    }

    public List<Message> getSummaryMessages() {
        return summaryMessages;
    }

    public long getEstimatedPendingTokens() {
        return estimatedPendingTokens;
    }

    public int getPendingTurnCount() {
        return pendingTurnCount;
    }

    public AgentContextCompressionState getState() {
        return state;
    }

    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(AgentMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
