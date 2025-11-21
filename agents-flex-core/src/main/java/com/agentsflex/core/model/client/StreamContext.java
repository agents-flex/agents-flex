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

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;

public class StreamContext {
    private final ChatModel chatModel;
    private final ChatContext chatContext;
    private final StreamClient client;
    private AiMessage aiMessage;
    private Throwable throwable;


    public StreamContext(ChatModel chatModel, ChatContext context, StreamClient client) {
        this.chatModel = chatModel;
        this.chatContext = context;
        this.client = client;
    }


    public ChatModel getChatModel() {
        return chatModel;
    }

    public ChatContext getChatContext() {
        return chatContext;
    }

    public StreamClient getClient() {
        return client;
    }

    public AiMessage getAiMessage() {
        return aiMessage;
    }

    public void setAiMessage(AiMessage aiMessage) {
        this.aiMessage = aiMessage;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public boolean isError() {
        return throwable != null;
    }
}
