package com.agentsflex.agent.middleware;

/**
 * 工具 Middleware 责任链的继续执行入口。
 *
 * <p>调用 {@link #proceed(AgentToolCallContext)} 会进入下一个 Middleware，链尾依次执行
 * ToolInterceptor 和实际 Tool。Middleware 通常只应调用一次；不调用表示短路工具执行。</p>
 */
@FunctionalInterface
public interface AgentToolCallChain {
    /**
     * @param context 当前工具调用上下文，可传递包装后的上下文
     * @return 后续 Middleware 转换或工具实际返回的结果对象
     */
    Object proceed(AgentToolCallContext context);
}
