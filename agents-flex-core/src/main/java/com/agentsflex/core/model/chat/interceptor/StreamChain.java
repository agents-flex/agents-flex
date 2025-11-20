package com.agentsflex.core.model.chat.interceptor;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.StreamResponseListener;

@FunctionalInterface
public interface StreamChain {
    void proceed(BaseChatModel<?> model, ChatContext context, StreamResponseListener listener);
}
