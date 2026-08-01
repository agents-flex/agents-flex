/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.middleware;

import com.agentsflex.core.agent.AgentStepResult;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

/** 可包装 Agent 步骤、模型调用和工具调用的运行时中间件。 */
public interface AgentMiddleware {

    /** 包装一次 Agent step，可在调用 chain 前后执行横切逻辑或直接返回结果。 */
    default AgentStepResult aroundStep(AgentMiddlewareContext context,
                                       AgentStepChain chain) {
        return chain.proceed(context);
    }

    /** 包装一次模型调用，可替换 Prompt、转换响应或直接短路模型请求。 */
    default AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                              AgentModelCallChain chain) {
        return chain.proceed(context);
    }

    /** 包装一次工具调用，可执行权限校验、缓存、限流和结果转换。 */
    default Object aroundToolCall(AgentToolCallContext context,
                                  AgentToolCallChain chain) {
        return chain.proceed(context);
    }
}
