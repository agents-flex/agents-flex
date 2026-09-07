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

import com.agentsflex.core.model.chat.*;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.*;

/** Reuses HTTP transport while providing Responses-specific terminal and streaming parsing. */
public class ResponsesChatClient extends OpenAIChatClient {
    public ResponsesChatClient(BaseChatModel<?> model) { super(model); }

    @Override
    protected AiMessageResponse parseResponse(String raw, ChatContext context) {
        return new ResponsesResponseParser().parse(raw, context);
    }

    @Override
    public void chatStream(String body, StreamResponseListener listener) {
        ChatContext context = ChatContextHolder.currentContext();
        ChatRequestSpec spec = context.getRequestSpec();
        StreamClient transport = getStreamClient();
        ResponsesStreamListener bridge = new ResponsesStreamListener(chatModel, context, transport, listener);
        try {
            bridge.onStart(transport);
            if (bridge.isTerminal()) return;
            transport.start(spec.getUrl(), spec.getHeaders(), body, bridge, chatModel.getConfig());
        } catch (Exception error) {
            bridge.onFailure(transport, error);
        }
    }
}
