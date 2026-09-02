/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.ToolCall;

/**
 * 判断一次模型或工具失败是否值得自动重试。
 *
 * <p>实现通常应按异常类型、工具名称和是否具备幂等性进行判断。该回调是进程内策略，
 * 不会进入 Snapshot；跨进程恢复时由同版本 Agent 重新绑定，若无法提供则使用默认决策器。</p>
 */
@FunctionalInterface
public interface AgentRetryDecider {

    /**
     * @param turn     当前 Turn
     * @param error    失败异常
     * @param toolCall 失败的工具调用；模型调用失败时为空
     * @return 是否安排自动重试
     */
    boolean shouldRetry(AgentTurn turn, Throwable error, ToolCall toolCall);

    /**
     * 默认只重试非参数、非缺失工具、非配额类错误。
     */
    static AgentRetryDecider defaults() {
        return (turn, error, call) -> {
            if (error instanceof com.agentsflex.core.model.exception.ModelQuotaExceededException
                || error instanceof com.agentsflex.core.model.exception.TokenLimitExceededException
                || error instanceof IllegalArgumentException
                || error instanceof AgentRunner.AgentToolNotFoundException) {
                return false;
            }
            return true;
        };
    }
}
