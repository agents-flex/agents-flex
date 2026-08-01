/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.mode;

import com.agentsflex.core.agent.AgentStepResult;

/** 使用模型原生 ToolCall 协议执行“模型决策、工具执行、结果回传”的默认模式。 */
public final class ToolCallingAgentExecutionMode implements AgentExecutionMode {

    public static final ToolCallingAgentExecutionMode INSTANCE =
        new ToolCallingAgentExecutionMode();

    private ToolCallingAgentExecutionMode() {
    }

    @Override
    public String getId() {
        return "tool-calling";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public AgentStepResult step(AgentExecutionContext context) {
        return context.executeToolCallingStep();
    }
}
