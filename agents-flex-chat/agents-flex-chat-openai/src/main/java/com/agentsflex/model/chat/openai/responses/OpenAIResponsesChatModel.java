package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.OpenAICompatibleChatModel;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.model.client.OpenAIResponsesChatClient;

import java.util.List;

/** Chat model using the OpenAI Responses protocol. */
public class OpenAIResponsesChatModel extends OpenAICompatibleChatModel<OpenAIResponsesChatConfig> {
    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config) {
        super(config);
    }

    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config, List<ChatInterceptor> interceptors) {
        super(config, interceptors);
    }

    @Override
    protected ChatRequestSpecBuilder buildChatRequestSpecBuilder() {
        return new OpenAIResponsesRequestSpecBuilder();
    }

    @Override
    protected ChatClient buildChatClient() {
        return new OpenAIResponsesChatClient(this);
    }
}
