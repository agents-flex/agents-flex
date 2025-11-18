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
package com.agentsflex.llm.qwen;

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

public class QwenChatModel extends BaseChatModel<QwenChatConfig> {

    private final HttpClient httpClient = new HttpClient();

    public AiMessageParser aiMessageParser = QwenLlmUtil.getAiMessageParser(false);
    public AiMessageParser streamMessageParser = QwenLlmUtil.getAiMessageParser(true);


    public QwenChatModel(QwenChatConfig config) {
        super(config);
    }


    @Override
    public AiMessageResponse doChat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        //非流式返回，需要设置为 false
        options.setEnableThinking(false);

        String payload = QwenLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/compatible-mode/v1/chat/completions", headers, payload);

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
    public void doChatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        StreamClient streamClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        headers.put("X-DashScope-SSE", "enable"); //stream

        String payload = QwenLlmUtil.promptToPayload(prompt, config, options, true);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, streamMessageParser);

        String endpoint = config.getEndpoint();
        streamClient.start(endpoint + "/compatible-mode/v1/chat/completions", headers, payload, clientListener, config);
    }




}
