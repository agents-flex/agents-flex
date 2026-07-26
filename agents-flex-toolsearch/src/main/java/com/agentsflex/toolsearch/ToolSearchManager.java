/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.toolsearch.memory.InMemoryToolSearchProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps executable tools local while delegating metadata persistence and search. */
public class ToolSearchManager {
    private final ToolSearchProvider provider;
    private final Map<String, Tool> executableTools = new ConcurrentHashMap<>();

    public ToolSearchManager() { this(new InMemoryToolSearchProvider()); }
    public ToolSearchManager(ToolSearchProvider provider) {
        if (provider == null) throw new IllegalArgumentException("ToolSearchProvider must not be null");
        this.provider = provider;
    }

    public void register(Tool tool) {
        register(tool, ToolInfo.from(tool));
    }

    /** Registers a tool with enriched searchable metadata such as category and tags. */
    public void register(Tool tool, ToolInfo toolInfo) {
        if (tool == null || tool.getName() == null || tool.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tool and its name must not be blank");
        }
        if (toolInfo == null || !tool.getName().equals(toolInfo.getName())) {
            throw new IllegalArgumentException("ToolInfo name must match the executable tool name");
        }
        if (ToolSearchTool.NAME.equals(tool.getName())) {
            throw new IllegalArgumentException("Tool name '" + ToolSearchTool.NAME + "' is reserved");
        }
        executableTools.put(tool.getName(), tool);
        provider.save(toolInfo);
    }

    public void registerAll(Collection<? extends Tool> tools) {
        if (tools != null) for (Tool tool : tools) {
            if (tool != null && !(tool instanceof ToolSearchTool)) register(tool);
        }
    }

    public boolean isRegistered(String name) { return resolve(name) != null; }

    public Tool unregister(String name) { provider.remove(name); return executableTools.remove(name); }
    public Tool resolve(String name) { return name == null ? null : executableTools.get(name); }

    public List<Tool> resolve(List<ToolSearchResult> results) {
        if (results == null || results.isEmpty()) return Collections.emptyList();
        List<Tool> resolved = new ArrayList<>(results.size());
        for (ToolSearchResult result : results) {
            if (result != null && result.getToolInfo() != null) {
                Tool tool = resolve(result.getToolInfo().getName());
                if (tool != null) resolved.add(tool);
            }
        }
        return resolved;
    }

    public List<ToolSearchResult> search(ToolSearchRequest request) { return provider.search(request); }
    public ToolSearchProvider getProvider() { return provider; }
    public void clear() { executableTools.clear(); provider.clear(); }
}
