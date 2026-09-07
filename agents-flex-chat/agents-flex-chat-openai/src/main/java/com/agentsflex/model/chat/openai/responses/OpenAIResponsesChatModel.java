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
package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatInterceptor;
import java.util.List;

/** Opt-in Responses protocol; existing OpenAIChatModel continues to use Chat Completions. */
public class OpenAIResponsesChatModel extends BaseChatModel<OpenAIResponsesChatConfig> {
    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config) {
        this(config, null);
    }

    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config, List<ChatInterceptor> interceptors) {
        super(config, interceptors);
        setChatRequestSpecBuilder(new ResponsesRequestSpecBuilder());
        setChatClient(new ResponsesChatClient(this));
    }
}
