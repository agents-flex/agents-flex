/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 事件数据脱敏器抛出异常后的安全降级方式。
 */
public enum AgentEventSanitizationFailureStrategy {
    /**
     * 继续发布事件，但不携带任何数据；默认采用该安全策略。
     */
    DROP_DATA,
    /**
     * 完全丢弃本次事件。
     */
    DROP_EVENT,
    /**
     * 发布未脱敏数据，仅适用于确认事件中不含敏感信息的兼容场景。
     */
    USE_ORIGINAL,
    /**
     * 将脱敏异常交给调用方并终止当前发布路径。
     */
    FAIL_EXECUTION
}
