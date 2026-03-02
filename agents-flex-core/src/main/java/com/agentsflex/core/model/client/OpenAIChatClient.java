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
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.Retryer;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;

/**
 * OpenAI 专用聊天客户端。
 * <p>
 * 封装了 HTTP 同步调用和 SSE 流式调用的具体实现。
 */
public class OpenAIChatClient extends ChatClient {

    protected HttpClient httpClient;
    protected AiMessageParser<JSONObject> aiMessageParser;

    public OpenAIChatClient(BaseChatModel<?> chatModel) {
        super(chatModel);
    }

    public HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new HttpClient();
        }
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public StreamClient getStreamClient() {
        // SseClient 默认实现是每次请求需要新建一个 SseClient 对象，方便进行 stop 调用
        return new SseClient();
    }


    public AiMessageParser<JSONObject> getAiMessageParser() {
        if (aiMessageParser == null) {
            aiMessageParser = DefaultAiMessageParser.getOpenAIMessageParser();
        }
        return aiMessageParser;
    }

    public void setAiMessageParser(AiMessageParser<JSONObject> aiMessageParser) {
        this.aiMessageParser = aiMessageParser;
    }


    @Override
    public AiMessageResponse chat() {
        HttpClient httpClient = getHttpClient();
        ChatContext context = ChatContextHolder.currentContext();
        ChatRequestSpec requestSpec = context.getRequestSpec();

        String response = requestSpec.getRetryCount() > 0 ? Retryer.retry(() -> httpClient.post(requestSpec.getUrl(),
            requestSpec.getHeaders(),
            requestSpec.getBody()), requestSpec.getRetryCount(), requestSpec.getRetryInitialDelayMs())
            : httpClient.post(requestSpec.getUrl(), requestSpec.getHeaders(), requestSpec.getBody());

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(context, response, "no content for response.");
        }
        try {
            return parseResponse(response, context);
        } catch (JSONException e) {
            return AiMessageResponse.error(context, response, "invalid json response.");
        }
    }


    protected AiMessageResponse parseResponse(String response, ChatContext context) {
        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessageResponse messageResponse;
        if (error != null && !error.isEmpty()) {
            String message = error.getString("message");
            messageResponse = AiMessageResponse.error(context, response, message);
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        } else {
            AiMessage aiMessage = getAiMessageParser().parse(jsonObject, context);
            LocalTokenCounter.computeAndSetLocalTokens(context.getPrompt().getMessages(), aiMessage);
            messageResponse = new AiMessageResponse(context, response, aiMessage);
        }
        return messageResponse;
    }


    @Override
    public void chatStream(StreamResponseListener listener) {
        StreamClient streamClient = getStreamClient();
        ChatContext context = ChatContextHolder.currentContext();
        StreamClientListener clientListener = new BaseStreamClientListener(
            chatModel,
            context,
            streamClient,
            listener,
            getAiMessageParser()
        );

        ChatRequestSpec requestSpec = context.getRequestSpec();
        if (requestSpec.getRetryCount() > 0) {
            Retryer.retry(() -> streamClient.start(requestSpec.getUrl(), requestSpec.getHeaders(), requestSpec.getBody()
                    , clientListener, chatModel.getConfig())
                , requestSpec.getRetryCount()
                , requestSpec.getRetryInitialDelayMs());
        } else {
            streamClient.start(requestSpec.getUrl(), requestSpec.getHeaders(), requestSpec.getBody()
                , clientListener, chatModel.getConfig());
        }
    }


}
