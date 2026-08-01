package com.agentsflex.core.agent.context;

import com.agentsflex.core.agent.AgentInvocationContext;
import com.agentsflex.core.agent.AgentRun;

/** 在模型调用前整理可持久化消息上下文。 */
@FunctionalInterface
public interface AgentContextManager {
    /**
     * 在模型调用前整理 Run 的持久化消息历史。
     *
     * @return 是否修改消息以及压缩前后的统计信息
     */
    AgentContextUpdate prepare(AgentRun run, AgentInvocationContext invocationContext);

    /** 返回不修改消息历史的默认实现。 */
    static AgentContextManager none() { return (run, context) -> AgentContextUpdate.unchanged(); }
}
