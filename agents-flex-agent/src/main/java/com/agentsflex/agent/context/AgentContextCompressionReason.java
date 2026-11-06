package com.agentsflex.agent.context;

/**
 * 上下文压缩的稳定原因枚举，用于状态持久化、事件通知和审计。
 */
public enum AgentContextCompressionReason {
    /**
     * 服务端确认的 Usage 已达到配置阈值。
     */
    THRESHOLD_REACHED,
    /**
     * 模型服务返回输入或总上下文超限异常。
     */
    TOKEN_LIMIT_EXCEPTION,
    /**
     * 原有压缩决策器判断需要压缩。
     */
    POLICY_TRIGGERED,
    /**
     * 业务代码或运维操作主动要求压缩。
     */
    MANUAL
}
