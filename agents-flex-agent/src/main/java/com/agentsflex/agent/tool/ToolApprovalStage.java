/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

/**
 * 一次 ToolCall 的人工审批阶段。
 *
 * <p>两个阶段必须分别记录，不能共用一个“已批准”布尔值：中央策略批准只代表 ToolCall
 * 通过平台安全边界，并不代表 Tool 在只读预检后产生的具体业务请求也已经获批。</p>
 */
public enum ToolApprovalStage {
    /**
     * toolApprovalPolicy 产生的中央、静态审批。
     */
    POLICY,
    /**
     * 本地 Tool 完成只读预检后主动产生的审批。
     */
    TOOL
}
