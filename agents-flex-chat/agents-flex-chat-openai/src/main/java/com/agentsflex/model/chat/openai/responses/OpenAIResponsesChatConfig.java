package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.util.StringUtil;

import java.util.List;

/** Configuration for OpenAI Responses-compatible endpoints. */
public class OpenAIResponsesChatConfig extends BaseChatConfig {
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com";
    private static final String DEFAULT_REQUEST_PATH = "/v1/responses";
    private static final String DEFAULT_MODEL = "gpt-5.6-sol";

    public OpenAIResponsesChatConfig() {
        setProvider(DEFAULT_PROVIDER);
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_REQUEST_PATH);
        setModel(DEFAULT_MODEL);
    }

    public final OpenAIResponsesChatModel toChatModel() {
        return new OpenAIResponsesChatModel(this);
    }

    public final OpenAIResponsesChatModel toChatModel(List<ChatInterceptor> interceptors) {
        return new OpenAIResponsesChatModel(this, interceptors);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final OpenAIResponsesChatConfig config = new OpenAIResponsesChatConfig();

        public Builder apiKey(String value) { config.setApiKey(value); return this; }
        public Builder provider(String value) { config.setProvider(value); return this; }
        public Builder endpoint(String value) { config.setEndpoint(value); return this; }
        public Builder requestPath(String value) { config.setRequestPath(value); return this; }
        public Builder model(String value) { config.setModel(value); return this; }
        public Builder supportImage(Boolean value) { config.setSupportImage(value); return this; }
        public Builder supportTool(Boolean value) { config.setSupportTool(value); return this; }
        public Builder supportToolMessage(Boolean value) { config.setSupportToolMessage(value); return this; }
        public Builder retryEnabled(boolean value) { config.setRetryEnabled(value); return this; }
        public Builder retryCount(int value) { config.setRetryCount(value); return this; }
        public Builder retryInitialDelayMs(int value) { config.setRetryInitialDelayMs(value); return this; }
        public Builder logEnabled(boolean value) { config.setLogEnabled(value); return this; }
        public OpenAIResponsesChatConfig build() {
            if (StringUtil.noText(config.getApiKey())) {
                throw new IllegalStateException("apiKey must be set for OpenAIResponsesChatConfig");
            }
            return config;
        }
        public OpenAIResponsesChatModel buildModel() { return new OpenAIResponsesChatModel(build()); }
        public OpenAIResponsesChatModel buildModel(List<ChatInterceptor> interceptors) {
            return new OpenAIResponsesChatModel(build(), interceptors);
        }
    }
}
