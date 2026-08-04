/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

/**
 * Worker 恢复 Run 时根据 Snapshot 重新构造非持久化调用上下文。
 *
 * <p>调用上下文用于传递租户、请求来源、流式开关等进程内信息，不写入 Snapshot。
 * Provider 因此应根据当前 Worker 环境重新生成这些信息，不能依赖上一次执行线程的对象。</p>
 */
@FunctionalInterface
public interface AgentInvocationContextProvider {

    /**
     * @param snapshot Worker 即将恢复的持久化 Run 状态
     * @return 当前 Worker 执行该 Run 时使用的非空调用上下文
     */
    AgentInvocationContext provide(AgentRunSnapshot snapshot);

    /** 返回始终使用空调用上下文的默认实现。 */
    static AgentInvocationContextProvider empty() {
        return snapshot -> AgentInvocationContext.empty();
    }
}
