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

import com.agentsflex.core.llm.ChatOptions;
import java.util.Map;

/**
 * @author yulsh
 */
public class CozeChatOptions extends ChatOptions {

    private String botId;

    private String conversationId;

    private String userId;

    private boolean stream;

    private Map<String, String> customVariables;

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public boolean isStream() {
        return stream;
    }

    public void setCustomVariables(Map<String, String> customVariables) {
        this.customVariables = customVariables;
    }

    public Map<String, String> getCustomVariables() {
        return customVariables;
    }
}
