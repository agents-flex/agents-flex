/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

/**
 * 整个任务计划的生命周期状态。
 *
 * <p>该状态描述计划级推进位置，不等同于父 AgentTurn 的状态；例如计划处于 WAITING 时，
 * 父 Turn 通常处于 WAITING_FOR_CHILD。</p>
 */
public enum AgentTaskPlanStatus {
    /** 计划已经创建，但尚未选择第一个可执行任务。 */
    READY,
    /** Runner 正在选择、启动或汇总计划任务。 */
    RUNNING,
    /** 已启动的任务正在由关联子 Turn 执行，计划等待其结束。 */
    WAITING,
    /** 子任务失败后等待模型调整尚未执行的任务。 */
    REPLANNING,
    /** 所有必要任务已经结束，正在请求模型生成最终回答。 */
    FINALIZING,
    /** 计划及其最终回答均已正常完成。 */
    COMPLETED,
    /** 计划无法继续推进且没有得到完整结果。 */
    FAILED,
    /** 父 Turn 取消后，计划已停止推进。 */
    CANCELLED;

    /** @return 当前计划是否已经结束 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** @return 当前计划是否正在等待关联 AgentTurn 的外部恢复事件 */
    public boolean isBlocked() {
        return this == WAITING;
    }
}
