package com.agentsflex.core.model.client;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.Prompt;

public interface ChatRequestSpecBuilder {
    ChatRequestSpec buildRequest(Prompt prompt, ChatConfig config, ChatOptions options);
}
