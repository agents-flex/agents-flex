/*
 * Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.agents.route;

import com.agentsflex.core.agents.IAgent;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.HistoriesPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RouteAgent：负责路由用户输入到最合适的 IAgent。
 * - 不实现 IAgent 接口
 * - route() 方法返回 IAgent 实例，或 null（表示无匹配）
 * - 不处理 Direct Answer，一律返回 null
 * - 支持关键字快速匹配 + LLM 智能路由
 */
public class RouteAgent {

    private static final Logger log = LoggerFactory.getLogger(RouteAgent.class);

    private static final String DEFAULT_ROUTING_PROMPT_TEMPLATE =
        "你是一个智能路由助手，请严格按以下规则响应：\n" +
            "\n" +
            "可用处理模块（Agent）及其能力描述：\n" +
            "{agent_descriptions}\n" +
            "\n" +
            "规则：\n" +
            "1. 如果用户问题属于某个模块的能力范围，请输出：Route: [模块名]\n" +
            "2. 如果问题可以直接回答（如问候、常识、简单对话），请输出：Direct: [你的自然语言回答]\n" +
            "3. 如果问题涉及多个模块，选择最核心的一个。\n" +
            "4. 不要解释、不要输出其他内容，只输出上述两种格式之一。\n" +
            "\n" +
            "当前对话上下文（最近几轮）：\n" +
            "{conversation_context}\n" +
            "\n" +
            "用户最新问题：\n" +
            "{user_input}";

    private final ChatModel chatModel;
    private final AgentRegistry agentRegistry;
    private final String userQuery;
    private final HistoriesPrompt conversationHistory;

    private String routingPromptTemplate = DEFAULT_ROUTING_PROMPT_TEMPLATE;
    private ChatOptions chatOptions = ChatOptions.DEFAULT;
    private boolean enableKeywordRouting = true;
    private boolean enableLlmRouting = true;

    public RouteAgent(ChatModel chatModel, AgentRegistry agentRegistry,
                      String userQuery, HistoriesPrompt conversationHistory) {
        this.chatModel = chatModel;
        this.agentRegistry = agentRegistry;
        this.userQuery = userQuery;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 路由用户输入，返回匹配的 IAgent 实例。
     * 仅当明确路由到某个 Agent 时才返回 IAgent，否则返回 null。
     *
     * @return IAgent 实例（仅 Route:xxx 场景），或 null（包括 Direct:、无匹配、异常等）
     */
    public IAgent route() {
        try {
            // 1. 关键字快速匹配
            if (enableKeywordRouting) {
                String agentName = agentRegistry.findAgentByKeyword(userQuery);
                if (agentName != null) {
                    log.debug("关键字匹配命中 Agent: {}", agentName);
                    return createAgent(agentName);
                }
            }

            // 2. LLM 智能路由
            if (enableLlmRouting) {
                String contextSummary = buildContextSummary(conversationHistory);
                String agentDescriptions = agentRegistry.getAgentDescriptions();
                String prompt = routingPromptTemplate
                    .replace("{agent_descriptions}", agentDescriptions)
                    .replace("{conversation_context}", contextSummary)
                    .replace("{user_input}", userQuery);

                String decision = chatModel.chat(prompt, chatOptions);

                if (decision != null && decision.startsWith("Route:")) {
                    String agentName = decision.substring("Route:".length()).trim();
                    return createAgent(agentName);
                }
            }

            // 3. 无有效 Route，返回 null
            log.debug("RouteAgent 未匹配到可路由的 Agent，返回 null。Query: {}", userQuery);
            return null;

        } catch (Exception e) {
            log.error("RouteAgent 路由异常，返回 null", e);
            return null;
        }
    }

    private IAgent createAgent(String agentName) {
        AgentFactory factory = agentRegistry.getAgentFactory(agentName);
        if (factory == null) {
            log.warn("Agent 不存在: {}, 返回 null", agentName);
            return null;
        }
        return factory.create(chatModel, userQuery, conversationHistory);
    }

    private String buildContextSummary(HistoriesPrompt history) {
        List<Message> messages = history.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "（无历史对话）";
        }

        int start = Math.max(0, messages.size() - 4);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String role = msg instanceof AiMessage ? "AI" : "User";
            String content = msg.getTextContent() != null ? msg.getTextContent() : "";
            sb.append(role).append(": ").append(content.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    // ===== 配置方法 =====

    public void setEnableKeywordRouting(boolean enable) {
        this.enableKeywordRouting = enable;
    }

    public void setEnableLlmRouting(boolean enable) {
        this.enableLlmRouting = enable;
    }

    public void setRoutingPromptTemplate(String routingPromptTemplate) {
        if (routingPromptTemplate != null && !routingPromptTemplate.trim().isEmpty()) {
            this.routingPromptTemplate = routingPromptTemplate;
        }
    }

    public void setChatOptions(ChatOptions chatOptions) {
        if (chatOptions != null) {
            this.chatOptions = chatOptions;
        }
    }
}
