/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.ChatInterceptorProvider;
import com.agentsflex.core.model.chat.ChatInterceptorOrders;
import com.agentsflex.core.model.chat.ChatInterceptorRegistration;
import com.agentsflex.core.model.chat.tool.BaseTool;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.toolsearch.memory.InMemoryToolSearchProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 面向大模型的 Tool 搜索入口，用于按需发现并渐进式暴露业务 Tool。
 *
 * <p>本 Tool 只负责检索目录并返回可执行 Tool 的名称，不持有也不修改 Prompt。普通 ChatModel
 * 场景由 {@link ToolSearchChatInterceptor} 根据消息中的最近一次搜索结果渐进披露 Tool；AgentRunner
 * 场景由 {@link ToolSearchAgentMiddleware} 保存 Turn 级搜索状态并处理工具可见性。</p>
 */
public class ToolSearchTool extends BaseTool implements ChatInterceptorProvider {

    /**
     * 默认 Tool 名称，同时是 Manager 禁止业务 Tool 占用的保留名称。
     */
    public static final String NAME = "toolSearch";

    private static final ChatInterceptor CHAT_INTERCEPTOR = new ToolSearchChatInterceptor();

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
     * @return 当前实例使用的 ToolSearchManager
     */
    public ToolSearchManager getManager() {
        return manager;
    }

    /** 自动为普通 ChatModel 请求启用 Tool 搜索结果的渐进式工具披露。 */
    @Override
    public List<ChatInterceptorRegistration> getChatInterceptorRegistrations() {
        return Collections.singletonList(
            ChatInterceptorRegistration.builder("tool-search", CHAT_INTERCEPTOR)
                .order(ChatInterceptorOrders.REQUEST_PREPARATION)
                .build());
    }

    /**
     * 执行 Tool 搜索并返回能够解析到本地执行对象的 Tool 名称。
     *
     * <p>搜索本身不会执行目标 Tool，也不会保存本次结果。调用方必须把 ToolMessage 加入消息历史，
     * 下一次模型调用时由 ToolSearchChatInterceptor 或 ToolSearchAgentMiddleware 披露完整定义。</p>
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

        List<String> names = new ArrayList<>();
        for (ToolSearchResult result : manager.search(request)) {
            if (result != null && result.getToolInfo() != null) {
                String toolName = result.getToolInfo().getName();
                if (toolName != null && manager.resolve(toolName) != null
                    && !names.contains(toolName)) names.add(toolName);
            }
        }
        return names;
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
         * 注册所有 Builder Tool 并创建无运行状态的 ToolSearchTool。
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
            return new ToolSearchTool(resolvedManager, name, description);
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
