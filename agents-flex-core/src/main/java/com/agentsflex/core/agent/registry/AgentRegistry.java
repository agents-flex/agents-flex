/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.registry;

import com.agentsflex.core.agent.Agent;

/**
 * 按稳定 ID 和定义版本管理 Agent 运行时对象。
 *
 * <p>恢复已有 Run 时使用精确版本解析；创建按逻辑 ID 分派的新任务时可以显式解析最新注册版本。</p>
 */
public interface AgentRegistry {

    /** 注册一个不可变 Agent 定义。 */
    void register(Agent agent);

    /** 根据稳定 ID 和定义版本精确解析 Agent。 */
    Agent resolve(String agentId, String version);

    /** 根据稳定 ID 解析最新注册的 Agent 版本。 */
    Agent resolveLatest(String agentId);
}
