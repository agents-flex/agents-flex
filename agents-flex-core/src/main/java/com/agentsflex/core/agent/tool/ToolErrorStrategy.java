/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.agent.AgentRun;

/**
 * 工具调用失败后的处理策略。
 */
public enum ToolErrorStrategy {

    /**
     * 立即结束本次 AgentRun，并把异常记录到 {@link AgentRun#getError()}。
     * 适用于工具错误不可恢复，或业务不允许模型自行重试的场景。
     */
    FAIL_RUN,

    /**
     * 把错误编码为带原 ToolCall ID 的 {@link com.agentsflex.core.message.ToolMessage}，
     * 再交给模型判断是否修改参数、选择其他工具或向用户解释失败原因。
     */
    RETURN_ERROR_TO_MODEL
}
