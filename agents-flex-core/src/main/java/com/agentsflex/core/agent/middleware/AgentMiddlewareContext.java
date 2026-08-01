/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.middleware;

import com.agentsflex.core.agent.AgentInvocationContext;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.prompt.Prompt;

/** Middleware 访问当前 Run、调用上下文和模型 Prompt 的受控上下文。 */
public class AgentMiddlewareContext {
    private final AgentRunner runner;
    private final AgentRun run;
    private Prompt prompt;

    public AgentMiddlewareContext(AgentRunner runner, AgentRun run, Prompt prompt) {
        this.runner = runner;
        this.run = run;
        this.prompt = prompt;
    }

    public AgentRunner getRunner() { return runner; }
    public AgentRun getRun() { return run; }
    public AgentInvocationContext getInvocationContext() { return run.getInvocationContext(); }
    public Prompt getPrompt() { return prompt; }

    /** 替换本次模型调用使用的 Prompt，不修改 Checkpoint 中的消息历史。 */
    public void setPrompt(Prompt prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        this.prompt = prompt;
    }
}
