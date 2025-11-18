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
package com.agentsflex.core.model.client;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.message.AiMessage;

import java.util.HashMap;
import java.util.Map;

public class StreamContext {
    private ChatModel chatModel;
    private StreamClient client;
    private final Map<String, Object> params = new HashMap<>();

    public StreamContext() {
    }

    public StreamContext(ChatModel chatModel, StreamClient client) {
        this.chatModel = chatModel;
        this.client = client;
    }

    public ChatModel getLlm() {
        return chatModel;
    }

    public void setLlm(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public StreamClient getClient() {
        return client;
    }

    public void setClient(StreamClient client) {
        this.client = client;
    }

    public void addLastAiMessage(AiMessage aiMessageContent) {
        addParam("lastAiMessage", aiMessageContent);
    }

    public AiMessage getLastAiMessage() {
        return getParam("lastAiMessage");
    }

    public StreamContext addParam(String key, Object value) {
        params.put(key, value);
        return this;
    }

    public <T> T getParam(String key) {
        return (T) params.get(key);
    }
}
