/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.tool.BaseTool;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.toolsearch.memory.InMemoryToolSearchProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Model-facing tool that searches and progressively exposes application tools. */
public class ToolSearchTool extends BaseTool {

    public static final String NAME = "toolSearch";

    private static final String DEFAULT_DESCRIPTION =
        "Search the tool catalog to discover capabilities for completing the current task. "
            + "Use this when the tools currently available to you do not provide the functionality you need, "
            + "or when you are unsure which tool is appropriate. "
            + "The query is matched against tool names, descriptions, parameters, categories, and tags. "
            + "The result contains the names of matching tools. Their complete definitions will be made "
            + "available on the next model call, after which you should select and invoke the appropriate tool "
            + "using the standard tool-calling mechanism. This search discovers tools; it does not execute them.";

    private final ToolSearchManager manager;
    private final Map<String, Tool> discoveredTools = new LinkedHashMap<>();
    private Prompt prompt;
    private Map<String, Tool> alwaysVisibleTools = Collections.emptyMap();

    private ToolSearchTool(ToolSearchManager manager, String name, String description) {
        this.manager = manager;
        this.name = name;
        this.description = description;
        this.parameters = new Parameter[]{
            Parameter.builder().name("query").description("Natural-language description of the needed capability")
                .required(true).build(),
            Parameter.builder().name("maxResults").type("integer")
                .description("Maximum number of tools to return; defaults to 5").build(),
            Parameter.builder().name("category").description("Optional exact tool category filter").build()
        };
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Binds this tool to a prompt. Existing prompt tools remain always visible and are not
     * added to the searchable catalog.
     */
    public synchronized ToolSearchTool bind(Prompt prompt) {
        if (prompt == null) throw new IllegalArgumentException("Prompt must not be null");
        if (this.prompt != null && this.prompt != prompt) {
            throw new IllegalStateException("ToolSearchTool can only be bound to one Prompt");
        }
        if (this.prompt == null) {
            this.prompt = prompt;
            syncAlwaysVisibleTools();
        }
        refreshPrompt();
        return this;
    }

    /** Restores the prompt's always-visible tools and releases the binding. */
    public synchronized void unbind() {
        syncAlwaysVisibleTools();
        if (this.prompt != null) this.prompt.setTools(new ArrayList<>(this.alwaysVisibleTools.values()));
        this.prompt = null;
        this.alwaysVisibleTools = Collections.emptyMap();
        this.discoveredTools.clear();
    }

    public synchronized void reset() {
        syncAlwaysVisibleTools();
        discoveredTools.clear();
        refreshPrompt();
    }

    public synchronized List<Tool> getDiscoveredTools() {
        return Collections.unmodifiableList(new ArrayList<>(discoveredTools.values()));
    }

    public ToolSearchManager getManager() {
        return manager;
    }

    @Override
    public Object invoke(Map<String, Object> argsMap) {
        if (argsMap == null || argsMap.get("query") == null
            || argsMap.get("query").toString().trim().isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        ToolSearchRequest request = new ToolSearchRequest(argsMap.get("query").toString());
        Object maxResults = argsMap.get("maxResults");
        if (maxResults instanceof Number) request.setMaxResults(((Number) maxResults).intValue());
        else if (maxResults != null && !maxResults.toString().trim().isEmpty()) {
            request.setMaxResults(Integer.parseInt(maxResults.toString()));
        }
        Object category = argsMap.get("category");
        if (category != null) request.setCategory(category.toString());

        List<ToolSearchResult> results = searchAndActivate(request);
        List<String> names = new ArrayList<>(results.size());
        for (ToolSearchResult result : results) names.add(result.getToolInfo().getName());
        return names;
    }

    private synchronized List<ToolSearchResult> searchAndActivate(ToolSearchRequest request) {
        syncAlwaysVisibleTools();
        discoveredTools.clear();
        List<ToolSearchResult> activatedResults = new ArrayList<>();
        for (ToolSearchResult result : manager.search(request)) {
            if (result != null && result.getToolInfo() != null) {
                Tool tool = manager.resolve(result.getToolInfo().getName());
                if (tool != null) {
                    discoveredTools.put(tool.getName(), tool);
                    activatedResults.add(result);
                }
            }
        }
        refreshPrompt();
        return activatedResults;
    }

    /**
     * Reconciles tools managed directly by the developer with the current prompt. Search
     * tools are excluded by identity so they are not accidentally promoted to always-visible.
     */
    private void syncAlwaysVisibleTools() {
        if (this.prompt == null) return;
        Map<String, Tool> currentAlwaysVisible = new LinkedHashMap<>();
        if (this.prompt.getTools() != null) {
            for (Tool tool : this.prompt.getTools()) {
                if (tool == null || tool == this) continue;
                Tool discoveredTool = this.discoveredTools.get(tool.getName());
                if (tool != discoveredTool) currentAlwaysVisible.put(tool.getName(), tool);
            }
        }
        this.alwaysVisibleTools = currentAlwaysVisible;
    }

    private void refreshPrompt() {
        if (this.prompt == null) return;
        Map<String, Tool> visibleTools = new LinkedHashMap<>();
        visibleTools.putAll(alwaysVisibleTools);
        visibleTools.put(this.getName(), this);
        for (Tool tool : discoveredTools.values()) visibleTools.putIfAbsent(tool.getName(), tool);
        this.prompt.setTools(new ArrayList<>(visibleTools.values()));
    }

    public static class Builder {
        private final List<ToolRegistration> tools = new ArrayList<>();
        private ToolSearchProvider provider;
        private ToolSearchManager manager;
        private Prompt prompt;
        private String name = NAME;
        private String description = DEFAULT_DESCRIPTION;

        protected Builder() {
        }

        public Builder provider(ToolSearchProvider provider) {
            this.provider = provider;
            return this;
        }

        public Builder manager(ToolSearchManager manager) {
            this.manager = manager;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addTool(Tool tool) {
            this.tools.add(new ToolRegistration(tool, null));
            return this;
        }

        public Builder addTool(Tool tool, ToolInfo toolInfo) {
            this.tools.add(new ToolRegistration(tool, toolInfo));
            return this;
        }

        public Builder addTools(Collection<? extends Tool> tools) {
            if (tools != null) for (Tool tool : tools) addTool(tool);
            return this;
        }

        public Builder prompt(Prompt prompt) {
            if (this.prompt != null && this.prompt != prompt) {
                throw new IllegalStateException("Only one Prompt can be configured");
            }
            this.prompt = prompt;
            return this;
        }

        public ToolSearchTool build() {
            if (manager != null && provider != null) {
                throw new IllegalStateException("manager and provider cannot be configured together");
            }
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalStateException("tool search name must not be blank");
            }
            ToolSearchManager resolvedManager = manager != null ? manager
                : new ToolSearchManager(provider != null ? provider : new InMemoryToolSearchProvider());
            for (ToolRegistration registration : tools) {
                if (registration.info == null) resolvedManager.register(registration.tool);
                else resolvedManager.register(registration.tool, registration.info);
            }
            if (resolvedManager.isRegistered(name)) {
                throw new IllegalStateException("Tool name '" + name + "' is already registered");
            }
            ToolSearchTool searchTool = new ToolSearchTool(resolvedManager, name, description);
            if (prompt != null) searchTool.bind(prompt);
            return searchTool;
        }
    }

    private static class ToolRegistration {
        private final Tool tool;
        private final ToolInfo info;

        private ToolRegistration(Tool tool, ToolInfo info) {
            this.tool = tool;
            this.info = info;
        }
    }
}
