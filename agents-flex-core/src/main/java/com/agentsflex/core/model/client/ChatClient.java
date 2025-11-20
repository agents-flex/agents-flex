package com.agentsflex.core.model.client;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

public abstract class ChatClient {
    protected BaseChatModel<?> chatModel;
    protected ChatContext context;

    public ChatClient(BaseChatModel<?> chatModel, ChatContext context) {
        this.chatModel = chatModel;
        this.context = context;
    }

    public abstract AiMessageResponse chat();

    public abstract void chatStream(StreamResponseListener listener);

}
