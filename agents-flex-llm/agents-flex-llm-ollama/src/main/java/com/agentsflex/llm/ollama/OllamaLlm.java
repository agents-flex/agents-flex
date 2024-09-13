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
import com.agentsflex.core.llm.MessageResponse;
import com.agentsflex.core.llm.StreamResponseListener;
import com.agentsflex.core.llm.client.BaseLlmClientListener;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.impl.DnjsonClient;
import com.agentsflex.core.llm.client.impl.SseClient;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.llm.response.FunctionMessageResponse;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.prompt.FunctionPrompt;
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
    private DnjsonClient dnjsonClient = new DnjsonClient();
    public AiMessageParser aiMessageParser = OllamaLlmUtil.getAiMessageParser();
    public FunctionMessageParser functionMessageParser = OllamaLlmUtil.getFunctionMessageParser();



    public OllamaLlm(OllamaLlmConfig config) {
        super(config);
    }


    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .put("prompt", document.getContent())
            .toJSON();

        String endpoint = config.getEndpoint();
        // https://github.com/ollama/ollama/blob/main/docs/api.md#generate-embeddings
        String response = httpClient.post(endpoint + "/api/embeddings", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONPath.read(response, "$.embedding", double[].class);
        vectorData.setVector(embedding);

        return vectorData;
    }


    @Override
    public <R extends MessageResponse<?>> R chat(Prompt<R> prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String endpoint = config.getEndpoint();
        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options,false);
        String response = httpClient.post(endpoint + "/api/chat", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        JSONObject jsonObject = JSON.parseObject(response);
        String error = jsonObject.getString("error");

        AbstractBaseMessageResponse<?> messageResponse;

        if (prompt instanceof FunctionPrompt) {
            messageResponse = new FunctionMessageResponse(((FunctionPrompt) prompt).getFunctions()
                , functionMessageParser.parse(jsonObject));
        } else {
            messageResponse = new AiMessageResponse(aiMessageParser.parse(jsonObject));
        }

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error);
        }

        //noinspection unchecked
        return (R) messageResponse;
    }


    @Override
    public <R extends MessageResponse<?>> void chatStream(Prompt<R> prompt, StreamResponseListener<R> listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = OllamaLlmUtil.promptToPayload(prompt, config, options, true);

        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser, null);
        dnjsonClient.start(endpoint + "/api/chat", headers, payload, clientListener, config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
