/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.registry;

import com.agentsflex.core.agent.Agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内 AgentRegistry 默认实现。 */
public final class InMemoryAgentRegistry implements AgentRegistry {

    private final ConcurrentMap<String, Agent> latestAgents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Agent> versionedAgents = new ConcurrentHashMap<>();

    @Override
    public void register(Agent agent) {
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        latestAgents.put(agent.getId(), agent);
        versionedAgents.put(key(agent.getId(), agent.getVersion()), agent);
    }

    @Override
    public Agent resolveLatest(String agentId) {
        return agentId == null ? null : latestAgents.get(agentId);
    }

    @Override
    public Agent resolve(String agentId, String version) {
        if (agentId == null || version == null) {
            return null;
        }
        return versionedAgents.get(key(agentId, version));
    }

    private String key(String agentId, String version) {
        return agentId + "\u0000" + version;
    }
}
