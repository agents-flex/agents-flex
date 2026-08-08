package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可由业务侧持久化的增量压缩状态。
 *
 * <p>{@code coveredUntilMessageId} 是幂等增量压缩的游标；摘要成功后，游标之前的原始消息
 * 不会再次交给压缩器。{@code version} 用于 Store 的 CAS，{@code compressionVersion} 用于
 * 业务审计和监控，两者不要混用。</p>
 */
public final class AgentContextCompressionState {
    private final long version;
    private final List<Message> summaryMessages;
    private final String coveredUntilMessageId;
    private final long compressionVersion;
    private final long estimatedCoveredTokens;
    private final int coveredTurnCount;

    public AgentContextCompressionState(long version, List<Message> summaryMessages,
                                        String coveredUntilMessageId, long compressionVersion,
                                        long estimatedCoveredTokens, int coveredTurnCount) {
        this.version = version;
        this.summaryMessages = copy(summaryMessages);
        this.coveredUntilMessageId = coveredUntilMessageId;
        this.compressionVersion = compressionVersion;
        this.estimatedCoveredTokens = estimatedCoveredTokens;
        this.coveredTurnCount = coveredTurnCount;
    }

    public static AgentContextCompressionState empty() {
        return new AgentContextCompressionState(0, Collections.emptyList(), null, 0, 0, 0);
    }

    public long getVersion() {
        return version;
    }

    public List<Message> getSummaryMessages() {
        return copy(summaryMessages);
    }

    public String getCoveredUntilMessageId() {
        return coveredUntilMessageId;
    }

    public long getCompressionVersion() {
        return compressionVersion;
    }

    public long getEstimatedCoveredTokens() {
        return estimatedCoveredTokens;
    }

    public int getCoveredTurnCount() {
        return coveredTurnCount;
    }

    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(AgentMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
