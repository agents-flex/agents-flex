package com.agentsflex.embedding.ollama;

import com.agentsflex.core.model.config.BaseModelConfig;

public class OllamaEmbeddingConfig extends BaseModelConfig {

    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-ada-002";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com";
    private static final String DEFAULT_REQUEST_PATH = "/v1/embeddings";


    public OllamaEmbeddingConfig() {
        super();
        this.setModel(DEFAULT_EMBEDDING_MODEL);
        this.setEndpoint(DEFAULT_ENDPOINT);
        this.setRequestPath(DEFAULT_REQUEST_PATH);
    }

}
