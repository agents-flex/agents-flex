/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

/** 整个任务计划的生命周期状态。 */
public enum AgentTaskPlanStatus {
    READY,
    RUNNING,
    WAITING,
    /** 子任务失败后等待模型调整尚未执行的任务。 */
    REPLANNING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** @return 当前计划是否已经结束 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** @return 当前计划是否正在等待关联 AgentRun 的外部恢复事件 */
    public boolean isBlocked() {
        return this == WAITING;
    }
}
