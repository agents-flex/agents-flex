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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.BaseLlm;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.impl.DnjsonClient;
import com.agentsflex.core.llm.client.impl.SseClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.Map;

public class OllamaLlm extends BaseLlm<OllamaLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    private final DnjsonClient dnjsonClient = new DnjsonClient();
    public AiMessageParser aiMessageParser = OllamaLlmUtil.getAiMessageParser();


    public OllamaLlm(OllamaLlmConfig config) {
        super(config);
    }


    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        if (StringUtil.hasText(getConfig().getApiKey())) {
            headers.put("Authorization", "Bearer " + getConfig().getApiKey());
        }

        String payload = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("input", document.getContent())
            .toJSON();

        String endpoint = config.getEndpoint();
        // https://github.com/ollama/ollama/blob/main/docs/api.md#generate-embeddings
        String response = httpClient.post(endpoint + "/api/embed", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }
        VectorData vectorData = new VectorData();

        double[] embedding = JSONPath.read(response, "$.embeddings[0]", double[].class);
        vectorData.setVector(embedding);

        vectorData.addMetadata("total_duration", JSONPath.read(response, "$.total_duration", Long.class));
        vectorData.addMetadata("load_duration", JSONPath.read(response, "$.load_duration", Long.class));
        vectorData.addMetadata("prompt_eval_count", JSONPath.read(response, "$.prompt_eval_count", Integer.class));
        vectorData.addMetadata("model", JSONPath.read(response, "$.model", String.class));

        return vectorData;
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String endpoint = config.getEndpoint();
        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options, false);
        String response = httpClient.post(endpoint + "/api/chat", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
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
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options, true);

        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser);
        dnjsonClient.start(endpoint + "/api/chat", headers, payload, clientListener, config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
