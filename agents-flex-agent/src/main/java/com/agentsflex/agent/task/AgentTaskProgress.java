/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.AgentSuspension;

import java.util.List;

/**
 * 面向调用方展示的任务列表、完成数量和当前子 Turn 状态。
 *
 * <p>该对象把父 Turn 中的计划状态和当前子 Turn 的实际阻塞状态组合为一次只读查询结果，适合任务
 * 列表、进度条和人工审批界面。构造时复制计划，后续 Snapshot 更新不会修改已有查询结果。</p>
 */
public final class AgentTaskProgress {
    /**
     * 查询时刻的计划副本。
     */
    private final AgentTaskPlan plan;
    /**
     * 当前活动子 Turn 的状态；没有活动子 Turn 时为父 Turn 状态。
     */
    private final AgentTurnStatus activeTurnStatus;
    /**
     * 当前活动 Turn 的暂停原因；未阻塞时为 {@code null}。
     */
    private final AgentSuspension activeSuspension;

    /**
     * 创建计划查询视图，并复制计划和挂起信息以隔离运行态修改。
     *
     * @param plan             当前任务计划
     * @param activeTurnStatus 当前实际执行的父或子 Turn 状态
     * @param activeSuspension 当前等待信息；没有等待时为空
     */
    public AgentTaskProgress(AgentTaskPlan plan, AgentTurnStatus activeTurnStatus,
                             AgentSuspension activeSuspension) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        this.plan = plan.copy();
        this.activeTurnStatus = activeTurnStatus;
        this.activeSuspension = activeSuspension;
    }

    /**
     * @return 计划稳定 ID
     */
    public String getPlanId() {
        return plan.getId();
    }

    /**
     * @return 计划需要完成的总体目标
     */
    public String getGoal() {
        return plan.getGoal();
    }

    /**
     * @return 计划生命周期状态
     */
    public AgentTaskPlanStatus getStatus() {
        return plan.getStatus();
    }

    /**
     * @return 当前正在执行的任务；没有活动任务时为 {@code null}
     */
    public AgentTask getCurrentTask() {
        return plan.getActiveTask();
    }

    /**
     * @return 按执行顺序排列的任务副本
     */
    public List<AgentTask> getTasks() {
        return plan.getTasks();
    }

    /**
     * @return 当前需要关注的父 Turn 或子 Turn 状态
     */
    public AgentTurnStatus getActiveTurnStatus() {
        return activeTurnStatus;
    }

    /**
     * @return 当前审批、输入、子任务或重试等待信息
     */
    public AgentSuspension getActiveSuspension() {
        return activeSuspension;
    }

    /**
     * @return 计划中的任务总数
     */
    public int getTotalTaskCount() {
        return plan.getTasks().size();
    }

    /**
     * @return 已成功完成的任务数量
     */
    public int getCompletedTaskCount() {
        return count(AgentTaskStatus.COMPLETED);
    }

    /**
     * @return 执行失败的任务数量
     */
    public int getFailedTaskCount() {
        return count(AgentTaskStatus.FAILED);
    }

    /**
     * 统计计划中处于指定状态的任务数。
     *
     * @param status 目标任务状态
     * @return 匹配任务数量
     */
    private int count(AgentTaskStatus status) {
        int value = 0;
        for (AgentTask task : plan.getTasks()) if (task.getStatus() == status) value++;
        return value;
    }
}
