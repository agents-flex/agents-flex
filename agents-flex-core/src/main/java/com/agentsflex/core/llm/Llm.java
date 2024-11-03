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
package com.agentsflex.core.llm;

import com.agentsflex.core.llm.embedding.EmbeddingModel;
import com.agentsflex.core.llm.exception.LlmException;
import com.agentsflex.core.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.TextPrompt;

public interface Llm extends EmbeddingModel {

    default String chat(String prompt) {
        return chat(prompt, ChatOptions.DEFAULT);
    }

    default String chat(String prompt, ChatOptions options) {
        AbstractBaseMessageResponse<AiMessage> response = chat(new TextPrompt(prompt), options);
        if (response != null && response.isError()) throw new LlmException(response.getErrorMessage());
        return response != null && response.getMessage() != null ? response.getMessage().getContent() : null;
    }

    default AiMessageResponse chat(Prompt prompt) {
        return chat(prompt, ChatOptions.DEFAULT);
    }

    AiMessageResponse chat(Prompt prompt, ChatOptions options);

    default void chatStream(String prompt, StreamResponseListener listener) {
        this.chatStream(new TextPrompt(prompt), listener, ChatOptions.DEFAULT);
    }

    default void chatStream(String prompt, StreamResponseListener listener, ChatOptions options) {
        this.chatStream(new TextPrompt(prompt), listener, options);
    }

    //chatStream
    default void chatStream(Prompt prompt, StreamResponseListener listener) {
        this.chatStream(prompt, listener, ChatOptions.DEFAULT);
    }

    void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options);

}
