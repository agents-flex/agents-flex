/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.core.prompt.Prompt;

/**
 * Middleware 访问当前 Turn、Prompt 和当前阶段专属能力的统一上下文。
 *
 * <p>替换 prompt 只影响当前责任链调用，不会直接替换 AgentTurn 持有的持久化 Prompt；需要保存状态
 * 时应通过 Runner 或 Turn 提供的受控 API 完成。工具调用阶段还会携带
 * {@link AgentToolContext}；Step 和模型调用阶段该字段为空。</p>
 */
public class AgentMiddlewareContext {
    /**
     * 当前执行责任链的 Runner。
     */
    private final AgentRunner runner;
    /**
     * 当前被推进的 Turn。
     */
    private final AgentTurn turn;
    /**
     * 当前模型链实际使用的 Prompt，可由 Middleware 替换。
     */
    private Prompt prompt;
    /**
     * 当前工具调用的受控上下文；非工具 Middleware 阶段为空。
     */
    private final AgentToolContext toolContext;

    /**
     * 创建绑定 Runner、Turn 和当前 Prompt 的中间件上下文。
     */
    public AgentMiddlewareContext(AgentRunner runner, AgentTurn turn, Prompt prompt) {
        this(runner, turn, prompt, null);
    }

    /**
     * 创建工具 Middleware 使用的统一上下文。
     *
     * @param runner      当前 Runner
     * @param turn        当前 AgentTurn
     * @param toolContext 当前工具调用的受控上下文，工具调用阶段不能为 {@code null}
     * @return 携带非空工具上下文的 Middleware 上下文
     */
    public static AgentMiddlewareContext forToolCall(AgentRunner runner, AgentTurn turn,
                                                     AgentToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalArgumentException("toolContext must not be null");
        }
        return new AgentMiddlewareContext(runner, turn, turn.getPrompt(), toolContext);
    }

    /**
     * 创建统一中间件上下文；toolContext 仅在工具调用链中存在。
     *
     * @param runner      当前 Runner
     * @param turn        当前 Turn
     * @param prompt      当前模型请求 Prompt
     * @param toolContext 可选工具上下文
     */
    private AgentMiddlewareContext(AgentRunner runner, AgentTurn turn, Prompt prompt,
                                   AgentToolContext toolContext) {
        this.runner = runner;
        this.turn = turn;
        this.prompt = prompt;
        this.toolContext = toolContext;
    }

    /**
     * @return 当前 Runner
     */
    public AgentRunner getRunner() {
        return runner;
    }

    /**
     * @return 当前 AgentTurn
     */
    public AgentTurn getRun() {
        return turn;
    }

    /**
     * @return 当前责任链实际使用的 Prompt
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 返回当前工具调用的受控上下文。
     *
     * <p>{@link AgentMiddleware#aroundToolCall(AgentMiddlewareContext, AgentToolCallChain)} 中保证非空；
     * Step 和模型调用 Middleware 中返回 {@code null}。</p>
     *
     * @return 当前工具上下文，非工具调用阶段返回 {@code null}
     */
    public AgentToolContext getToolContext() {
        return toolContext;
    }

    /**
     * 替换本次模型调用使用的 Prompt，不修改 Snapshot 中的消息历史。
     */
    public void setPrompt(Prompt prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        this.prompt = prompt;
    }
}
