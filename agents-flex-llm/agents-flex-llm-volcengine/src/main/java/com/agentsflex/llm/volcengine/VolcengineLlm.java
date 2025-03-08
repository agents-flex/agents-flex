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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.BaseLlm;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.impl.SseClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class VolcengineLlm extends BaseLlm<VolcengineLlmConfig> {

    private final HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = VolcengineLlmUtil.getAiMessageParser(false);
    public AiMessageParser streamMessageParser = VolcengineLlmUtil.getAiMessageParser(true);

    private Map<String, String> buildHeader() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    public VolcengineLlm(VolcengineLlmConfig config) {
        super(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = buildHeader();
        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = VolcengineLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String defaultChatApi = config.getDefaultChatApi();
        String response = httpClient.post(endpoint + defaultChatApi, headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessageResponse messageResponse  = new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }

        return messageResponse;
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = buildHeader();

        String payload = VolcengineLlmUtil.promptToPayload(prompt, config, options, true);
        String endpoint = config.getEndpoint();
        String defaultChatApi = config.getDefaultChatApi();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, streamMessageParser);
        llmClient.start(endpoint + defaultChatApi, headers, payload, clientListener, config);
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        return null;
    }
}
