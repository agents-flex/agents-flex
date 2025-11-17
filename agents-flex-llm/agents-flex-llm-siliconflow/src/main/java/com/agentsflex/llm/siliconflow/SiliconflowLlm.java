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
package com.agentsflex.llm.siliconflow;

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
import com.agentsflex.core.util.JSONUtil;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author daxian1218
 */
public class SiliconflowLlm extends BaseLlm<SiliconflowConfig> {

    private final Map<String, String> headers = new HashMap<>();
    private final HttpClient httpClient = new HttpClient();
    private final AiMessageParser aiMessageParser = SiliconflowLlmUtil.getAiMessageParser(false);
    private final AiMessageParser streamMessageParser = SiliconflowLlmUtil.getAiMessageParser(true);

    public SiliconflowLlm(SiliconflowConfig config) {
        super(config);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
    }

    public static SiliconflowLlm of(String apiKey) {
        SiliconflowConfig config = new SiliconflowConfig();
        config.setApiKey(apiKey);
        return new SiliconflowLlm(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = SiliconflowLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/chat/completions", headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        if (response.startsWith("{")) {
            JSONObject jsonObject = JSON.parseObject(response);
            Integer code = jsonObject.getInteger("code");
            if (code != null) {
                return AiMessageResponse.error(prompt, response, jsonObject.getString("message"));
            }


            return new AiMessageResponse(prompt, response, aiMessageParser.parse(jsonObject));
        }

        return AiMessageResponse.error(prompt, response, response);
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener streamResponseListener, ChatOptions chatOptions) {
        LlmClient llmClient = new SseClient();
        String payload = SiliconflowLlmUtil.promptToPayload(prompt, config, chatOptions, true);
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
        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        String payload = SiliconflowLlmUtil.documentToPayload(document, config, embeddingOptions);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/embeddings", headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        if (response.startsWith("{")) {
            JSONObject jsonObject = JSON.parseObject(response);
            Integer code = jsonObject.getInteger("code");
            if (code != null) {
                return null;
            }
            VectorData vectorData = new VectorData();
            vectorData.setVector(JSONUtil.readDoubleArray(JSON.parseObject(response), "$.data[0].embedding"));

            return vectorData;
        }

        return null;
    }
}
