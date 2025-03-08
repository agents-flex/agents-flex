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
