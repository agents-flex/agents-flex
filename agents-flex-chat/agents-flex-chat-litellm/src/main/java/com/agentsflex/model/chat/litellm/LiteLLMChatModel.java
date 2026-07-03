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
package com.agentsflex.model.chat.litellm;

import com.agentsflex.core.model.chat.OpenAICompatibleChatModel;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.GlobalChatInterceptors;

import java.util.List;

/**
 * LiteLLM chat model provider.
 * <p>
 * LiteLLM is an AI gateway/proxy that provides a unified OpenAI-compatible interface
 * to 100+ LLM providers (OpenAI, Anthropic, Azure, Bedrock, Vertex AI, etc.).
 *
 * @see <a href="https://docs.litellm.ai/">LiteLLM Documentation</a>
 */
public class LiteLLMChatModel extends OpenAICompatibleChatModel<LiteLLMConfig> {

    /**
     * Construct a LiteLLM chat model instance without instance-level interceptors.
     *
     * @param config chat model configuration
     */
    public LiteLLMChatModel(LiteLLMConfig config) {
        super(config);
    }

    /**
     * Construct a LiteLLM chat model instance with instance-level interceptors.
     * <p>
     * Instance-level interceptors are merged with global interceptors
     * (registered via {@link GlobalChatInterceptors}).
     * Execution order: observability interceptors -> global interceptors -> instance interceptors.
     *
     * @param config           chat model configuration
     * @param userInterceptors instance-level interceptor list
     */
    public LiteLLMChatModel(LiteLLMConfig config, List<ChatInterceptor> userInterceptors) {
        super(config, userInterceptors);
    }
}
