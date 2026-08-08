package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次增量压缩的结果，包含当前摘要和尚未压缩的尾部消息。
 */
public final class AgentContextCompressionResult {
    private final boolean compressed;
    private final AgentContextCompressionState state;
    private final List<Message> modelMessages;

    public AgentContextCompressionResult(boolean compressed, AgentContextCompressionState state,
                                         List<Message> modelMessages) {
        this.compressed = compressed;
        this.state = state;
        this.modelMessages = copy(modelMessages);
    }

    public boolean isCompressed() {
        return compressed;
    }

    public AgentContextCompressionState getState() {
        return state;
    }

    public List<Message> getModelMessages() {
        return modelMessages;
    }

    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(AgentMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
