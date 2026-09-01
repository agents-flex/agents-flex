/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;

import java.util.Map;

/**
 * 在 AgentEvent 对外发布前清理或裁剪事件数据。
 */
@FunctionalInterface
public interface AgentEventDataSanitizer {
    /**
     * 在事件构造前清理、脱敏或裁剪事件数据。
     *
     * <p>输入 Map 是 Runner 新建的临时 Map，返回值会再次被 {@link com.agentsflex.agent.event.AgentEvent}
     * 深度复制并冻结。返回 {@code null} 表示不暴露任何事件数据；实现抛出异常时 Runner 会记录
     * 警告并回退到原始数据，避免观测逻辑阻断 Agent 主流程。</p>
     *
     * @param type 事件类型
     * @param data 尚未脱敏的事件数据
     * @return 对外暴露的数据，可为 {@code null}
     */
    Map<String, ?> sanitize(AgentEventType type, Map<String, ?> data);

    static AgentEventDataSanitizer identity() {
        return (type, data) -> data;
    }
}
