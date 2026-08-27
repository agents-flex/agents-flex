/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.loader;

import com.agentsflex.agent.Agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从构造参数提供的不可变 Agent 集合中加载对象。
 *
 * <p>该实现适合测试、Demo 和单进程静态配置。相同 Agent ID 出现多次时，最后一个版本作为当前
 * 生效版本。构造完成后不支持注册或修改。</p>
 */
public final class InMemoryAgentLoader implements AgentLoader {

    /**
     * 按 Agent ID 索引的当前生效版本；同一 ID 以后传入的 Agent 覆盖先前值。
     */
    private final Map<String, Agent> activeAgents;
    /**
     * 按 Agent ID 与版本联合索引的全部构造参数，用于精确恢复指定版本。
     */
    private final Map<String, Agent> versionedAgents;

    /**
     * 使用给定 Agent 集合创建只读 Loader。
     *
     * @param agents 可供加载的 Agent；数组可以为空，但元素不能为 {@code null}
     */
    public InMemoryAgentLoader(Agent... agents) {
        Map<String, Agent> active = new LinkedHashMap<>();
        Map<String, Agent> versioned = new LinkedHashMap<>();
        if (agents != null) {
            for (Agent agent : agents) {
                if (agent == null) {
                    throw new IllegalArgumentException("agents must not contain null");
                }
                active.put(agent.getId(), agent);
                versioned.put(key(agent.getId(), agent.getVersion()), agent);
            }
        }
        this.activeAgents = Collections.unmodifiableMap(active);
        this.versionedAgents = Collections.unmodifiableMap(versioned);
    }

    /**
     * 按稳定 ID 和配置版本读取 Agent；不存在时返回 {@code null}。
     */
    @Override
    public Agent load(String agentId, String version) {
        if (agentId == null || version == null) {
            return null;
        }
        return versionedAgents.get(key(agentId, version));
    }

    /**
     * 按稳定 ID 读取构造时最后出现的生效版本。
     */
    @Override
    public Agent loadActive(String agentId) {
        return agentId == null ? null : activeAgents.get(agentId);
    }

    /**
     * 使用不可出现在普通标识中的分隔符生成 ID 与版本联合索引键。
     */
    private static String key(String agentId, String version) {
        return agentId + "\u0000" + version;
    }
}
