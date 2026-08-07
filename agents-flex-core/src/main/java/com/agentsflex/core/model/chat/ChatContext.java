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

/**
 * 一次 ChatModel 调用的可变运行上下文。
 *
 * <p>业务关联 ID 和 attributes 不在本类重复保存，而是通过当前请求独享的 ChatOptions 读写。
 * 因此拦截器通过 ChatContext 或 ChatOptions 修改上下文时操作的是同一份数据。</p>
 */
public class ChatContext {

    Prompt prompt;
    BaseChatConfig config;
    ChatOptions options;
    ChatRequestSpec requestSpec;

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public BaseChatConfig getConfig() {
        return config;
    }

    public void setConfig(BaseChatConfig config) {
        this.config = config;
    }

    public ChatOptions getOptions() {
        return options;
    }

    public void setOptions(ChatOptions options) {
        if (this.options != null && options != null) {
            if (options.getContextBotId() == null) {
                options.setContextBotId(this.options.getContextBotId());
            }
            if (options.getContextConversationId() == null) {
                options.setContextConversationId(this.options.getContextConversationId());
            }
            if (options.getContextAccountId() == null) {
                options.setContextAccountId(this.options.getContextAccountId());
            }
            if (options.getContextTurnId() == null) {
                options.setContextTurnId(this.options.getContextTurnId());
            }
            if (options.getContextAttributes() == null) {
                options.setContextAttributes(this.options.getContextAttributes());
            }
        }
        this.options = options;
    }


    public ChatRequestSpec getRequestSpec() {
        return requestSpec;
    }

    public void setRequestSpec(ChatRequestSpec requestSpec) {
        this.requestSpec = requestSpec;
    }

    public Object getBotId() {
        return options == null ? null : options.getContextBotId();
    }

    public void setBotId(Object botId) {
        ensureOptions().setContextBotId(botId);
    }

    public Object getConversationId() {
        return options == null ? null : options.getContextConversationId();
    }

    public void setConversationId(Object conversationId) {
        ensureOptions().setContextConversationId(conversationId);
    }

    public Object getAccountId() {
        return options == null ? null : options.getContextAccountId();
    }

    public void setAccountId(Object accountId) {
        ensureOptions().setContextAccountId(accountId);
    }

    public Object getTurnId() {
        return options == null ? null : options.getContextTurnId();
    }

    public void setTurnId(Object turnId) {
        ensureOptions().setContextTurnId(turnId);
    }

    public Map<String, Object> getAttributes() {
        return options == null ? null : options.getContextAttributes();
    }

    public Object getAttribute(String key) {
        Map<String, Object> attributes = getAttributes();
        if (attributes == null) {
            return null;
        }
        return attributes.get(key);
    }

    public void addAttribute(String key, Object value) {
        Map<String, Object> attributes = getAttributes();
        if (attributes == null) {
            ensureOptions().setContextAttributes(
                new java.util.concurrent.ConcurrentHashMap<String, Object>());
            attributes = getAttributes();
        }
        attributes.put(key, value);
    }

    public void setAttributes(Map<String, Object> attributes) {
        ensureOptions().setContextAttributes(attributes);
    }

    private ChatOptions ensureOptions() {
        if (options == null) {
            options = new ChatOptions();
        }
        return options;
    }


    @Override
    public String toString() {
        return "ChatContext{" +
            "prompt=" + prompt +
            ", config=" + config +
            ", options=" + options +
            ", requestSpec=" + requestSpec +
            ", botId=" + getBotId() +
            ", conversationId=" + getConversationId() +
            ", accountId=" + getAccountId() +
            ", turnId=" + getTurnId() +
            ", attributes=" + getAttributes() +
            '}';
    }
}
