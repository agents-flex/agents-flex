package com.agentsflex.core.model.chat;

@FunctionalInterface
public interface StreamChain {
    void proceed(BaseChatModel<?> model, ChatContext context, StreamResponseListener listener);
}
