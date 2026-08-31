package com.agentsflex.agent.compression;

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

    /**
     * 创建供触发器只读判断的压缩输入，并复制两组消息以隔离调用方修改。
     *
     * @param pendingMessages        游标之后尚未压缩的消息
     * @param summaryMessages        已持久化的摘要消息
     * @param estimatedPendingTokens 新增消息的估算 Token 数
     * @param pendingTurnCount       新增消息覆盖的 Turn 数
     * @param state                  当前持久化压缩状态
     */
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

    /**
     * 深复制非空消息并返回不可修改列表，空输入按空列表处理。
     */
    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(CompressionMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
