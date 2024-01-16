package com.agentsflex.llm.openai;

import com.agentsflex.llm.LlmConfig;

public class OpenAiLlmConfig extends LlmConfig {

    private String apiKey;
    private String model = "gpt-3.5-turbo";

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
