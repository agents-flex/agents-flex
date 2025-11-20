package com.agentsflex.core.model.chat.interceptor;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

@FunctionalInterface
public interface SyncChain {
    AiMessageResponse proceed(BaseChatModel<?> model, ChatContext context);
}
