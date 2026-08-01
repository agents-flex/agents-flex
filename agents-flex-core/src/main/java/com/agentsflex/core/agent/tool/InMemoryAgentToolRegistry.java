/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.model.chat.tool.Tool;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 使用完整 AgentToolReference 作为键的进程内工具注册表。 */
public final class InMemoryAgentToolRegistry implements AgentToolRegistry {

    private final ConcurrentMap<AgentToolReference, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public AgentToolReference register(String agentId, String agentVersion, Tool tool) {
        if (agentId == null || agentVersion == null || tool == null || tool.getName() == null) {
            throw new IllegalArgumentException(
                "agentId, agentVersion, tool and tool name must not be null");
        }
        AgentToolReference reference = AgentToolReference.builder(
                agentId, agentVersion, tool.getName())
            .metadata(tool.getMetadata())
            .build();
        tools.put(reference, tool);
        return reference;
    }

    @Override
    public Tool resolve(AgentToolReference reference) {
        return reference == null ? null : tools.get(reference);
    }
}
