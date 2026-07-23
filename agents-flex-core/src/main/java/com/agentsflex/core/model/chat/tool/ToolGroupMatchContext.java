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
package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.prompt.Prompt;

import java.util.Collections;
import java.util.List;

/** Read-only input available to a {@link ToolGroupMatcher}. */
public class ToolGroupMatchContext {

    private final Prompt prompt;
    private final List<Message> messages;
    private final UserMessage lastUserMessage;

    public ToolGroupMatchContext(Prompt prompt, List<Message> messages, UserMessage lastUserMessage) {
        this.prompt = prompt;
        this.messages = messages == null ? Collections.emptyList() : Collections.unmodifiableList(messages);
        this.lastUserMessage = lastUserMessage;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public UserMessage getLastUserMessage() {
        return lastUserMessage;
    }

    public String getUserPrompt() {
        return lastUserMessage == null ? null : lastUserMessage.getTextContent();
    }
}
