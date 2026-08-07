/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentStepResult;
import com.agentsflex.agent.tool.AgentToolResolver;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

/**
 * 可包装 Agent 步骤、模型调用和工具调用的运行时中间件。
 *
 * <p>Middleware 按 Agent 注册顺序形成责任链。实现通常应调用一次 {@code chain.proceed(context)}；
 * 不调用表示短路后续链路，多次调用可能导致模型或工具重复执行。Middleware 是 Agent 定义的一部分，
 * 被多个 Turn 复用时应自行保证线程安全。</p>
 */
public interface AgentMiddleware {

    /**
     * 返回该 Middleware 提供的动态工具解析器。
     *
     * <p>Agent 构建时会自动收集非空 Resolver，业务代码只需注册 Middleware。默认不提供动态工具。</p>
     */
    default AgentToolResolver getToolResolver() {
        return null;
    }

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
    default Object aroundToolCall(AgentMiddlewareContext context,
                                  AgentToolCallChain chain) {
        return chain.proceed(context);
    }
}
