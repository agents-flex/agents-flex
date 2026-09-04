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
package com.agentsflex.model.chat.llmman;

import com.agentsflex.model.chat.ollama.OllamaChatConfig;

/**
 * Config for <a href="https://github.com/llmmanorg/llmman">llmman</a>, a local model runner
 * serving the Ollama API on port 17434; {@link OllamaChatConfig} with a different provider and port.
 */
public class LlmmanChatConfig extends OllamaChatConfig {

    private static final String DEFAULT_PROVIDER = "llmman";
    private static final String DEFAULT_ENDPOINT = "http://localhost:17434";

    public LlmmanChatConfig() {
        setProvider(DEFAULT_PROVIDER);
        setEndpoint(DEFAULT_ENDPOINT);
    }

}
