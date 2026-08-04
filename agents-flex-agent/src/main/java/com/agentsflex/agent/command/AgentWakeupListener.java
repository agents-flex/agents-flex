package com.agentsflex.agent.command;

/**
 * 恢复命令持久化后通知外部调度系统唤醒目标 Run。
 *
 * <p>该回调不消费命令，也不直接推进 Run，只用于触发 Worker 调度。Runner 会隔离监听器异常，
 * 因此可靠唤醒应由业务调度系统结合 AgentRunCommandStore 的待处理命令扫描实现。</p>
 */
@FunctionalInterface
public interface AgentWakeupListener {
    /**
     * 接收已经成功写入命令 Store 的命令。
     *
     * @param command 可供 Worker 后续领取的持久化命令
     */
    void onWakeup(AgentRunCommand command);
}
