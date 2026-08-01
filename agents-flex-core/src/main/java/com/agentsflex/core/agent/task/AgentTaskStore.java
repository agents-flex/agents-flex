/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

/** 保存任务计划及其执行进度的持久化接口。 */
public interface AgentTaskStore {

    AgentTaskPlanSnapshot load(String planId);

    AgentTaskPlanSnapshot loadByRootRunId(String rootRunId);

    /** 使用乐观锁保存计划，新建计划时 expectedVersion 为 {@code -1}。 */
    AgentTaskPlanSnapshot save(AgentTaskPlanSnapshot snapshot, long expectedVersion);
}
