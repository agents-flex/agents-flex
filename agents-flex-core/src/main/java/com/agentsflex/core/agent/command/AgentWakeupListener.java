package com.agentsflex.core.agent.command;

/** 命令持久化后通知调度系统有任务可唤醒。 */
@FunctionalInterface
public interface AgentWakeupListener {
    void onWakeup(AgentRunCommand command);
}
