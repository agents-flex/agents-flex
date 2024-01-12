package com.agentsflex.llm.qwen;

import com.agentsflex.llm.LlmConfig;

public class QwenLlmConfig extends LlmConfig {

    private String apiKey;
    private String model;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
