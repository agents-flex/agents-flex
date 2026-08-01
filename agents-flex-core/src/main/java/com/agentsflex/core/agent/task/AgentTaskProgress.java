/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.agent.AgentRunStatus;
import com.agentsflex.core.agent.AgentSuspension;

import java.util.List;

/** 面向调用方展示的任务列表、完成数量和当前运行阻塞信息。 */
public final class AgentTaskProgress {

    private final AgentTaskPlanSnapshot plan;
    private final AgentRunStatus activeRunStatus;
    private final AgentSuspension activeSuspension;

    public AgentTaskProgress(AgentTaskPlanSnapshot plan, AgentRunStatus activeRunStatus,
                             AgentSuspension activeSuspension) {
        this.plan = plan.copy();
        this.activeRunStatus = activeRunStatus;
        this.activeSuspension = activeSuspension;
    }

    public String getPlanId() { return plan.getPlanId(); }
    public String getRootRunId() { return plan.getRootRunId(); }
    public String getGoal() { return plan.getGoal(); }
    public AgentTaskPlanStatus getStatus() { return plan.getStatus(); }
    public AgentTask getCurrentTask() { return plan.getCurrentTask(); }
    public List<AgentTask> getTasks() { return plan.getTasks(); }
    public AgentRunStatus getActiveRunStatus() { return activeRunStatus; }
    public AgentSuspension getActiveSuspension() { return activeSuspension; }

    public int getTotalTaskCount() { return plan.getTasks().size(); }

    public int getCompletedTaskCount() {
        int count = 0;
        for (AgentTask task : plan.getTasks()) {
            if (task.getStatus() == AgentTaskStatus.COMPLETED) { count++; }
        }
        return count;
    }

    public int getFailedTaskCount() {
        int count = 0;
        for (AgentTask task : plan.getTasks()) {
            if (task.getStatus() == AgentTaskStatus.FAILED) { count++; }
        }
        return count;
    }
}
