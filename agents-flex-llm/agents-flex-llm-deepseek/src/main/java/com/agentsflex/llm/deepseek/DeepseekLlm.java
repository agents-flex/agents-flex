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
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekLlm extends BaseLlm<DeepseekConfig> {

    private final Map<String, String> headers = new HashMap<>();
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = DeepseekLlmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = DeepseekLlmUtil.getAiMessageParser(true);

    public DeepseekLlm(DeepseekConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
    }

    public static DeepseekLlm of(String apiKey) {
        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        return new DeepseekLlm(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = DeepseekLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/chat/completions", headers, payload);

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
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener, ChatOptions chatOptions) {
        LlmClient llmClient = new SseClient();
        String payload = DeepseekLlmUtil.promptToPayload(prompt, config, chatOptions, true);
        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, streamResponseListener, prompt, streamMessageParser);
        llmClient.start(endpoint + "/chat/completions", headers, payload, clientListener, config);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener) {
        chatStream(prompt, streamResponseListener, ChatOptions.DEFAULT);
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions embeddingOptions) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
