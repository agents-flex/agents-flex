package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentStepResult;

/**
 * Step Middleware 责任链的继续执行入口。
 *
 * <p>链尾进入 Runner 内置的 ToolCall 状态机。Middleware 可以在调用前后执行横切逻辑，
 * 或直接返回 AgentStepResult 短路本次 step。</p>
 */
@FunctionalInterface
public interface AgentStepChain {
    /**
     * @param context 可由前置 Middleware 传递的当前执行上下文
     * @return 当前步骤的规范化执行结果
     */
    AgentStepResult proceed(AgentMiddlewareContext context);
}
