package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.client.ChatRequestInfo;
import com.agentsflex.core.prompt.Prompt;

public class ChatContext {

    ChatConfig config;
    ChatOptions options;
    Prompt prompt;
    ChatRequestInfo requestInfo;

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

    public ChatRequestInfo getRequestInfo() {
        return requestInfo;
    }

    public void setRequestInfo(ChatRequestInfo requestInfo) {
        this.requestInfo = requestInfo;
    }
}
