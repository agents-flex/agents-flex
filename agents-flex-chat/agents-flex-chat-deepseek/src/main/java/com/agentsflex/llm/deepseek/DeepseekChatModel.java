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
package com.agentsflex.llm.deepseek;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.BaseStreamClientListener;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.impl.SseClient;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekChatModel extends BaseChatModel<DeepseekConfig> {

    private final Map<String, String> headers = new HashMap<>();
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = DeepseekLlmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = DeepseekLlmUtil.getAiMessageParser(true);

    public DeepseekChatModel(DeepseekConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
    }

    public static DeepseekChatModel of(String apiKey) {
        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        return new DeepseekChatModel(config);
    }

    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = DeepseekLlmUtil.promptToPayload(prompt, config, options, false);
        String response = httpClient.post(config.getFullUrl(), headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

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
    public void doChatStream(Prompt prompt, StreamResponseListener streamResponseListener, ChatOptions chatOptions) {
        StreamClient streamClient = new SseClient();
        String payload = DeepseekLlmUtil.promptToPayload(prompt, config, chatOptions, true);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, streamResponseListener, prompt, streamMessageParser);
        streamClient.start(config.getFullUrl(), headers, payload, clientListener, config);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener) {
        chatStream(prompt, streamResponseListener, ChatOptions.DEFAULT);
    }

}
