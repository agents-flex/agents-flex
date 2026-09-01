/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 恢复暂停 AgentTurn 的外部命令类型。
 *
 * <p>命令类型必须与当前 AgentSuspensionType 匹配，并在需要时携带相同 correlationId，
 * Runner 才会应用命令并继续推进；不匹配的命令会被拒绝。</p>
 */
public enum AgentResumeCommandType {
    /**
     * 不补充数据，直接从暂停阶段继续。
     */
    CONTINUE,
    /**
     * 提交新的文本或结构化用户输入；控制 ToolCall 场景会生成匹配的 ToolMessage。
     */
    USER_INPUT,
    /**
     * 允许执行关联的工具调用。
     */
    APPROVE_TOOL,
    /**
     * 拒绝执行关联的工具调用。
     */
    REJECT_TOOL,
    /**
     * 提交外部执行器成功完成后的工具结果。
     */
    TOOL_RESULT,
    /**
     * 提交外部执行器返回的结构化工具错误。
     */
    TOOL_ERROR,
    /**
     * 执行已经到达调度时间的自动重试。
     */
    RETRY
}
