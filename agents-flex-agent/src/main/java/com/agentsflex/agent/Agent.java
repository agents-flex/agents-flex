/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.task.AgentPlanningPolicy;
import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.tool.AgentToolResolver;
import com.agentsflex.agent.tool.ToolApprovalPolicy;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.model.chat.toolgroup.ToolGroup;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Agent 的不可变定义对象。
 *
 * <p>该类只描述一个 Agent 的静态能力，包括名称、系统指令、模型、工具、工具拦截器和执行策略，
 * 不保存任何一次具体运行产生的消息、迭代次数或最终结果。一次任务的可变状态由
 * {@link AgentTurn} 保存，实际执行流程由 {@link AgentRunner} 推进。</p>
 *
 * <p>Agent 构建完成后，其工具和拦截器集合会转换为只读集合，因此同一个 Agent 可以被多个
 * {@link AgentTurn} 复用。需要注意的是，{@link ChatModel}、{@link ChatOptions} 和具体 Tool
 * 实现本身是否线程安全，仍由对应实现负责。</p>
 */
public final class Agent {

    /**
     * 用于 Snapshot 恢复和 AgentLoader 加载的稳定 ID。
     */
    private final String id;
    /**
     * Agent 配置版本，用于让运行中的任务绑定到创建时的配置。
     */
    private final String version;
    /**
     * Agent 的业务名称，用于日志、追踪和 Agent 路由。
     */
    private final String name;
    /**
     * 提供给使用者和规划模型的公开能力描述。
     */
    private final String description;
    /**
     * 作为 SystemMessage 注入对话的系统指令。
     */
    private final String instructions;
    /**
     * 负责生成回答和原生 ToolCall 的聊天模型。
     */
    private final ChatModel chatModel;
    /**
     * 处理图片、音频、视频或文件等多模态输入的可选模型。
     */
    private final ChatModel multimodalChatModel;
    /**
     * 模型调用参数模板。每次请求都会创建独立副本，避免不同 Turn 修改同一个配置实例。
     */
    private final ChatOptions chatOptions;
    /**
     * AgentRunner 最终可执行的工具全集，包含直接工具和 ToolGroup 工具。
     */
    private final List<Tool> executableTools;
    /**
     * 直接配置、始终加入 Prompt 的 Tool；ToolGroup 中的 Tool 不在此列表中。
     */
    private final List<Tool> tools;
    /**
     * 按当前请求条件向模型暴露 Tool 和系统指令的工具组。
     */
    private final List<ToolGroup> toolGroups;
    /**
     * 按唯一工具名建立的只读索引，供 ToolCall 和恢复流程直接定位工具。
     */
    private final Map<String, Tool> toolsByName;
    /**
     * 仅作用于当前 Agent 工具调用的拦截器集合。
     */
    private final List<ToolInterceptor> toolInterceptors;
    /**
     * 控制最大迭代次数以及工具异常处理方式的执行策略。
     */
    private final AgentExecutionPolicy executionPolicy;
    /**
     * 控制工具是否需要外部审批。
     */
    private final ToolApprovalPolicy toolApprovalPolicy;
    /**
     * 控制模型是否可以自主创建并执行任务计划。
     */
    private final AgentPlanningPolicy planningPolicy;
    /**
     * 每次模型调用最多从 Turn 历史中附加的消息数量。
     */
    private final int maxAttachedMessages;
    /**
     * 每次模型调用最多保留的完整 Turn 数量。
     */
    private final int maxAttachedTurns;
    /**
     * 是否将较早已完成工具 Turn 压缩为 UserMessage + 最终 AiMessage。
     */
    private final boolean compactCompletedToolTurns;
    /**
     * 最近多少个 Turn 不参与规则或语义压缩。
     */
    private final int compressionKeepRecentTurns;
    /**
     * 可选的业务语义压缩器。
     */
    private final AgentContextCompressor contextCompressor;
    /**
     * 包装步骤、模型调用和工具调用的中间件。
     */
    private final List<AgentMiddleware> middlewares;
    /**
     * 由 Middleware 在 Agent 构建阶段声明的动态工具解析器。
     */
    private final List<AgentToolResolver> toolResolvers;
    /**
     * 供配置平台保存模式参数、任务类型和发布信息的只读扩展属性。
     */
    private final Map<String, Object> attributes;

