/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

/**
 * 计划中单个任务的执行状态。
 *
 * <p>任务通常通过子 AgentTurn 执行。依赖关系尚未满足时保持 PENDING，依赖满足后进入 READY；
 * 子 Turn 的状态变化再映射为 RUNNING、WAITING 或终止状态。</p>
 */
public enum AgentTaskStatus {
    /** 任务尚未满足依赖条件，不能开始执行。 */
    PENDING,
    /** 依赖已经满足，可以创建或领取对应子 Turn。 */
    READY,
    /** 对应子 Turn 正在执行。 */
    RUNNING,
    /** 对应子 Turn 暂停，正在等待输入、审批、子任务或重试时间。 */
    WAITING,
    /** 对应子 Turn 正常完成，任务结果可供后续任务使用。 */
    COMPLETED,
    /** 对应子 Turn 失败，任务没有产生可用结果。 */
    FAILED,
    /** 因计划调整或依赖分支不再需要而未执行。 */
    SKIPPED,
    /** 父计划或对应子 Turn 被取消。 */
    CANCELLED;

    /** @return 当前任务是否已经结束 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
