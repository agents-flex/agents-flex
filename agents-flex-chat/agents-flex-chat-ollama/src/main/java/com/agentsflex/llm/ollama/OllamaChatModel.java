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
package com.agentsflex.llm.ollama;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.impl.DnjsonClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class OllamaChatModel extends BaseChatModel<OllamaChatConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = OllamaLlmUtil.getAiMessageParser();


    public OllamaChatModel(OllamaChatConfig config) {
        super(config);
    }


    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options, false);
        String response = httpClient.post(config.getFullUrl(), headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        String error = jsonObject.getString("error");

        AiMessage aiMessage = aiMessageParser.parse(jsonObject);
        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessage);

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error);
        }

        return messageResponse;
    }


    @Override
    public void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        DnjsonClient dnjsonStreamClient = new DnjsonClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options, true);

        StreamClientListener clientListener = new BaseStreamClientListener(this, dnjsonStreamClient, listener, prompt, aiMessageParser);
        dnjsonStreamClient.start(config.getFullUrl(), headers, payload, clientListener, config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
