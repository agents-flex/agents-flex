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
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.DefaultChatMemory;

import java.util.List;

public class HistoriesPrompt extends Prompt<AiMessageResponse> {

    private ChatMemory memory = new DefaultChatMemory();

    public HistoriesPrompt() {
    }

    public HistoriesPrompt(ChatMemory memory) {
        this.memory = memory;
    }

    public void addMessage(Message message) {
        memory.addMessage(message);
    }

    public ChatMemory getMemory() {
        return memory;
    }

    public void setMemory(ChatMemory memory) {
        this.memory = memory;
    }

    @Override
    public List<Message> toMessages() {
        return memory.getMessages();
    }
}
