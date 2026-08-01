/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.agent.AgentRun;

/** 一次计划执行返回的计划状态、根 Run 和当前活动 Run。 */
public final class AgentPlanRun {

    private final AgentTaskPlanSnapshot plan;
    private final AgentRun rootRun;
    private final AgentRun activeRun;

    public AgentPlanRun(AgentTaskPlanSnapshot plan, AgentRun rootRun, AgentRun activeRun) {
        this.plan = plan.copy();
        this.rootRun = rootRun;
        this.activeRun = activeRun;
    }

    public AgentTaskPlanSnapshot getPlan() { return plan.copy(); }
    public AgentRun getRootRun() { return rootRun; }
    public AgentRun getActiveRun() { return activeRun; }
    public boolean isBlocked() { return plan.getStatus().isBlocked(); }
    public boolean isTerminal() { return plan.getStatus().isTerminal(); }
}
