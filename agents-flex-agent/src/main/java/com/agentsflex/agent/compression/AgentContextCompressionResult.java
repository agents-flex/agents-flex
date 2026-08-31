package com.agentsflex.agent.compression;

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

    /**
     * 创建一次协调结果，并复制最终模型消息。
     *
     * @param compressed    本次是否实际调用压缩器并保存新状态
     * @param state         本次结束后的持久化状态
     * @param modelMessages 应用于模型上下文的摘要和新增消息
     */
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

    /**
     * 深复制非空消息并冻结结果，避免结果对象泄漏可变消息列表。
     */
    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(CompressionMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
