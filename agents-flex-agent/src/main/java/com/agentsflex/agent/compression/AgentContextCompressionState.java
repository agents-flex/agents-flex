package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.Serializable;

/**
 * 可由业务侧持久化的增量压缩状态。
 *
 * <p>{@code coveredUntilMessageId} 是幂等增量压缩的游标；摘要成功后，游标之前的原始消息
 * 不会再次交给压缩器。{@code version} 用于 Store 的 CAS。压缩次数和 Token/Turn 统计属于
 * 事件或监控数据，不放入持久化状态。</p>
 */
public final class AgentContextCompressionState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final long version;
    private final List<Message> summaryMessages;
    private final String coveredUntilMessageId;

    /**
     * 创建可持久化的压缩状态快照。
     *
     * @param version                Store CAS 版本
     * @param summaryMessages        已生成的模型摘要消息
     * @param coveredUntilMessageId  摘要覆盖的最后消息 ID
     */
    public AgentContextCompressionState(long version, List<Message> summaryMessages,
                                        String coveredUntilMessageId) {
        this.version = version;
        this.summaryMessages = copy(summaryMessages);
        this.coveredUntilMessageId = coveredUntilMessageId;
    }

    /**
     * @return 尚未覆盖任何消息、版本为零的初始压缩状态
     */
    public static AgentContextCompressionState empty() {
        return new AgentContextCompressionState(0, Collections.emptyList(), null);
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

    /**
     * 深复制并冻结摘要消息，防止持久化状态被调用方间接修改。
     */
    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(CompressionMessageUtils.copyMessage(message));
        }
        return Collections.unmodifiableList(result);
    }
}
