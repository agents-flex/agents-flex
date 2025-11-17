package com.agentsflex.embedding.qwen;

import com.agentsflex.core.model.config.BaseModelConfig;

public class QwenEmbeddingConfig extends BaseModelConfig {

    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v1";
    private static final String DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com";
    private static final String DEFAULT_REQUEST_PATH = "/compatible-mode/v1/embeddings";

    public QwenEmbeddingConfig() {
        super();
        this.setModel(DEFAULT_EMBEDDING_MODEL);
        this.setEndpoint(DEFAULT_ENDPOINT);
        this.setRequestPath(DEFAULT_REQUEST_PATH);
    }

}
