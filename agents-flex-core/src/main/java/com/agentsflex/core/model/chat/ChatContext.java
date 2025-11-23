package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.prompt.Prompt;

public class ChatContext {

    ChatConfig config;
    ChatOptions options;
    Prompt prompt;
    ChatRequestSpec requestSpec;

    public ChatConfig getConfig() {
        return config;
    }

    public void setConfig(ChatConfig config) {
        this.config = config;
    }

    public ChatOptions getOptions() {
        return options;
    }

    public void setOptions(ChatOptions options) {
        this.options = options;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public ChatRequestSpec getRequestSpec() {
        return requestSpec;
    }

    public void setRequestSpec(ChatRequestSpec requestSpec) {
        this.requestSpec = requestSpec;
    }
}
