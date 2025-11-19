package com.agentsflex.core.agents.route;

import com.agentsflex.core.agents.IAgent;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.prompt.HistoriesPrompt;

/**
 * ReActAgent 工厂接口，支持不同 Agent 的定制化创建。
 */
@FunctionalInterface
public interface AgentFactory {
    IAgent create(ChatModel chatModel, String userQuery, HistoriesPrompt historiesPrompt);
}
