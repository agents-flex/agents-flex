/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.middleware;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.core.prompt.Prompt;

/**
 * Middleware 访问当前 Turn 和模型 Prompt 的受控上下文。
 *
 * <p>替换 prompt 只影响当前责任链调用，不会直接替换 AgentTurn 持有的持久化 Prompt；需要保存状态
 * 时应通过 Runner 或 Turn 提供的受控 API 完成。</p>
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
     * 创建绑定 Runner、Turn 和当前 Prompt 的中间件上下文。
     */
    public AgentMiddlewareContext(AgentRunner runner, AgentTurn turn, Prompt prompt) {
        this.runner = runner;
        this.turn = turn;
        this.prompt = prompt;
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
     * 替换本次模型调用使用的 Prompt，不修改 Snapshot 中的消息历史。
     */
    public void setPrompt(Prompt prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        this.prompt = prompt;
    }
}
