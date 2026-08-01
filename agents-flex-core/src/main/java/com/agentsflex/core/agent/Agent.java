/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.task.AgentTaskPlanner;
import com.agentsflex.core.agent.context.AgentContextManager;
import com.agentsflex.core.agent.context.ToolResultOffloadPolicy;
import com.agentsflex.core.agent.middleware.AgentMiddleware;
import com.agentsflex.core.agent.mode.AgentExecutionMode;
import com.agentsflex.core.agent.mode.ToolCallingAgentExecutionMode;
import com.agentsflex.core.agent.tool.ToolApprovalPolicy;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Agent 的不可变定义对象。
 *
 * <p>该类只描述一个 Agent 的静态能力，包括名称、系统指令、模型、工具、工具拦截器和执行策略，
 * 不保存任何一次具体运行产生的消息、迭代次数或最终结果。一次任务的可变状态由
 * {@link AgentRun} 保存，实际执行流程由 {@link AgentRunner} 推进。</p>
 *
 * <p>Agent 构建完成后，其工具和拦截器集合会转换为只读集合，因此同一个 Agent 可以被多个
 * {@link AgentRun} 复用。需要注意的是，{@link ChatModel}、{@link ChatOptions} 和具体 Tool
 * 实现本身是否线程安全，仍由对应实现负责。</p>
 */
public final class Agent {

    /**
     * 用于 Checkpoint 恢复和 Registry 查找的稳定 ID。
     */
    private final String id;
    /** Agent 定义版本，用于让运行中的任务绑定到创建时的配置。 */
    private final String version;
    /**
     * Agent 的业务名称，用于日志、追踪和 Agent 路由。
     */
    private final String name;
    /**
     * 作为 SystemMessage 注入对话的系统指令。
     */
    private final String instructions;
    /**
     * 负责生成回答和原生 ToolCall 的聊天模型。
     */
    private final ChatModel chatModel;
    /**
     * 每次调用聊天模型时使用的生成参数和上下文参数。
     */
    private final ChatOptions chatOptions;
    /**
     * 暴露给模型并允许 AgentRunner 执行的工具集合。
     */
    private final List<Tool> tools;
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
    /** 将复杂目标拆分为可持久化任务计划的可选规划器。 */
    private final AgentTaskPlanner taskPlanner;
    /** 决定 AgentRun 如何推进的运行模式。 */
    private final AgentExecutionMode executionMode;
    /** 控制每次模型调用可见消息范围的上下文策略。 */
    private final AgentContextPolicy contextPolicy;
    /** 在模型调用前压缩或整理持久化消息历史。 */
    private final AgentContextManager contextManager;
    /** 判断大型工具结果是否需要外置。 */
    private final ToolResultOffloadPolicy toolResultOffloadPolicy;
    /** 包装步骤、模型调用和工具调用的中间件。 */
    private final List<AgentMiddleware> middlewares;
    /** 供配置平台保存模式参数、任务类型和发布信息的只读扩展属性。 */
    private final Map<String, Object> attributes;

