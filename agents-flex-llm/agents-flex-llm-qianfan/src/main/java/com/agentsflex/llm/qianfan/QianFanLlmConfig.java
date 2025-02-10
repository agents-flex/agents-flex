package com.agentsflex.llm.qianfan;

import com.agentsflex.core.llm.LlmConfig;

public class QianFanLlmConfig extends LlmConfig {
    private static final String DEFAULT_MODEL = "ernie-3.5-8k";
    private static final String DEFAULT_EMBEDDING_MODEL = "embedding-v1";
    private static final String DEFAULT_ENDPOINT = "https://qianfan.baidubce.com/v2";
    private String embeddingModel = DEFAULT_EMBEDDING_MODEL;

    public QianFanLlmConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setModel(DEFAULT_MODEL);
    }

    public QianFanLlmConfig(String apikey) {
        setEndpoint(DEFAULT_ENDPOINT);
        setModel(DEFAULT_MODEL);
        super.setApiKey(apikey);
    }



    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

}
