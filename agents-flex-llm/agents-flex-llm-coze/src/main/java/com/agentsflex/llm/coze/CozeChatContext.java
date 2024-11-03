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
package com.agentsflex.llm.coze;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.message.AiMessage;

import java.util.Map;

/**
 * @author yulsh
 */
public class CozeChatContext extends ChatContext {

    private String id;
    private String conversationId;

    private String botId;

    private String status;

    private long createdAt;

    private Map lastError;

    private Map usage;

    private AiMessage message;

    private String response;


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public Map getLastError() {
        return lastError;
    }

    public void setLastError(Map lastError) {
        this.lastError = lastError;
    }

    public Map getUsage() {
        return usage;
    }

    public void setUsage(Map usage) {
        this.usage = usage;
    }

    public void setMessage(AiMessage message) {
        this.message = message;
    }

    public AiMessage getMessage() {
        return message;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }
}
