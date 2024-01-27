package com.agentsflex.llm.zhipu;

import com.agentsflex.llm.LlmConfig;

public class ZhipuLlmConfig extends LlmConfig {

    //f26ca1e9****.JmzpD****
    private String apiKey;
    private String model = "glm-4";

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
