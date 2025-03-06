package com.agentsflex.llm.volcengine;

import com.agentsflex.core.llm.LlmConfig;

public class VolcengineLlmConfig extends LlmConfig {
    /*DEFAULT_MODEL作为模型Model ID 作为推理接入点 用于模型调用*/
    private static final String DEFAULT_MODEL = "doubao-1-5-vision-pro-32k-250115";
    /**
     *模型调用api链接地址
     */
    private static final String DEFAULT_ENDPOINT = "https://ark.cn-beijing.volces.com";

    /**
     * api后缀地址
     */
    private final String DEFAULT_CHAT_API = "/api/v3/chat/completions";
    private String defaultChatApi;

    public String getDefaultChatApi() {
        return defaultChatApi;
    }

    public void setDefaultChatApi(String defaultChatApi) {
        if (defaultChatApi == null) {
            throw new IllegalArgumentException("defaultChatApi cannot be null");
        }
        this.defaultChatApi = defaultChatApi;
    }

    private String defaultEmbeddingModel = DEFAULT_MODEL;

    public String getDefaultEmbeddingModel() {
        return defaultEmbeddingModel;
    }

    public void setDefaultEmbeddingModel(String defaultEmbeddingModel) {
        if (defaultEmbeddingModel == null) {
            throw new IllegalArgumentException("defaultEmbeddingModel cannot be null");
        }
        this.defaultEmbeddingModel = defaultEmbeddingModel;

    }

    public VolcengineLlmConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setModel(DEFAULT_MODEL);
        setDefaultChatApi(DEFAULT_CHAT_API);
    }
}
