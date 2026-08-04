package com.agentsflex.agent.context;

/**
 * 上下文管理器完成一次处理后的统计结果。
 *
 * <p>Runner 根据 {@link #isChanged()} 判断是否需要立即保存 Snapshot 和发布上下文压缩事件。
 * 消息数量用于监控压缩效果，不参与后续状态计算。</p>
 */
public final class AgentContextUpdate {
    /**
     * 未发生上下文变更时复用的不可变结果，避免重复创建短生命周期对象。
     */
    private static final AgentContextUpdate UNCHANGED = new AgentContextUpdate(false, 0, 0);
    /**
     * 是否实际修改了 Run 的消息上下文。
     */
    private final boolean changed;
    /**
     * 本次处理从上下文中移除或合并的消息数量。
     */
    private final int removedMessageCount;
    /**
     * 上下文处理完成后仍保留的消息数量。
     */
    private final int remainingMessageCount;

    /**
     * 创建上下文处理结果。
     *
     * @param changed               是否修改了消息上下文
     * @param removedMessageCount   被移除或合并的消息数量
     * @param remainingMessageCount 处理后保留的消息数量
     */
    public AgentContextUpdate(boolean changed, int removedMessageCount, int remainingMessageCount) {
        this.changed = changed;
        this.removedMessageCount = removedMessageCount;
        this.remainingMessageCount = remainingMessageCount;
    }

    /**
     * @return 表示没有修改消息历史的共享结果
     */
    public static AgentContextUpdate unchanged() {
        return UNCHANGED;
    }

    /**
     * @return 是否修改了 Run 中可持久化的消息历史
     */
    public boolean isChanged() {
        return changed;
    }

    /**
     * @return 本次处理移除或合并的消息数量
     */
    public int getRemovedMessageCount() {
        return removedMessageCount;
    }

    /**
     * @return 处理完成后保留的消息数量
     */
    public int getRemainingMessageCount() {
        return remainingMessageCount;
    }
}
