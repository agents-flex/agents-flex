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
package com.agentsflex.llm.tencent;

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

import java.util.Map;

public class TencentChatModel extends BaseChatModel<TencentChatConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = TencentChatUtil.getAiMessageParser(false);
    public AiMessageParser aiStreamMessageParser = TencentChatUtil.getAiMessageParser(true);

    public TencentChatModel(TencentChatConfig config) {
        super(config);
    }


    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        String payload = TencentChatUtil.promptToPayload(prompt, config, false, options);
        Map<String, String> headers = TencentChatUtil.createAuthorizationToken(config, "ChatCompletions", payload);
        String response = httpClient.post(config.getEndpoint(), headers, payload);
        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }
        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("Response").getJSONObject("Error");
        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));
        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("Message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("Code"));
        }
        return messageResponse;
    }


    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        StreamClient streamClient = new SseClient();
        String payload = TencentChatUtil.promptToPayload(prompt, config, true, options);
        Map<String, String> headers = TencentChatUtil.createAuthorizationToken(config, "ChatCompletions", payload);
        StreamClientListener clientListener = new BaseStreamClientListener(this, streamClient, listener, prompt, aiStreamMessageParser);
        streamClient.start(config.getEndpoint(), headers, payload, clientListener, config);
    }

}
