/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 导致 AgentTurn 等待模型恢复的故障类别。
 *
 * <p>该分类用于控制面展示、告警和恢复决策，不替代底层模型异常。原异常类型、错误码和消息仍会
 * 保存在 {@link AgentModelFailure} 中。</p>
 */
public enum AgentModelFailureType {
    /**
     * 账户、项目或组织额度已经耗尽。
     */
    QUOTA_EXCEEDED,
    /**
     * 模型服务触发请求频率或 Token 速率限制。
     */
    RATE_LIMITED,
    /**
     * 输入、输出或总上下文超过模型 Token 限制。
     */
    TOKEN_LIMIT_EXCEEDED,
    /**
     * 模型服务当前过载。
     */
    OVERLOADED,
    /**
     * 已归一化为模型异常，但不属于以上已知类别。
     */
    UNAVAILABLE
}
