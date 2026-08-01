/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.event;

import com.agentsflex.core.agent.AgentRun;

import java.util.Map;

/** 为持久化运行事件补充账号、模块、接口、租户等平台审计属性。 */
@FunctionalInterface
public interface AgentRunEventEnricher {

    /** 返回要合并到事件 attributes 的字符串属性。 */
    Map<String, String> enrich(AgentRun run, AgentRunEventType eventType);
}
