/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm;

import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.Message;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.SimplePrompt;

public interface Llm extends Embeddings {

    default String chat(String prompt) {
        ChatResponse<?> chat = chat(new SimplePrompt(prompt));
        return chat != null ? chat.getMessage().getContent() : null;
    }

    <T extends ChatResponse<M>, M extends Message> T chat(Prompt<M> prompt);

    default void chatAsync(String prompt, ChatListener<MessageResponse, AiMessage> listener) {
        this.chatAsync(new SimplePrompt(prompt), listener);
    }

    <T extends ChatResponse<M>, M extends Message> void chatAsync(Prompt<M> prompt, ChatListener<T, M> listener);

}
