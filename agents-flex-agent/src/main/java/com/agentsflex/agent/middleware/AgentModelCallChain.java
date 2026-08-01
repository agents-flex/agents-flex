package com.agentsflex.agent.middleware;

import com.agentsflex.core.model.chat.response.AiMessageResponse;

@FunctionalInterface
/** 继续执行下一个模型 Middleware，最终调用 ChatModel。 */
public interface AgentModelCallChain {
    /** @return Middleware 转换或模型实际返回的完整响应 */
    AiMessageResponse proceed(AgentMiddlewareContext context);
}
