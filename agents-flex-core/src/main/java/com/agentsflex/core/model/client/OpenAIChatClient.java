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

    protected AiMessageParser aiMessageParser;

    public OpenAIChatClient(
        BaseChatModel<?> chatModel,
        ChatContext context) {
        super(chatModel, context);
    }

    public AiMessageParser getAiMessageParser() {
        if (aiMessageParser == null) {
            aiMessageParser = DefaultAiMessageParser.getOpenAIMessageParser();
        }
        return aiMessageParser;
    }

    public void setAiMessageParser(AiMessageParser aiMessageParser) {
        this.aiMessageParser = aiMessageParser;
    }

    @Override
    public AiMessageResponse chat() {
        HttpClient httpClient = new HttpClient();
        ChatRequestInfo requestInfo = context.getRequestInfo();
        String response = httpClient.post(requestInfo.getUrl(), requestInfo.getHeaders(), requestInfo.getBody());

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(context, response, "no content for response.");
        }
        return parseResponse(response);
    }

    @Override
    public void chatStream(StreamResponseListener listener) {
        StreamClient streamClient = new SseClient();
        StreamClientListener clientListener = new BaseStreamClientListener(
            chatModel,
            context,
            streamClient,
            listener,
            getAiMessageParser()
        );

        ChatRequestInfo requestInfo = context.getRequestInfo();
        streamClient.start(requestInfo.getUrl(), requestInfo.getHeaders(), requestInfo.getBody()
            , clientListener, chatModel.getConfig());
    }


    protected AiMessageResponse parseResponse(String response) {
        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessage aiMessage = getAiMessageParser().parse(jsonObject, context);
        LocalTokenCounter.computeAndSetLocalTokens(context.getPrompt().getMessages(), aiMessage);
        AiMessageResponse messageResponse = new AiMessageResponse(context, response, aiMessage);

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }
        return messageResponse;
    }
}
