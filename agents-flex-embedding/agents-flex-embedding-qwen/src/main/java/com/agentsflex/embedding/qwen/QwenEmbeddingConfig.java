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
