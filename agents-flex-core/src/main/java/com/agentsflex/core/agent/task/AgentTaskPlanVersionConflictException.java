/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

/** 多个执行器并发更新同一任务计划时抛出的乐观锁异常。 */
public final class AgentTaskPlanVersionConflictException extends RuntimeException {

    public AgentTaskPlanVersionConflictException(String planId, long expected, long actual) {
        super("AgentTaskPlan version conflict, planId=" + planId
            + ", expected=" + expected + ", actual=" + actual);
    }
}
