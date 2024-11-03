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

import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TextPrompt extends Prompt {

    private SystemMessage systemMessage;
    protected String content;

    public TextPrompt() {
    }

    public TextPrompt(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    @Override
    public List<Message> toMessages() {
        if (systemMessage != null) {
            ArrayList<Message> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.add(new HumanMessage(content));
            return messages;
        }
        return Collections.singletonList(new HumanMessage(content));
    }

    @Override
    public String toString() {
        return "TextPrompt{" +
            "content='" + content + '\'' +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
