/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.exception;

/**
 * 保存 AgentTurn Snapshot 时发生乐观锁冲突。
 */
public class AgentTurnVersionConflictException extends RuntimeException {

    /**
     * 创建包含冲突 Turn 和双方版本的异常。
     *
     * @param turnId          冲突 Turn ID
     * @param expectedVersion 调用方预期版本
     * @param actualVersion   Store 当前版本
     */
    public AgentTurnVersionConflictException(String turnId, long expectedVersion, long actualVersion) {
        super("AgentTurn version conflict, turnId=" + turnId
            + ", expectedVersion=" + expectedVersion
            + ", actualVersion=" + actualVersion);
    }
}
