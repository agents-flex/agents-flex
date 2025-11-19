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
package com.agentsflex.llm.volcengine;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.log.ChatMessageLogUtil;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class VolcengineChatModel extends BaseChatModel<VolcengineChatConfig> {

    private final HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = VolcengineUtil.getAiMessageParser(false);
    public AiMessageParser streamMessageParser = VolcengineUtil.getAiMessageParser(true);

    private Map<String, String> buildHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    public VolcengineChatModel(VolcengineChatConfig config) {
        super(config);
    }

    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = buildHeader();
        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = VolcengineUtil.promptToPayload(prompt, config, options, false);
        ChatMessageLogUtil.logRequest(config, payload);
        String response = httpClient.post(config.getFullUrl(), headers, payload);
        ChatMessageLogUtil.logResponse(config, response);

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));

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
        Map<String, String> headers = buildHeader();

        String payload = VolcengineUtil.promptToPayload(prompt, config, options, true);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, streamMessageParser);
        streamClient.start(config.getFullUrl(), headers, payload, clientListener, config);
    }
}
