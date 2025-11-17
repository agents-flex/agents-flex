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
package com.agentsflex.llm.vllm;

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

/**
 * @author 84396
 */
public class VLlmLlm extends BaseLlm<VLlmLlmConfig> {

    private final HttpClient httpClient = new HttpClient();

    public AiMessageParser aiMessageParser = VLlmLlmUtil.getAiMessageParser(false);
    public AiMessageParser streamMessageParser = VLlmLlmUtil.getAiMessageParser(true);


    public VLlmLlm(VLlmLlmConfig config) {
        super(config);
    }


    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        String payload = VLlmLlmUtil.promptToPayload(prompt, config, options, false);
        String endpoint = config.getEndpoint();
        String response = httpClient.post(endpoint + "/v1/chat/completions", headers, payload);
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
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        String payload = VLlmLlmUtil.promptToPayload(prompt, config, options, true);
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, streamMessageParser);
        String endpoint = config.getEndpoint();
        llmClient.start(endpoint + "/v1/chat/completions", headers, payload, clientListener, config);
    }


    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        String payload = VLlmLlmUtil.promptToEnabledPayload(document, options, config);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        String url = config.getEndpoint() + "/v1/embeddings";
        String response = httpClient.post(url, headers, payload);
        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }
        if (StringUtil.noText(response)) {
            return null;
        }
        VectorData vectorData = new VectorData();
        double[] embedding = JSONUtil.readDoubleArray(JSON.parseObject(response), "$.data[0].embedding");
        vectorData.setVector(embedding);
        return vectorData;
    }


}
