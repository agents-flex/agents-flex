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
package com.agentsflex.core.prompt;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;


public class SimplePrompt extends Prompt {

    protected SystemMessage systemMessage;
    protected UserMessage userMessage;
    protected List<ToolMessage> toolMessages;

    public SimplePrompt() {
    }

    public SimplePrompt(String content) {
        this.userMessage = new UserMessage(content);
    }

    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    public UserMessage getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(UserMessage userMessage) {
        this.userMessage = userMessage;
    }

    public List<ToolMessage> getToolMessages() {
        return toolMessages;
    }

    public void setToolMessages(List<ToolMessage> toolMessages) {
        this.toolMessages = toolMessages;
    }

    @Override
    public List<Message> toMessages() {
        List<Message> messages = new ArrayList<>(2);
        if (systemMessage != null) {
            messages.add(systemMessage);
        }
        messages.add(userMessage);

        if (toolMessages != null) {
            messages.addAll(toolMessages);
        }
        return messages;
    }
}
