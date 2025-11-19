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
package com.agentsflex.llm.openai;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.*;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAIChatModel extends BaseChatModel<OpenAIChatConfig> {

    private HttpClient httpClient = new HttpClient();
    private AiMessageParser aiMessageParser = OpenAILlmUtil.getAiMessageParser(false);
    private AiMessageParser streamMessageParser = OpenAILlmUtil.getAiMessageParser(true);

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public AiMessageParser getAiMessageParser() {
        return aiMessageParser;
    }

    public void setAiMessageParser(AiMessageParser aiMessageParser) {
        this.aiMessageParser = aiMessageParser;
    }

    public AiMessageParser getStreamMessageParser() {
        return streamMessageParser;
    }

    public void setStreamMessageParser(AiMessageParser streamMessageParser) {
        this.streamMessageParser = streamMessageParser;
    }

    public static OpenAIChatModel of(String apiKey) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        return new OpenAIChatModel(config);
    }

    public static OpenAIChatModel of(String apiKey, String endpoint) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        config.setEndpoint(endpoint);
        return new OpenAIChatModel(config);
    }

    public OpenAIChatModel(OpenAIChatConfig config) {
        super(config);
    }

    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        // 非流式返回，比如 Qwen3 等必须设置 false，否则自动流式返回了
        if (options.getThinkingEnabled() == null) {
            options.setThinkingEnabled(false);
        }

        String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, false);
        if (config.isDebug()) {
            LogUtil.println(">>>>send payload:" + payload);
        }

        String response = httpClient.post(config.getFullUrl(), headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessage aiMessage = aiMessageParser.parse(jsonObject);
        LocalTokenCounter.computeAndSetLocalTokens(prompt.toMessages(), aiMessage);
        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessage);

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }
        return messageResponse;
    }


    @Override
    public void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        StreamClient streamClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, true);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, streamMessageParser);
        streamClient.start(config.getFullUrl(), headers, payload, clientListener, config);
    }


}
