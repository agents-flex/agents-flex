package com.agentsflex.core.agent.command;

/** 持久化恢复命令的处理状态。 */
public enum AgentRunCommandStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    FAILED
}
