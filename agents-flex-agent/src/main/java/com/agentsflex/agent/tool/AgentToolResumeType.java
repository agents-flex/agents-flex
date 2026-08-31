/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import java.io.Serializable;

/**
 * 表示本次工具执行前最近一次恢复的来源。
 */
public enum AgentToolResumeType implements Serializable {
    NONE,
    FORM_INPUT,
    APPROVAL,
    RETRY
}
