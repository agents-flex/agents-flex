/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.observability.otel;

import io.opentelemetry.api.common.AttributeKey;

/**
 * 本模块自建的 OpenTelemetry 属性键，只覆盖 core 的
 * {@link com.agentsflex.core.observability.ObservabilityAttributeKeys} 未暴露、而编排层事件监听器
 * 需要的 Agent 维度属性。turn/bot/conversation/account 等关联属性一律复用 core 常量，不在此重复定义。
 */
public final class OtelAttributeNames {

    /** 产生事件的 Agent 稳定标识。 */
    public static final AttributeKey<String> AGENT_ID =
        AttributeKey.stringKey("agentsflex.agent.id");

    /** 产生事件时使用的 Agent 定义版本。 */
    public static final AttributeKey<String> AGENT_VERSION =
        AttributeKey.stringKey("agentsflex.agent.version");

    /** 事件在同一 Turn 内的递增序号，用于调试与排序。 */
    public static final AttributeKey<Long> EVENT_SEQUENCE =
        AttributeKey.longKey("agentsflex.event.sequence");

    /** 工具/Turn 终止结果维度，取值 {@code success} 或 {@code error}。 */
    public static final AttributeKey<String> OUTCOME =
        AttributeKey.stringKey("agentsflex.outcome");

    private OtelAttributeNames() {
    }
}
