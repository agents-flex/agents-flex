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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.prompt.Prompt;

import java.util.Map;

public class ChatContext {

    Prompt prompt;
    ChatConfig config;
    ChatOptions options;
    ChatRequestSpec requestSpec;
    Object conversationId;
    Object accountId;
    Map<String, Object> attributes;

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public ChatConfig getConfig() {
        return config;
    }

    public void setConfig(ChatConfig config) {
        this.config = config;
    }

    public ChatOptions getOptions() {
        return options;
    }

    public void setOptions(ChatOptions options) {
        this.options = options;
    }


    public ChatRequestSpec getRequestSpec() {
        return requestSpec;
    }

    public void setRequestSpec(ChatRequestSpec requestSpec) {
        this.requestSpec = requestSpec;
    }

    public Object getConversationId() {
        return conversationId;
    }

    public void setConversationId(Object conversationId) {
        this.conversationId = conversationId;
    }

    public Object getAccountId() {
        return accountId;
    }

    public void setAccountId(Object accountId) {
        this.accountId = accountId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String key) {
        if (attributes == null) {
            return null;
        }
        return attributes.get(key);
    }

    public void addAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new java.util.HashMap<>();
        }
        attributes.put(key, value);
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }


    @Override
    public String toString() {
        return "ChatContext{" +
            "prompt=" + prompt +
            ", config=" + config +
            ", options=" + options +
            ", requestSpec=" + requestSpec +
            ", conversationId=" + conversationId +
            ", accountId=" + accountId +
            ", attributes=" + attributes +
            '}';
    }
}
