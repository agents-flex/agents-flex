/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.mode;

import com.agentsflex.agent.AgentRun;
import com.agentsflex.agent.AgentInvocationContext;
import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentStepResult;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.core.message.AiMessage;

/**
 * 运行模式推进 AgentRun 时可使用的受控操作集合。
 *
 * <p>模式通过该对象保存 Checkpoint、暂停、完成或失败任务，无需直接修改 AgentRun 内部状态。</p>
 */
public final class AgentExecutionContext {

    private final AgentRunner runner;
    private final AgentRun run;

    public AgentExecutionContext(AgentRunner runner, AgentRun run) {
        if (runner == null || run == null) {
            throw new IllegalArgumentException("runner and run must not be null");
        }
        this.runner = runner;
        this.run = run;
    }

    /** @return 当前执行模式所属 Runner */
    public AgentRunner getRunner() {
        return runner;
    }

    /** @return 当前被推进的 Run */
    public AgentRun getRun() {
        return run;
    }

    /** @return 当前 Run 附加的非持久化调用上下文 */
    public AgentInvocationContext getInvocationContext() {
        return run.getInvocationContext();
    }

    /** 使用框架内置的模型原生 ToolCall 循环推进一步。 */
    public AgentStepResult executeToolCallingStep() {
        return runner.executeToolCallingStep(run);
    }

    /** 保存当前 Run 的稳定状态并返回已分配版本的新快照。 */
    public AgentRunSnapshot checkpoint() {
        return runner.checkpoint(run);
    }

    /** 保存模式状态并返回可继续推进的标准步骤结果。 */
    public AgentStepResult checkpointAndContinue() {
        checkpoint();
        return AgentStepResult.progressed();
    }

    /** 暂停当前 Run、保存 Checkpoint，并返回规范的阻塞步骤结果。 */
    public AgentStepResult suspend(AgentSuspension suspension) {
        runner.suspend(run, suspension);
        return AgentStepResult.blocked();
    }

    /** 使用纯文本最终消息正常完成当前 Run。 */
    public AgentStepResult complete(String content) {
        return complete(new AiMessage(content));
    }

    /** 使用结构化 AI 消息正常完成当前 Run。 */
    public AgentStepResult complete(AiMessage message) {
        return runner.completeFromMode(run, message);
    }

    /** 按统一自动重试和失败策略处理模式异常。 */
    public AgentStepResult fail(Throwable error) {
        return runner.failFromMode(run, error);
    }
}
