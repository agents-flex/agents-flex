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

/**
 * 面向大模型的 Tool 搜索入口，用于按需发现并渐进式暴露业务 Tool。
 *
 * <p>通过 Builder 注册的业务 Tool 不会在初始请求中全部发送给模型。模型先调用本 Tool
 * 描述所需能力，搜索命中的可执行 Tool 才会被加入绑定的 {@link Prompt}，供下一轮模型
 * 调用。这样可以降低几十到几百个 Tool 同时占用上下文所产生的 Token 成本和选择干扰。</p>
 *
 * <p>一个实例同一时间只绑定一个 Prompt。Prompt 中由开发者直接维护的 Tool 被视为
 * “常驻 Tool”，始终可见且不会自动进入搜索目录；只有通过 {@link Builder#addTool(Tool)}
 * 或 {@link Builder#addTools(Collection)} 注册的 Tool 才可搜索。</p>
 *
 * <p>动态发现结果采用替换语义：每次搜索前都会清空上一次结果，Prompt 中仅保留最近
 * 一次命中的 Tool。搜索无结果时，之前发现的 Tool 也会被移除。</p>
 */
public class ToolSearchTool extends BaseTool {

    /**
     * 默认 Tool 名称，同时是 Manager 禁止业务 Tool 占用的保留名称。
     */
    public static final String NAME = "toolSearch";

    /**
     * 面向模型的默认说明，明确搜索时机、检索范围、返回内容和“只发现不执行”的边界。
     */
    private static final String DEFAULT_DESCRIPTION =
        "Search the tool catalog to discover capabilities for completing the current task. "
            + "Use this when the tools currently available to you do not provide the functionality you need, "
            + "or when you are unsure which tool is appropriate. "
            + "The query is matched against tool names, descriptions, parameters, categories, and tags. "
            + "The result contains the names of matching tools. Their complete definitions will be made "
            + "available on the next model call, after which you should select and invoke the appropriate tool "
            + "using the standard tool-calling mechanism. This search discovers tools; it does not execute them.";

    /**
     * 保存可搜索元数据并关联本地可执行 Tool 的管理器。
     */
    private final ToolSearchManager manager;
    /**
     * 最近一次搜索命中的可执行 Tool，以名称去重并保持 Provider 返回顺序。
     */
    private final Map<String, Tool> discoveredTools = new LinkedHashMap<>();
    /**
     * 当前绑定的唯一 Prompt；未绑定时为 {@code null}。
     */
    private Prompt prompt;
    /**
     * 开发者直接维护在 Prompt 中、需要始终发送给模型的 Tool。
     */
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

    /**
     * 创建 ToolSearchTool Builder。
     *
     * @return 新的 Builder，每个 Builder 默认创建独立的 Manager 和内存 Provider
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 将当前 ToolSearchTool 绑定到一个 Prompt。
     *
     * <p>绑定前 Prompt 中已有的 Tool 会保留为常驻 Tool，不会注册到搜索目录。绑定完成后，
     * Prompt 会包含原有常驻 Tool 和当前 ToolSearchTool。重复绑定同一个 Prompt 是幂等的；
     * 如需改绑另一个 Prompt，必须先调用 {@link #unbind()}。</p>
     *
     * @param prompt 需要渐进式挂载搜索结果的 Prompt
     * @return 当前 ToolSearchTool，便于链式调用
     * @throws IllegalArgumentException 当 prompt 为 {@code null} 时抛出
     * @throws IllegalStateException    当当前实例已绑定另一个 Prompt 时抛出
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

    /**
     * 解除 Prompt 绑定。
     *
     * <p>解除前会再次同步开发者对 Prompt Tool 列表的增删，然后恢复当前常驻 Tool，移除
     * ToolSearchTool 和动态发现的 Tool。解除后该实例可以绑定另一个 Prompt。</p>
     */
    public synchronized void unbind() {
        syncAlwaysVisibleTools();
        if (this.prompt != null) this.prompt.setTools(new ArrayList<>(this.alwaysVisibleTools.values()));
        this.prompt = null;
        this.alwaysVisibleTools = Collections.emptyMap();
        this.discoveredTools.clear();
    }

