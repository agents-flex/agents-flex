package com.agentsflex.core.model.router.chat;

import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.prompt.Prompt;

import java.util.List;

/** 根据 Prompt、请求参数和上一次失败信息选择或排序模型候选节点。 */
@FunctionalInterface
public interface ChatModelSelector {
    List<ModelEndpoint<com.agentsflex.core.model.chat.ChatModel>> select(
        Prompt prompt, ChatOptions options,
        List<ModelEndpoint<com.agentsflex.core.model.chat.ChatModel>> candidates);
}
