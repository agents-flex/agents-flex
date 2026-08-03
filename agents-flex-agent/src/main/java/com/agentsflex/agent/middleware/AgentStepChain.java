package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentStepResult;

@FunctionalInterface
/** 继续执行下一个步骤 Middleware，最终进入内置 ToolCall 状态机。 */
public interface AgentStepChain {
    /**
     * @param context 可由前置 Middleware 传递的当前执行上下文
     * @return 当前步骤的规范化执行结果
     */
    AgentStepResult proceed(AgentMiddlewareContext context);
}
