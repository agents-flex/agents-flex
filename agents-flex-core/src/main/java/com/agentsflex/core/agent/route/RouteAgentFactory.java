package com.agentsflex.core.agent.route;

import com.agentsflex.core.agent.IAgent;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.prompt.HistoriesPrompt;

/**
 * ReActAgent 工厂接口，支持不同 Agent 的定制化创建。
 */
@FunctionalInterface
public interface RouteAgentFactory {
    IAgent create(ChatModel chatModel, String userQuery, HistoriesPrompt historiesPrompt);
}
