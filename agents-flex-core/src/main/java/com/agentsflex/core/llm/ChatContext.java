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
package com.agentsflex.core.llm;

import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.message.AiMessage;

import java.util.HashMap;
import java.util.Map;

public class ChatContext {
    private Llm llm;
    private LlmClient client;
    private final Map<String, Object> params = new HashMap<>();

    public ChatContext() {
    }

    public ChatContext(Llm llm, LlmClient client) {
        this.llm = llm;
        this.client = client;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public LlmClient getClient() {
        return client;
    }

    public void setClient(LlmClient client) {
        this.client = client;
    }

    public void addLastAiMessage(AiMessage aiMessageContent) {
        addParam("lastAiMessage", aiMessageContent);
    }

    public AiMessage getLastAiMessage() {
        return getParam("lastAiMessage");
    }

    public ChatContext addParam(String key, Object value) {
        params.put(key, value);
        return this;
    }

    public <T> T getParam(String key) {
        return (T) params.get(key);
    }
}
