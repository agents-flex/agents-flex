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
package com.agentsflex.llm.gitee;

import com.agentsflex.core.llm.LlmConfig;

public class GiteeAiLlmConfig extends LlmConfig {

    private static final String DEFAULT_MODEL = "Qwen2-7B-Instruct";
    private static final String DEFAULT_EMBEDDING_MODEL = "bge-large-zh-v1.5";
    private static final String DEFAULT_ENDPOINT = "https://ai.gitee.com";

    private String defaultEmbeddingModal = DEFAULT_EMBEDDING_MODEL;

    public String getDefaultEmbeddingModal() {
        return defaultEmbeddingModal;
    }

    public void setDefaultEmbeddingModal(String defaultEmbeddingModal) {
        this.defaultEmbeddingModal = defaultEmbeddingModal;
    }

    public GiteeAiLlmConfig() {
        setEndpoint(DEFAULT_ENDPOINT);
        setModel(DEFAULT_MODEL);
    }

}
