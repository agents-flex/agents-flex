package com.agentsflex.spring.boot.llm.chatglm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author 王帅
 * @since 2024-04-10
 */
@ConfigurationProperties(prefix = "agents-flex.llm.chatglm")
public class ChatglmProperties {

    private String model = "glm-4";
    private String endpoint = "https://open.bigmodel.cn";
    private String apiKey;
    private String apiSecret;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

}
