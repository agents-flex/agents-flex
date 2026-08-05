/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.loader;

import com.agentsflex.agent.Agent;

/**
 * 根据稳定标识加载当前进程可执行的 Agent。
 *
 * <p>实现可以从一张或多张业务表、配置中心或其他数据源读取配置，并完成 ChatModel、Tool、
 * Middleware 等运行时对象的组装。框架不限定 Agent 配置的存储结构。</p>
 */
public interface AgentLoader {

    /**
     * 加载指定版本的 Agent。
     *
     * <p>恢复已保存的 Turn 时调用。实现应保证同一 agentId 和 version 能恢复兼容的执行定义。</p>
     */
    Agent load(String agentId, String version);

    /**
     * 加载当前生效的 Agent。
     *
     * <p>创建按 Agent ID 分派的新任务或子任务时调用，具体生效规则由业务实现决定。</p>
     */
    Agent loadActive(String agentId);
}
