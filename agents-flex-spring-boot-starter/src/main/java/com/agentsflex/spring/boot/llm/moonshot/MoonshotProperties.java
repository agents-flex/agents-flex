package com.agentsflex.spring.boot.llm.moonshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author lidong
 * @since 2024-06-25
 */
@ConfigurationProperties(prefix = "agents-flex.llm.moonshot")
public class MoonshotProperties {

    private String model = "moonshot-v1-8k";
    private String endpoint = "https://api.moonshot.cn";
    private String apiKey;

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

}
