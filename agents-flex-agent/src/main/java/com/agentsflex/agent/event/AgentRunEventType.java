/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.event;

/** AgentRun 执行过程中可以持久化和增量消费的事件类型。 */
public enum AgentRunEventType {
    /** Run 首次开始执行。 */
    RUN_STARTED,
    /** 即将调用模型。 */
    MODEL_STARTED,
    /** 模型调用完成。 */
    MODEL_COMPLETED,
    /** 即将执行业务工具。 */
    TOOL_STARTED,
    /** 业务工具成功返回。 */
    TOOL_COMPLETED,
    /** 业务工具执行失败。 */
    TOOL_FAILED,
    /** Run Checkpoint 已持久化。 */
    CHECKPOINT_SAVED,
    /** Run 进入等待状态。 */
    RUN_SUSPENDED,
    /** Run 已应用外部恢复命令。 */
    RUN_RESUMED,
    /** 工具调用等待外部审批。 */
    TOOL_APPROVAL_REQUESTED,
    /** 已安排自动重试。 */
    RETRY_SCHEDULED,
    /** 运行预算已经耗尽。 */
    BUDGET_EXCEEDED,
    /** 父 Run 已创建子 Run。 */
    CHILD_STARTED,
    /** 已创建任务计划。 */
    PLAN_CREATED,
    /** 已调整任务计划。 */
    PLAN_UPDATED,
    /** 计划任务已经开始。 */
    TASK_STARTED,
    /** 计划任务成功完成。 */
    TASK_COMPLETED,
    /** 计划任务失败或取消。 */
    TASK_FAILED,
    /** Run 正常完成。 */
    RUN_COMPLETED,
    /** Run 因异常失败。 */
    RUN_FAILED,
    /** 已收到协作式取消请求。 */
    CANCELLATION_REQUESTED,
    /** Run 已完成取消。 */
    RUN_CANCELLED,
    /** 模型调用次数达到上限。 */
    MAX_ITERATIONS_REACHED,
    /** 执行模式 step 次数达到上限。 */
    MAX_STEPS_REACHED
}
