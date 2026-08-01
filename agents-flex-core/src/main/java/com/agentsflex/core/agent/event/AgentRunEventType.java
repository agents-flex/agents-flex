/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.event;

/** AgentRun 执行过程中可以持久化和增量消费的事件类型。 */
public enum AgentRunEventType {
    RUN_STARTED,
    MODEL_STARTED,
    MODEL_COMPLETED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED,
    CHECKPOINT_SAVED,
    RUN_SUSPENDED,
    RUN_RESUMED,
    TOOL_APPROVAL_REQUESTED,
    RETRY_SCHEDULED,
    BUDGET_EXCEEDED,
    CHILD_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    CANCELLATION_REQUESTED,
    RUN_CANCELLED,
    MAX_ITERATIONS_REACHED,
    MAX_STEPS_REACHED
}
