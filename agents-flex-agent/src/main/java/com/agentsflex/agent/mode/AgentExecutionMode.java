/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.mode;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentStepResult;

/**
 * Agent 运行模式扩展接口。
 *
 * <p>模式负责决定一次 step 如何推进。框架默认提供模型原生 ToolCall 闭环，平台也可以实现规划、反思、
 * 监督者或领域专用模式。模式 ID 和版本会写入 Checkpoint，用于恢复时校验运行逻辑没有漂移。</p>
 */
public interface AgentExecutionMode {

    /** 返回可持久化的稳定模式 ID。 */
    String getId();

    /** 返回模式实现版本。 */
    String getVersion();

    /** 在 Agent 构建时校验模式要求。 */
    default void validate(Agent agent) {
    }

    /** 推进当前 Run 一步。 */
    AgentStepResult step(AgentExecutionContext context);
}
