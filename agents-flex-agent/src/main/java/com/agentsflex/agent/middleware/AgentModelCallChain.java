package com.agentsflex.agent.middleware;

import com.agentsflex.core.model.chat.response.AiMessageResponse;

/**
 * 模型 Middleware 责任链的继续执行入口。
 *
 * <p>调用 {@link #proceed(AgentMiddlewareContext)} 会进入下一个 Middleware，链尾调用 ChatModel。
 * Middleware 可以先替换 context 中的 Prompt，也可以不调用该方法并直接返回完整响应。</p>
 */
@FunctionalInterface
public interface AgentModelCallChain {
    /**
     * @param context 当前模型调用上下文
     * @return 后续 Middleware 转换或模型实际返回的完整响应
     */
    AiMessageResponse proceed(AgentMiddlewareContext context);
}
