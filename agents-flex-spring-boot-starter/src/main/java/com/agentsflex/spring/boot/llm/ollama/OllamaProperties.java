package com.agentsflex.spring.boot.llm.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author hustlelr
 * @since 2025-02-11
 */
@ConfigurationProperties(prefix = "agents-flex.llm.ollama")
public class OllamaProperties {

    private String model;
    private String endpoint = "http://localhost:11434/";
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
