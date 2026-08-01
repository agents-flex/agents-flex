/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.mode;

import com.agentsflex.agent.AgentStepResult;

/** 使用模型原生 ToolCall 协议执行“模型决策、工具执行、结果回传”的默认模式。 */
public final class ToolCallingAgentExecutionMode implements AgentExecutionMode {

    public static final ToolCallingAgentExecutionMode INSTANCE =
        new ToolCallingAgentExecutionMode();

    private ToolCallingAgentExecutionMode() {
    }

    /** @return 默认模型原生 ToolCall 模式的稳定 ID */
    @Override
    public String getId() {
        return "tool-calling";
    }

    /** @return 默认模式状态协议版本 */
    @Override
    public String getVersion() {
        return "1";
    }

    /** 委托 Runner 按当前 MODEL 或 TOOLS 阶段推进一次。 */
    @Override
    public AgentStepResult step(AgentExecutionContext context) {
        return context.executeToolCallingStep();
    }
}