    /**
     * 清除最近一次搜索发现的 Tool，使 Prompt 回到“常驻 Tool + ToolSearchTool”的状态。
     *
     * <p>该操作不会清空搜索目录，也不会移除开发者直接添加到 Prompt 的常驻 Tool。</p>
     */
    public synchronized void reset() {
        syncAlwaysVisibleTools();
        discoveredTools.clear();
        refreshPrompt();
    }

    /**
     * 获取最近一次搜索激活的 Tool 快照。
     *
     * @return 不可修改的 Tool 列表；尚未搜索、已重置或最近搜索无结果时为空
     */
    public synchronized List<Tool> getDiscoveredTools() {
        return Collections.unmodifiableList(new ArrayList<>(discoveredTools.values()));
    }

    /**
     * @return 当前实例使用的 ToolSearchManager
     */
    public ToolSearchManager getManager() {
        return manager;
    }

    /**
     * 执行 Tool 搜索，并把命中的本地可执行 Tool 激活到绑定的 Prompt。
     *
     * <p>返回值只包含命中 Tool 的名称。完整 Tool 定义会在下一次使用绑定 Prompt 调用模型
     * 时发送，因此搜索本身不会执行任何业务 Tool。每次调用都会替换上一次发现结果。</p>
     *
     * @param argsMap 模型传入的参数，必须包含非空 {@code query}；可以包含
     *                {@code maxResults} 和 {@code category}
     * @return 已成功解析并激活的 Tool 名称列表
     * @throws IllegalArgumentException 当 query 为空，或 maxResults 不合法时抛出
     * @throws NumberFormatException    当字符串形式的 maxResults 不是整数时抛出
     */
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
        // 搜索前先吸收开发者对 Prompt Tool 列表的最新修改，避免用旧快照覆盖常驻 Tool。
        syncAlwaysVisibleTools();
        // 动态 Tool 只保留最近一次结果；即使本次无命中，也必须移除上次发现的 Tool。
        discoveredTools.clear();
        List<ToolSearchResult> activatedResults = new ArrayList<>();
        for (ToolSearchResult result : manager.search(request)) {
            if (result != null && result.getToolInfo() != null) {
                // Provider 只返回元数据；没有对应本地执行对象的陈旧或远程结果不能暴露给模型。
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
     * 根据 Prompt 当前内容重新计算开发者维护的常驻 Tool。
     *
     * <p>判断动态发现 Tool 时使用对象身份而不是只比较名称：同一个搜索结果对象会被排除，
     * 不会在下一次同步时被误提升为常驻 Tool；如果开发者主动用另一个同名 Tool 替换它，
     * 新对象则被视为显式配置的常驻 Tool。</p>
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
        // 顺序稳定为：开发者常驻 Tool、搜索入口、最近发现的 Tool；同名时前者优先。
        visibleTools.putAll(alwaysVisibleTools);
        visibleTools.put(this.getName(), this);
        for (Tool tool : discoveredTools.values()) visibleTools.putIfAbsent(tool.getName(), tool);
        this.prompt.setTools(new ArrayList<>(visibleTools.values()));
    }

    /**
     * ToolSearchTool 的构建器。
     *
     * <p>Builder 同时承担搜索目录的隔离配置：默认每次构建都会创建独立的 Manager 和
     * 内存 Provider；只有显式传入同一个 Manager 时，多个实例才共享工具目录。</p>
     */
    public static class Builder {
        /**
         * 构建完成时写入 Manager 的可搜索 Tool。
         */
        private final List<ToolRegistration> tools = new ArrayList<>();
        /**
         * 可选的元数据 Provider；未配置 Manager 时生效。
         */
        private ToolSearchProvider provider;
        /**
         * 可选的共享 Manager；与 provider 互斥。
         */
        private ToolSearchManager manager;
        /**
         * 构建后自动绑定的唯一 Prompt。
         */
        private Prompt prompt;
        /**
         * 模型看到的搜索 Tool 名称。
         */
        private String name = NAME;
        /**
         * 模型看到的搜索 Tool 使用说明。
         */
        private String description = DEFAULT_DESCRIPTION;

        /**
         * 仅允许通过 {@link ToolSearchTool#builder()} 创建 Builder。
         */
        protected Builder() {
        }

        /**
         * 配置 Tool 元数据的存储与搜索实现。
         *
         * @param provider 自定义 Provider；不配置时使用 {@link InMemoryToolSearchProvider}
         * @return 当前 Builder
         */
        public Builder provider(ToolSearchProvider provider) {
            this.provider = provider;
            return this;
        }

        /**
         * 配置已有 Manager，以复用已注册的可执行 Tool 和 Provider。
         *
         * @param manager 要使用的 Manager
         * @return 当前 Builder
         */
        public Builder manager(ToolSearchManager manager) {
            this.manager = manager;
            return this;
        }

        /**
         * 自定义模型可见的搜索 Tool 名称。
         *
         * @param name 非空 Tool 名称
         * @return 当前 Builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 自定义模型可见的搜索 Tool 描述。
         *
         * @param description Tool 使用说明
         * @return 当前 Builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 注册一个可搜索 Tool，检索元数据将通过 {@link ToolInfo#from(Tool)} 自动生成。
         *
         * @param tool 可搜索的业务 Tool
         * @return 当前 Builder
         */
        public Builder addTool(Tool tool) {
            this.tools.add(new ToolRegistration(tool, null));
            return this;
        }

        /**
         * 使用补充后的检索元数据注册一个可搜索 Tool。
         *
         * @param tool     本地可执行 Tool
         * @param toolInfo 与 Tool 同名的检索元数据
         * @return 当前 Builder
         */
        public Builder addTool(Tool tool, ToolInfo toolInfo) {
            this.tools.add(new ToolRegistration(tool, toolInfo));
            return this;
        }

        /**
         * 批量注册可搜索 Tool。
         *
         * @param tools Tool 集合；传入 {@code null} 时不执行任何操作
         * @return 当前 Builder
         */
        public Builder addTools(Collection<? extends Tool> tools) {
            if (tools != null) for (Tool tool : tools) addTool(tool);
            return this;
        }

        /**
         * 配置构建后自动绑定的 Prompt。
         *
         * <p>一个 Builder 只能配置一个 Prompt。Prompt 中已有和后续由开发者直接添加的
         * Tool 都属于常驻 Tool，不会自动加入搜索目录。</p>
         *
         * @param prompt 要绑定的 Prompt
         * @return 当前 Builder
         * @throws IllegalStateException 当已经配置了另一个 Prompt 时抛出
         */
        public Builder prompt(Prompt prompt) {
            if (this.prompt != null && this.prompt != prompt) {
                throw new IllegalStateException("Only one Prompt can be configured");
            }
            this.prompt = prompt;
            return this;
        }

        /**
         * 注册所有 Builder Tool、创建 ToolSearchTool，并按配置绑定 Prompt。
         *
         * @return 构建完成的 ToolSearchTool
         * @throws IllegalStateException 当 manager 与 provider 同时配置、名称为空，或者
         *                               搜索 Tool 名称与目录中已有 Tool 冲突时抛出
         */
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
        /**
         * 待注册的本地可执行 Tool。
         */
        private final Tool tool;
        /**
         * 可选的增强元数据；为空时从 Tool 自动生成。
         */
        private final ToolInfo info;

        private ToolRegistration(Tool tool, ToolInfo info) {
            this.tool = tool;
            this.info = info;
        }
    }
}
