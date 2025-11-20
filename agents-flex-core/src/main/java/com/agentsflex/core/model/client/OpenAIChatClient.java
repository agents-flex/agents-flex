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
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * OpenAI 专用聊天客户端。
 * <p>
 * 封装了 HTTP 同步调用和 SSE 流式调用的具体实现。
 */
public class OpenAIChatClient extends ChatClient {

    private AiMessageParser syncParser;
    private AiMessageParser streamParser;

    public OpenAIChatClient(
        BaseChatModel<?> chatModel,
        ChatContext context) {
        super(chatModel, context);
        this.syncParser = DefaultAiMessageParser.getOpenAIMessageParser(false);
        this.streamParser = DefaultAiMessageParser.getOpenAIMessageParser(true);
    }

    public AiMessageParser getSyncParser() {
        return syncParser;
    }

    public void setSyncParser(AiMessageParser syncParser) {
        this.syncParser = syncParser;
    }

    public AiMessageParser getStreamParser() {
        return streamParser;
    }

    public void setStreamParser(AiMessageParser streamParser) {
        this.streamParser = streamParser;
    }

    @Override
    public AiMessageResponse chat() {
        HttpClient httpClient = new HttpClient();
        String response = httpClient.post(context.getRequestUrl(), context.getRequestHeaders(), context.getRequestBody());

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(context.getPrompt(), response, "no content for response.");
        }

        return parseResponse(response);
    }

    @Override
    public void chatStream(StreamResponseListener listener) {
        StreamClient streamClient = new SseClient();
        StreamClientListener clientListener = new BaseStreamClientListener(
            chatModel,
            streamClient,
            listener,
            context.getPrompt(),
            streamParser
        );
        streamClient.start(context.getRequestUrl(), context.getRequestHeaders(), context.getRequestBody()
            , clientListener, chatModel.getConfig());
    }


    protected AiMessageResponse parseResponse(String response) {
        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessage aiMessage = syncParser.parse(jsonObject);
        LocalTokenCounter.computeAndSetLocalTokens(context.getPrompt().getMessages(), aiMessage);
        AiMessageResponse messageResponse = new AiMessageResponse(context.getPrompt(), response, aiMessage);

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }
        return messageResponse;
    }
}
