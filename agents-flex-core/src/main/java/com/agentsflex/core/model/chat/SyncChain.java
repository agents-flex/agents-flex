package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.response.AiMessageResponse;

@FunctionalInterface
public interface SyncChain {
    AiMessageResponse proceed(BaseChatModel<?> model, ChatContext context);
}
