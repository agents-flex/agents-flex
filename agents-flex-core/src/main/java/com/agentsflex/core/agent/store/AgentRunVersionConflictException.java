/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.store;

/**
 * 保存 AgentRun Checkpoint 时发生乐观锁冲突。
 */
public class AgentRunVersionConflictException extends RuntimeException {

    public AgentRunVersionConflictException(String runId, long expectedVersion, long actualVersion) {
        super("AgentRun version conflict, runId=" + runId
            + ", expectedVersion=" + expectedVersion
            + ", actualVersion=" + actualVersion);
    }
}
