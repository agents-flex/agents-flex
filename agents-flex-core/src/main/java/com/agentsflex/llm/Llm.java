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

import com.agentsflex.llm.embedding.EmbeddingModel;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.Message;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.SimplePrompt;

public interface Llm extends EmbeddingModel {

    default String chat(String prompt) {
        MessageResponse<AiMessage> chat = chat(new SimplePrompt(prompt), ChatOptions.DEFAULT);
        return chat != null && chat.getMessage() != null ? chat.getMessage().getContent() : null;
    }

    default String chat(String prompt, ChatOptions options) {
        MessageResponse<AiMessage> chat = chat(new SimplePrompt(prompt), options);
        return chat != null && chat.getMessage() != null ? chat.getMessage().getContent() : null;
    }

    default <R extends MessageResponse<M>, M extends Message> R chat(Prompt<M> prompt) {
        return chat(prompt, ChatOptions.DEFAULT);
    }

    <R extends MessageResponse<M>, M extends Message> R chat(Prompt<M> prompt, ChatOptions options);

    default void chatStream(String prompt, StreamResponseListener<AiMessageResponse, AiMessage> listener) {
        this.chatStream(new SimplePrompt(prompt), listener, ChatOptions.DEFAULT);
    }

    default void chatStream(String prompt, StreamResponseListener<AiMessageResponse, AiMessage> listener, ChatOptions options) {
        this.chatStream(new SimplePrompt(prompt), listener, options);
    }

    //chatStream
    default <R extends MessageResponse<M>, M extends Message> void chatStream(Prompt<M> prompt, StreamResponseListener<R, M> listener) {
        this.chatStream(prompt, listener, ChatOptions.DEFAULT);
    }

    <R extends MessageResponse<M>, M extends Message> void chatStream(Prompt<M> prompt, StreamResponseListener<R, M> listener, ChatOptions options);

}
