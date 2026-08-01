/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.agent.Agent;

/** 将复杂目标转换为有序任务计划的策略接口。 */
public interface AgentTaskPlanner {

    /** 根据 Agent 能力和当前目标创建任务计划。 */
    AgentTaskPlan createPlan(Agent agent, AgentPlanningContext context);

    /**
     * 根据当前执行结果调整未完成任务。默认保持原计划不变。
     */
    default AgentTaskPlan revisePlan(Agent agent, AgentPlanningContext context,
                                     AgentTaskPlanSnapshot currentPlan) {
        return new AgentTaskPlan(currentPlan.getGoal(), currentPlan.getTasks());
    }
}
