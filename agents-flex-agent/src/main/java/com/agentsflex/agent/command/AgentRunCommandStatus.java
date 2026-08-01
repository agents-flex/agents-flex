package com.agentsflex.agent.command;

/** 持久化恢复命令的处理状态。 */
public enum AgentRunCommandStatus {
    /** 尚未被 Worker 领取，或者失败后已释放等待重试。 */
    PENDING,
    /** 已被某个 Worker 领取且租约仍可能有效。 */
    CLAIMED,
    /** 恢复动作已经成功应用到目标 Run。 */
    COMPLETED,
    /** 多次处理失败后不再自动重试。 */
    FAILED
}
