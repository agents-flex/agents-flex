/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 挂起等待期限届满后，收到迟到恢复命令时采用的处理方式。
 */
public enum AgentSuspensionExpirationStrategy {
    /**
     * 拒绝本次命令并保持原等待状态，兼容旧行为。
     */
    REJECT_RESUME,
    /**
     * 收束未完成工具协议并将 Turn 标记为失败。
     */
    FAIL_TURN,
    /**
     * 收束未完成工具协议并将 Turn 标记为取消。
     */
    CANCEL_TURN
}
