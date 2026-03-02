/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.rerank.gitee;

import com.agentsflex.rerank.DefaultRerankModelConfig;

public class GiteeRerankModelConfig extends DefaultRerankModelConfig {

    private static final String DEFAULT_ENDPOINT = "https://ai.gitee.com";
    private static final String DEFAULT_BASE_PATH = "/v1/rerank";
    private static final String DEFAULT_MODEL = "Qwen3-Reranker-8B";

    public GiteeRerankModelConfig() {
        super();
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_BASE_PATH);
        setModel(DEFAULT_MODEL);
    }
}