    private Agent(Builder builder) {
        this.id = builder.id;
        this.version = builder.version;
        this.name = builder.name;
        this.description = builder.description;
        this.instructions = builder.instructions;
        this.chatModel = builder.chatModel;
        this.multimodalChatModel = builder.multimodalChatModel;
        this.chatOptions = builder.chatOptions;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.toolGroups = Collections.unmodifiableList(new ArrayList<>(builder.toolGroups));
        this.executableTools = Collections.unmodifiableList(builder.effectiveTools());
        Map<String, Tool> indexedTools = new HashMap<>();
        for (Tool tool : this.executableTools) {
            indexedTools.put(tool.getName(), tool);
        }
        this.toolsByName = Collections.unmodifiableMap(indexedTools);
        this.toolInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.toolInterceptors));
        this.executionPolicy = builder.executionPolicy;
        this.toolApprovalPolicy = builder.toolApprovalPolicy;
        this.planningPolicy = builder.planningPolicy;
        this.maxAttachedMessages = builder.maxAttachedMessages;
        this.maxAttachedTurns = builder.maxAttachedTurns;
        this.compactCompletedToolTurns = builder.compactCompletedToolTurns;
        this.compressionKeepRecentTurns = builder.compressionKeepRecentTurns;
        this.contextCompressor = builder.contextCompressor;
        this.middlewares = Collections.unmodifiableList(new ArrayList<>(builder.middlewares));
        List<AgentToolResolver> resolvers = new ArrayList<>();
        for (AgentMiddleware middleware : this.middlewares) {
            AgentToolResolver resolver = middleware.getToolResolver();
            if (resolver != null) resolvers.add(resolver);
        }
        this.toolResolvers = Collections.unmodifiableList(resolvers);
        this.attributes = Collections.unmodifiableMap(new HashMap<>(builder.attributes));
    }

    /**
     * 创建一个使用默认名称 {@code agent} 的构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建一个已设置 Agent 名称的构建器。
     *
     * @param name Agent 名称
     */
    public static Builder builder(String name) {
        return new Builder().name(name);
    }

    /**
     * @return Agent 的稳定 ID，用于 Snapshot 和恢复加载
     */
    public String getId() {
        return id;
    }

    /**
     * @return Agent 配置版本
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return Agent 的业务名称
     */
    public String getName() {
        return name;
    }

    /**
     * @return Agent 的公开能力描述；未配置时可能为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return Agent 的系统指令；未配置时可能为 {@code null}
     */
    public String getInstructions() {
        return instructions;
    }

    /**
     * @return Agent 使用的聊天模型
     */
    public ChatModel getChatModel() {
        return chatModel;
    }

    /**
     * @return 多模态消息存在时使用的模型；未配置时返回 {@code null}
     */
    public ChatModel getMultimodalChatModel() {
        return multimodalChatModel;
    }

    /**
     * @return 调用聊天模型时使用的参数
     */
    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    /**
     * @return 直接配置、默认始终对模型可见的工具列表
     */
    public List<Tool> getTools() {
        return tools;
    }

    /** 返回 Runner 可执行的完整 Tool 集合，包含直接 Tool 和所有 ToolGroup 中的 Tool。 */
    List<Tool> getExecutableTools() {
        return executableTools;
    }

    /** @return 当前 Agent 配置的请求级 ToolGroup。 */
    public List<ToolGroup> getToolGroups() {
        return toolGroups;
    }

    /**
     * 根据模型 ToolCall 中的工具名返回当前 Agent 版本绑定的工具。
     *
     * @return 对应工具；当前 Agent 未配置该名称时返回 {@code null}
     */
    public Tool getTool(String name) {
        return name == null ? null : toolsByName.get(name);
    }

    /**
     * 解析当前 Turn 要执行的工具。显式注册在 Agent 上的 Tool 优先，随后按 Middleware 注册顺序
     * 查询动态 Resolver；多个 Resolver 同时返回结果时视为配置冲突。
     */
    public Tool resolveTool(AgentTurn turn, String name) {
        if (turn == null || name == null) return null;
        Tool direct = toolsByName.get(name);
        if (direct != null) return direct;
        Tool resolved = null;
        for (AgentToolResolver resolver : toolResolvers) {
            Tool candidate = resolver.resolve(turn, name);
            if (candidate == null) continue;
            if (!name.equals(candidate.getName())) {
                throw new IllegalStateException(
                    "AgentToolResolver returned tool '" + candidate.getName()
                        + "' for requested name: " + name);
            }
            if (resolved != null) {
                throw new IllegalStateException(
                    "multiple AgentToolResolvers resolved tool: " + name);
            }
            resolved = candidate;
        }
        return resolved;
    }

    /**
     * @return 不可修改的 Agent 级工具拦截器列表
     */
    public List<ToolInterceptor> getToolInterceptors() {
        return toolInterceptors;
    }

    /**
     * @return 当前 Agent 的执行策略
     */
    public AgentExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    /**
     * @return 工具执行前使用的审批策略
     */
    public ToolApprovalPolicy getToolApprovalPolicy() {
        return toolApprovalPolicy;
    }

    /**
     * @return 模型自主创建任务计划时使用的约束策略
     */
    public AgentPlanningPolicy getPlanningPolicy() {
        return planningPolicy;
    }

    /**
     * @return 每次模型调用最多附加的历史消息数量
     */
    public int getMaxAttachedMessages() {
        return maxAttachedMessages;
    }

    public int getMaxAttachedTurns() {
        return maxAttachedTurns;
    }

    public boolean isCompactCompletedToolTurns() {
        return compactCompletedToolTurns;
    }

    public int getCompressionKeepRecentTurns() {
        return compressionKeepRecentTurns;
    }

    public AgentContextCompressor getContextCompressor() {
        return contextCompressor;
    }

    /**
     * @return 按注册顺序执行的只读 Middleware 列表
     */
    public List<AgentMiddleware> getMiddlewares() {
        return middlewares;
    }

    /**
     * @return 供平台查询和审计的不可修改扩展属性
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * {@link Agent} 构建器。
     *
     * <p>构建时会校验 ChatModel、Agent 名称和工具名称。工具名称必须非空且在当前 Agent 内唯一，
     * 因为模型返回 ToolCall 后，Runner 需要通过名称准确找到对应的 Tool 实现。</p>
     */
    public static final class Builder {

        private String id;
        private String version = "1";
        private String name = "agent";
        private String description;
        private String instructions;
        private ChatModel chatModel;
        private ChatModel multimodalChatModel;
        private ChatOptions chatOptions = new ChatOptions();
        private final List<Tool> tools = new ArrayList<>();
        private final List<ToolGroup> toolGroups = new ArrayList<>();
        private final List<ToolInterceptor> toolInterceptors = new ArrayList<>();
        private AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.defaults();
        private ToolApprovalPolicy toolApprovalPolicy = ToolApprovalPolicy.allowAll();
        private AgentPlanningPolicy planningPolicy = AgentPlanningPolicy.disabled();
        private int maxAttachedMessages = 100;
        private int maxAttachedTurns = 10;
        private boolean compactCompletedToolTurns = true;
        private int compressionKeepRecentTurns = 2;
        private AgentContextCompressor contextCompressor;
        private final List<AgentMiddleware> middlewares = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();

        /**
         * 设置 Agent 的稳定 ID。未设置时默认使用 name。
         *
         * <p>持久化 AgentTurn 后不应随意修改该 ID，否则旧 Snapshot 将无法重新绑定 Agent。</p>
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * 设置配置平台发布的 Agent 配置版本。
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * 设置 Agent 名称。
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置可展示给使用者和规划模型的能力描述。
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置会作为系统消息发送给模型的指令。
         */
        public Builder instructions(String instructions) {
            this.instructions = instructions;
            return this;
        }

        /**
         * 设置 Agent 使用的聊天模型，该配置为必填项。
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 配置处理多模态输入的模型。当前 Prompt 包含图片、音频、视频或文件时，Runner 优先使用该模型；
         * 纯文本请求仍使用 {@link #chatModel(ChatModel)} 配置的默认模型。
         */
        public Builder multimodalChatModel(ChatModel multimodalChatModel) {
            this.multimodalChatModel = multimodalChatModel;
            return this;
        }

        /**
         * 设置模型调用参数；传入 {@code null} 时使用默认参数。
         */
        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        /**
         * 添加一个工具；传入 {@code null} 时忽略。
         */
        public Builder tool(Tool tool) {
            if (tool != null) {
                this.tools.add(tool);
            }
            return this;
        }

        /**
         * 批量添加工具；集合中的 {@code null} 元素会被忽略。
         */
        public Builder tools(List<? extends Tool> tools) {
            if (tools != null) {
                for (Tool tool : tools) {
                    tool(tool);
                }
            }
            return this;
        }

        /**
         * 添加按请求条件向模型暴露 Tool 的工具组。
         *
         * <p>组内 Tool 会自动登记为 AgentRunner 可执行 Tool，但不会无条件加入 Prompt；只有
         * ToolGroup 的 matcher 匹配当前模型请求时才会对模型可见。若同名 Tool 已显式注册，必须是
         * 同一个实例，避免模型看到的 Tool 与实际执行的 Tool 不一致。</p>
         */
        public Builder toolGroup(ToolGroup toolGroup) {
            if (toolGroup != null) this.toolGroups.add(toolGroup);
            return this;
        }

        /** 批量添加 ToolGroup；集合中的 null 元素会被忽略。 */
        public Builder toolGroups(List<? extends ToolGroup> toolGroups) {
            if (toolGroups != null) for (ToolGroup toolGroup : toolGroups) toolGroup(toolGroup);
            return this;
        }

        /**
         * 添加一个只作用于当前 Agent 的工具调用拦截器。
         */
        public Builder toolInterceptor(ToolInterceptor interceptor) {
            if (interceptor != null) {
                this.toolInterceptors.add(interceptor);
            }
            return this;
        }

        /**
         * 设置运行限制和工具错误处理策略。
         */
        public Builder executionPolicy(AgentExecutionPolicy executionPolicy) {
            this.executionPolicy = executionPolicy;
            return this;
        }

        /**
         * 设置工具执行前的审批策略。
         */
        public Builder toolApprovalPolicy(ToolApprovalPolicy toolApprovalPolicy) {
            this.toolApprovalPolicy = toolApprovalPolicy;
            return this;
        }

        /**
         * 设置模型自主创建任务计划时使用的约束策略。
         */
        public Builder planningPolicy(AgentPlanningPolicy planningPolicy) {
            this.planningPolicy = planningPolicy;
            return this;
        }

        /**
         * 设置每次模型调用最多附加的历史消息数量。
         *
         * <p>该限制只影响本次发送给模型的消息视图，不删除 Turn 或 Snapshot 中保存的完整历史。</p>
         */
        public Builder maxAttachedMessages(int maxAttachedMessages) {
            if (maxAttachedMessages <= 0) {
                throw new IllegalArgumentException("maxAttachedMessages must be greater than 0");
            }
            this.maxAttachedMessages = maxAttachedMessages;
            return this;
        }

        /**
         * 设置模型上下文最多附加的完整 Turn 数量。
         */
        public Builder maxAttachedTurns(int maxAttachedTurns) {
            if (maxAttachedTurns <= 0) {
                throw new IllegalArgumentException("maxAttachedTurns must be greater than 0");
            }
            this.maxAttachedTurns = maxAttachedTurns;
            return this;
        }

        /**
         * 设置是否压缩较早的已完成工具 Turn；完整历史仍保留在 ChatMemory 和 Snapshot 中。
         */
        public Builder compactCompletedToolTurns(boolean value) {
            this.compactCompletedToolTurns = value;
            return this;
        }

        /**
         * 设置最近多少个 Turn 不参与压缩，默认保留最近 2 个完整 Turn。
         */
        public Builder compressionKeepRecentTurns(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("compressionKeepRecentTurns must not be negative");
            }
            this.compressionKeepRecentTurns = value;
            return this;
        }

        /**
         * 设置可选的语义上下文压缩器；未设置时仅使用规则压缩。
         */
        public Builder contextCompressor(AgentContextCompressor value) {
            this.contextCompressor = value;
            return this;
        }

        /**
         * 添加一个 Agent 运行时中间件。
         */
        public Builder middleware(AgentMiddleware middleware) {
            if (middleware != null) this.middlewares.add(middleware);
            return this;
        }

        /**
         * 批量添加 Agent 运行时中间件。
         */
        public Builder middlewares(List<? extends AgentMiddleware> values) {
            if (values != null) for (AgentMiddleware value : values) middleware(value);
            return this;
        }

        /**
         * 添加供平台查询和审计的 Agent 定义属性。
         *
         * <p>attributes 不会自动发送给模型；需要影响模型行为时应使用 instructions、工具描述或
         * Middleware 显式处理。</p>
         */
        public Builder attribute(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("attribute key must not be null");
            }
            this.attributes.put(key, value);
            return this;
        }

        /**
         * 批量添加 Agent 定义扩展属性。
         */
        public Builder attributes(Map<String, ?> values) {
            if (values != null) {
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    attribute(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        /**
         * 完成参数校验并创建不可变 Agent。
         *
         * @throws IllegalStateException 模型未配置、名称为空或工具名称非法时抛出
         */
        public Agent build() {
            if (chatModel == null) {
                throw new IllegalStateException("chatModel must not be null");
            }
            if (!StringUtil.hasText(name)) {
                throw new IllegalStateException("name must not be blank");
            }
            if (!StringUtil.hasText(id)) {
                id = name;
            }
            if (!StringUtil.hasText(version)) {
                throw new IllegalStateException("version must not be blank");
            }
            if (chatOptions == null) {
                chatOptions = new ChatOptions();
            }
            if (executionPolicy == null) {
                executionPolicy = AgentExecutionPolicy.defaults();
            }
            if (toolApprovalPolicy == null) {
                toolApprovalPolicy = ToolApprovalPolicy.allowAll();
            }
            if (planningPolicy == null) {
                throw new IllegalStateException("planningPolicy must not be null");
            }
            List<Tool> effectiveTools = effectiveTools();
            validateUniqueToolNames(effectiveTools);
            if (planningPolicy.isEnabled() && effectiveTools.stream().anyMatch(tool ->
                AgentPlanningTool.NAME.equals(tool.getName())
                    || AgentPlanningTool.UPDATE_NAME.equals(tool.getName()))) {
                throw new IllegalStateException(
                    "tool name is reserved by Agent planning: " + AgentPlanningTool.NAME);
            }
            return new Agent(this);
        }

        /**
         * 校验工具名称可用于从模型 ToolCall 唯一定位工具实现。
         */
        private void validateUniqueToolNames(List<Tool> values) {
            Set<String> names = new HashSet<>();
            for (Tool tool : values) {
                if (!StringUtil.hasText(tool.getName())) {
                    throw new IllegalStateException("tool name must not be blank");
                }
                if (!names.add(tool.getName())) {
                    throw new IllegalStateException("duplicate tool name: " + tool.getName());
                }
            }
        }

        /** 合并常驻 Tool 与 ToolGroup Tool，保留唯一、稳定的执行实例。 */
        private List<Tool> effectiveTools() {
            Map<String, Tool> result = new LinkedHashMap<>();
            addTools(result, tools);
            for (ToolGroup toolGroup : toolGroups) addTools(result, toolGroup.getTools());
            return new ArrayList<>(result.values());
        }

        private static void addTools(Map<String, Tool> target, List<? extends Tool> values) {
            for (Tool tool : values) {
                if (tool == null || !StringUtil.hasText(tool.getName())) continue;
                Tool existing = target.get(tool.getName());
                if (existing != null && existing != tool) {
                    throw new IllegalStateException("duplicate tool name with different instances: " + tool.getName());
                }
                target.put(tool.getName(), tool);
            }
        }
    }
}
