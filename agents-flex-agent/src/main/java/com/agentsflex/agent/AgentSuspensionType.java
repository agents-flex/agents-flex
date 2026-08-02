/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * AgentRun 暂停并等待外部事件的原因。
 */
public enum AgentSuspensionType {
    /**
     * 等待用户补充信息。
     */
    USER_INPUT,
    /**
     * 等待外部系统批准或拒绝工具调用。
     */
    TOOL_APPROVAL,
    /**
     * 等待关联的子 AgentRun 结束。
     */
    CHILD_AGENT,
    /**
     * 等待自动重试的调度时间到达。
     */
    RETRY
}
