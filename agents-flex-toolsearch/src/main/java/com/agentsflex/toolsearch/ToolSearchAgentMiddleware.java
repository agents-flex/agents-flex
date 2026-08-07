/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.tool.AgentToolResolver;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将 {@link ToolSearchTool} 接入 AgentRunner 的渐进式工具披露 Middleware。
 *
 * <p>搜索 Tool 始终加入当前模型 Prompt；搜索命中的业务 Tool 名称保存到 AgentTurn metadata，
 * 下一次模型调用时才加入 Prompt。Resolver 只返回当前 Turn 已激活的工具，避免模型绕过搜索直接
 * 调用目录中的隐藏 Tool。Middleware 不绑定共享 Prompt，因此同一个 Agent 可以并发执行多个 Turn。</p>
 */
public final class ToolSearchAgentMiddleware implements AgentMiddleware {

    /**
     * Snapshot 中保存当前 Turn 最近一次搜索命中工具名称的稳定键。
     */
    public static final String ACTIVE_TOOL_NAMES_METADATA =
        "agentsflex.toolsearch.activeToolNames";

    private final ToolSearchTool searchTool;
    private final ToolSearchManager manager;
    private final AgentToolResolver toolResolver = this::resolveTool;

    public ToolSearchAgentMiddleware(ToolSearchTool searchTool) {
        if (searchTool == null) {
            throw new IllegalArgumentException("searchTool must not be null");
        }
        this.searchTool = searchTool;
        this.manager = searchTool.getManager();
    }

    /**
     * 创建使用指定 ToolSearchTool 的 Agent Middleware。
     */
    public static ToolSearchAgentMiddleware of(ToolSearchTool searchTool) {
        return new ToolSearchAgentMiddleware(searchTool);
    }

    /**
     * Agent 构建时会自动收集该 Resolver，无需再次配置 toolResolver。
     */
    @Override
    public AgentToolResolver getToolResolver() {
        return toolResolver;
    }

    /**
     * 在每次模型调用前应用当前 Turn 的最近一次搜索结果。
     */
    @Override
    public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                             AgentModelCallChain chain) {
        context.setPrompt(ToolSearchPromptResolver.resolve(
            context.getPrompt(), searchTool, activeToolNames(context.getRun())));
        return chain.proceed(context);
    }

    /**
     * 搜索完成后把命中名称保存到 Turn，随后 Runner 保存 ToolMessage 时会一并写入 Snapshot。
     */
    @Override
    public Object aroundToolCall(AgentMiddlewareContext context,
                                 AgentToolCallChain chain) {
        Object result = chain.proceed(context);
        Tool current = context.getToolContext() == null
            ? null : context.getToolContext().getTool();
        if (current == searchTool) {
            context.getRun().putMetadata(ACTIVE_TOOL_NAMES_METADATA,
                normalizedToolNames(result));
        }
        return result;
    }

    private Tool resolveTool(AgentTurn turn, String toolName) {
        if (searchTool.getName().equals(toolName)) return searchTool;
        if (!activeToolNames(turn).contains(toolName)) return null;
        return manager.resolve(toolName);
    }

    private List<String> normalizedToolNames(Object value) {
        if (!(value instanceof Iterable)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            if (item == null) continue;
            String name = String.valueOf(item);
            if (manager.resolve(name) != null && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private List<String> activeToolNames(AgentTurn turn) {
        Object value = turn.getMetadata().get(ACTIVE_TOOL_NAMES_METADATA);
        if (!(value instanceof Iterable)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (Object item : (Iterable<?>) value) {
            if (item != null) names.add(String.valueOf(item));
        }
        return names;
    }
}
