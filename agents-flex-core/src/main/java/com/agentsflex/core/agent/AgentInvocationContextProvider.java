/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent;

/** Worker 恢复 Run 时根据 Snapshot 重新构造非持久化调用上下文。 */
@FunctionalInterface
public interface AgentInvocationContextProvider {

    /** @return 当前 Worker 执行该 Run 时使用的调用上下文 */
    AgentInvocationContext provide(AgentRunSnapshot snapshot);

    /** 返回始终使用空调用上下文的默认实现。 */
    static AgentInvocationContextProvider empty() {
        return snapshot -> AgentInvocationContext.empty();
    }
}
