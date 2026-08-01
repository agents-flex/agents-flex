package com.agentsflex.core.agent.context;

/** 上下文管理器完成一次处理后的统计结果。 */
public final class AgentContextUpdate {
    private static final AgentContextUpdate UNCHANGED = new AgentContextUpdate(false, 0, 0);
    private final boolean changed;
    private final int removedMessageCount;
    private final int remainingMessageCount;

    public AgentContextUpdate(boolean changed, int removedMessageCount, int remainingMessageCount) {
        this.changed = changed;
        this.removedMessageCount = removedMessageCount;
        this.remainingMessageCount = remainingMessageCount;
    }

    public static AgentContextUpdate unchanged() { return UNCHANGED; }
    public boolean isChanged() { return changed; }
    public int getRemovedMessageCount() { return removedMessageCount; }
    public int getRemainingMessageCount() { return remainingMessageCount; }
}