    private Agent(Builder builder) {
        this.id = builder.id;
        this.version = builder.version;
        this.name = builder.name;
        this.instructions = builder.instructions;
        this.chatModel = builder.chatModel;
        this.chatOptions = builder.chatOptions;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.toolInterceptors = Collections.unmodifiableList(new ArrayList<>(builder.toolInterceptors));
        this.executionPolicy = builder.executionPolicy;
        this.toolApprovalPolicy = builder.toolApprovalPolicy;
        this.taskPlanner = builder.taskPlanner;
        this.executionMode = builder.executionMode;
        this.contextPolicy = builder.contextPolicy;
        this.contextManager = builder.contextManager;
        this.toolResultOffloadPolicy = builder.toolResultOffloadPolicy;
        this.middlewares = Collections.unmodifiableList(new ArrayList<>(builder.middlewares));
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
     * @return Agent 的稳定 ID，用于持久化引用和恢复绑定
     */
    public String getId() {
        return id;
    }

    /** @return Agent 定义版本 */
    public String getVersion() { return version; }

    /**
     * @return Agent 的业务名称
     */
    public String getName() {
        return name;
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
     * @return 调用聊天模型时使用的参数
     */
    public ChatOptions getChatOptions() {
        return chatOptions;
    }

    /**
     * @return 不可修改的工具列表
     */
    public List<Tool> getTools() {
        return tools;
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

    /** @return 工具执行前使用的审批策略 */
    public ToolApprovalPolicy getToolApprovalPolicy() {
        return toolApprovalPolicy;
    }

    /** @return 当前 Agent 配置的任务规划器；未启用任务规划时返回 {@code null} */
    public AgentTaskPlanner getTaskPlanner() {
        return taskPlanner;
    }

    public AgentExecutionMode getExecutionMode() { return executionMode; }

    public AgentContextPolicy getContextPolicy() { return contextPolicy; }

    public AgentContextManager getContextManager() { return contextManager; }

    public ToolResultOffloadPolicy getToolResultOffloadPolicy() { return toolResultOffloadPolicy; }

    public List<AgentMiddleware> getMiddlewares() { return middlewares; }

    public Map<String, Object> getAttributes() { return attributes; }

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
        private String instructions;
        private ChatModel chatModel;
        private ChatOptions chatOptions = new ChatOptions();
        private final List<Tool> tools = new ArrayList<>();
        private final List<ToolInterceptor> toolInterceptors = new ArrayList<>();
        private AgentExecutionPolicy executionPolicy = AgentExecutionPolicy.defaults();
        private ToolApprovalPolicy toolApprovalPolicy = ToolApprovalPolicy.allowAll();
        private AgentTaskPlanner taskPlanner;
        private AgentExecutionMode executionMode = ToolCallingAgentExecutionMode.INSTANCE;
        private AgentContextPolicy contextPolicy = AgentContextPolicy.defaults();
        private AgentContextManager contextManager = AgentContextManager.none();
        private ToolResultOffloadPolicy toolResultOffloadPolicy = ToolResultOffloadPolicy.disabled();
        private final List<AgentMiddleware> middlewares = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();

        /**
         * 设置 Agent 的稳定 ID。未设置时默认使用 name。
         *
         * <p>持久化 AgentRun 后不应随意修改该 ID，否则旧 Checkpoint 将无法重新绑定 Agent。</p>
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /** 设置配置平台发布的 Agent 定义版本。 */
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

        /** 设置复杂目标使用的任务规划器。 */
        public Builder taskPlanner(AgentTaskPlanner taskPlanner) {
            this.taskPlanner = taskPlanner;
            return this;
        }

        /** 设置运行模式；未配置时使用模型原生 ToolCall 循环。 */
        public Builder executionMode(AgentExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        /** 设置模型上下文读取策略。 */
        public Builder contextPolicy(AgentContextPolicy contextPolicy) {
            this.contextPolicy = contextPolicy;
            return this;
        }

        /** 设置模型调用前使用的消息上下文管理器。 */
        public Builder contextManager(AgentContextManager contextManager) {
            this.contextManager = contextManager;
            return this;
        }

        /** 设置工具结果外置策略。 */
        public Builder toolResultOffloadPolicy(ToolResultOffloadPolicy policy) {
            this.toolResultOffloadPolicy = policy;
            return this;
        }

        /** 添加一个 Agent 运行时中间件。 */
        public Builder middleware(AgentMiddleware middleware) {
            if (middleware != null) this.middlewares.add(middleware);
            return this;
        }

        /** 批量添加 Agent 运行时中间件。 */
        public Builder middlewares(List<? extends AgentMiddleware> values) {
            if (values != null) for (AgentMiddleware value : values) middleware(value);
            return this;
        }

        /** 添加供平台查询和审计的 Agent 定义属性。 */
        public Builder attribute(String key, Object value) {
            if (key == null) {
                throw new IllegalArgumentException("attribute key must not be null");
            }
            this.attributes.put(key, value);
            return this;
        }

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
            if (executionMode == null || contextPolicy == null || contextManager == null
                || toolResultOffloadPolicy == null) {
                throw new IllegalStateException("Agent runtime policies must not be null");
            }
            validateUniqueToolNames();
            Agent agent = new Agent(this);
            executionMode.validate(agent);
            return agent;
        }

        /**
         * 校验工具名称可用于从模型 ToolCall 唯一定位工具实现。
         */
        private void validateUniqueToolNames() {
            Set<String> names = new HashSet<>();
            for (Tool tool : tools) {
                if (!StringUtil.hasText(tool.getName())) {
                    throw new IllegalStateException("tool name must not be blank");
                }
                if (!names.add(tool.getName())) {
                    throw new IllegalStateException("duplicate tool name: " + tool.getName());
                }
            }
        }
    }
}
