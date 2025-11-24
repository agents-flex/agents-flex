/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.embedding.openai;

import com.agentsflex.core.model.config.BaseModelConfig;

public class OpenAIEmbeddingConfig extends BaseModelConfig {

    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-ada-002";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com";
    private static final String DEFAULT_REQUEST_PATH = "/v1/embeddings";


    public OpenAIEmbeddingConfig() {
        super();
        this.setModel(DEFAULT_EMBEDDING_MODEL);
        this.setEndpoint(DEFAULT_ENDPOINT);
        this.setRequestPath(DEFAULT_REQUEST_PATH);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model = DEFAULT_EMBEDDING_MODEL;
        private String endpoint = DEFAULT_ENDPOINT;
        private String requestPath = DEFAULT_REQUEST_PATH;
        private String apiKey;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder requestPath(String requestPath) {
            this.requestPath = requestPath;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public OpenAIEmbeddingConfig build() {
            OpenAIEmbeddingConfig config = new OpenAIEmbeddingConfig();
            config.setModel(this.model);
            config.setEndpoint(this.endpoint);
            config.setRequestPath(this.requestPath);
            if (this.apiKey != null) {
                config.setApiKey(this.apiKey);
            }
            return config;
        }
    }
}
