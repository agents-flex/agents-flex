package com.agentsflex.core.model.router.chat;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.prompt.Prompt;

/**
 * 根据 Prompt、请求参数和上一次失败信息选择或排序模型候选节点。
 */
@FunctionalInterface
public interface ChatModelSelector {
    java.util.List<ModelEndpoint<ChatModel>> select(
        Prompt prompt, ChatOptions options, ChatModelCandidates candidates);
}
