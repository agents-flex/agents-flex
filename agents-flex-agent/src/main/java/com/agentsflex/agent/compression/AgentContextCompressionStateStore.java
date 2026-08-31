package com.agentsflex.agent.compression;

/**
 * 业务侧实现的摘要状态存储；save 使用版本号进行 CAS。
 *
 * <p>load 和 compress 的输入应基于同一会话的完整、按时间升序排列的消息历史。
 * 如果历史裁剪掉了 {@code coveredUntilMessageId}，处理器会拒绝静默重复压缩并抛出异常。</p>
 */
public interface AgentContextCompressionStateStore {
    /**
     * 未找到状态时可以返回 {@code null}，处理器会按空状态处理。
     */
    AgentContextCompressionState load(String conversationId);

    /**
     * expectedVersion 为 0 表示首次创建，成功后必须持久化 state.getVersion()。
     */
    boolean save(String conversationId, AgentContextCompressionState state, long expectedVersion);
}
