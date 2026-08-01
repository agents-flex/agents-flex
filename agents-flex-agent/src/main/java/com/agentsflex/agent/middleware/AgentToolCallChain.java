package com.agentsflex.agent.middleware;

@FunctionalInterface
/** 继续执行下一个工具 Middleware，最终进入 ToolInterceptor 和 Tool。 */
public interface AgentToolCallChain {
    /** @return Middleware 转换或工具实际返回的结果对象 */
    Object proceed(AgentToolCallContext context);
}
