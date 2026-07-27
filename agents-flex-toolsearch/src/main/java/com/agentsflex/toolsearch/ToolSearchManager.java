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

/**
 * Tool 搜索目录的管理器，负责连接“可执行 Tool”和“可检索元数据”。
 *
 * <p>可执行 {@link Tool} 保存在当前进程中，{@link ToolInfo} 的保存与搜索则委托给
 * {@link ToolSearchProvider}。这种分离使 Provider 可以接入数据库、Lucene 或
 * Elasticsearch，而不需要序列化 Tool 的执行回调。</p>
 *
 * <p>名称是两部分数据的关联键。Provider 返回搜索结果后，调用方仍需通过本管理器
 * 解析到同名的本地 Tool；无法解析的结果不会暴露给模型。</p>
 */
public class ToolSearchManager {
    /**
     * 负责 Tool 元数据持久化和检索的 Provider。
     */
    private final ToolSearchProvider provider;
    /**
     * 当前进程内的可执行 Tool，以 Tool 名称为键。
     */
    private final Map<String, Tool> executableTools = new ConcurrentHashMap<>();

    /**
     * 使用默认内存 Provider 创建独立的工具目录。
     */
    public ToolSearchManager() {
        this(new InMemoryToolSearchProvider());
    }

    /**
     * 使用指定 Provider 创建工具目录。
     *
     * @param provider Tool 元数据的存储与搜索实现
     * @throws IllegalArgumentException 当 provider 为 {@code null} 时抛出
     */
    public ToolSearchManager(ToolSearchProvider provider) {
        if (provider == null) throw new IllegalArgumentException("ToolSearchProvider must not be null");
        this.provider = provider;
    }

    /**
     * 注册可执行 Tool，并自动从 Tool 定义生成基础检索元数据。
     *
     * @param tool 要加入搜索目录的 Tool
     */
    public void register(Tool tool) {
        register(tool, ToolInfo.from(tool));
    }

    /**
     * 使用补充后的元数据注册可执行 Tool。
     *
     * <p>同名注册会同时覆盖本地可执行 Tool 和 Provider 中的元数据。元数据名称必须
     * 与 Tool 名称完全一致，避免搜索结果解析到错误的执行函数。</p>
     *
     * @param tool     本地可执行 Tool
     * @param toolInfo 对应的检索元数据，可包含分类、标签和扩展属性
     * @throws IllegalArgumentException 当 Tool 或名称为空、元数据名称不匹配，或者使用
     *                                  保留名称 {@code toolSearch} 时抛出
     */
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

    /**
     * 批量注册 Tool。集合中的 {@code null} 和 {@link ToolSearchTool} 会被忽略。
     *
     * @param tools 要注册的 Tool 集合；可以为 {@code null}
     */
    public void registerAll(Collection<? extends Tool> tools) {
        if (tools != null) for (Tool tool : tools) {
            if (tool != null && !(tool instanceof ToolSearchTool)) register(tool);
        }
    }

    /**
     * @param name Tool 名称
     * @return 当前是否存在该名称的本地可执行 Tool
     */
    public boolean isRegistered(String name) {
        return resolve(name) != null;
    }

    /**
     * 同时删除 Provider 中的元数据和本地可执行 Tool。
     *
     * @param name Tool 名称
     * @return 被删除的可执行 Tool；不存在时返回 {@code null}
     */
    public Tool unregister(String name) {
        provider.remove(name);
        return executableTools.remove(name);
    }

    /**
     * @param name Tool 名称
     * @return 同名的本地可执行 Tool；未注册时返回 {@code null}
     */
    public Tool resolve(String name) {
        return name == null ? null : executableTools.get(name);
    }

    /**
     * 将搜索结果解析为本地可执行 Tool。
     *
     * <p>空结果、无元数据结果以及 Provider 中存在但本地未注册的结果都会被忽略，
     * 返回顺序与可解析搜索结果的原顺序一致。</p>
     *
     * @param results Provider 返回的搜索结果
     * @return 可执行 Tool 列表；无可解析结果时返回空列表
     */
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

    /**
     * 委托 Provider 搜索 Tool 元数据。
     *
     * @param request 搜索条件
     * @return Provider 返回的搜索结果
     */
    public List<ToolSearchResult> search(ToolSearchRequest request) {
        return provider.search(request);
    }

    /**
     * @return 当前 Manager 使用的 Provider
     */
    public ToolSearchProvider getProvider() {
        return provider;
    }

    /**
     * 清空本地可执行 Tool 和 Provider 中的全部元数据。
     */
    public void clear() {
        executableTools.clear();
        provider.clear();
    }
}
