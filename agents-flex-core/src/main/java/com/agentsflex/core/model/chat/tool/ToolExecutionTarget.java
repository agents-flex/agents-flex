/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.model.chat.tool;

/**
 * ToolCall 的实际执行位置。
 */
public enum ToolExecutionTarget {
    /**
     * 由当前 AgentRunner 进程调用 {@link Tool#invoke(java.util.Map)} 执行。
     */
    LOCAL,
    /**
     * 由浏览器、移动端或其他外部执行器完成，再把结果回传给 AgentRunner。
     */
    EXTERNAL
}
