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
package com.agentsflex.llm.llama;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatOptions;
import com.agentsflex.llm.MessageResponse;
import com.agentsflex.llm.StreamResponseListener;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.SseClient;
import com.agentsflex.llm.embedding.EmbeddingOptions;
import com.agentsflex.llm.response.AbstractBaseMessageResponse;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;
import com.agentsflex.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class LlamaLlm extends BaseLlm<LlamaLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = LlamaLlmUtil.getAiMessageParser();


    public LlamaLlm(LlamaLlmConfig config) {
        super(config);
    }


    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        return null;
    }


    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> R chat(Prompt<M> prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String endpoint = config.getEndpoint();
        String payload = LlamaLlmUtil.promptToPayload(prompt, config, false);
        String response = httpClient.post(endpoint + "/v1/chat/completions", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AbstractBaseMessageResponse<M> messageResponse;

        if (prompt instanceof FunctionPrompt) {
            throw new IllegalStateException("Llama not support function calling");
        } else {
            //noinspection unchecked
            messageResponse = (AbstractBaseMessageResponse<M>) new AiMessageResponse(aiMessageParser.parse(jsonObject));
        }

        if (error != null && !error.isEmpty()) {
            messageResponse.setError(true);
            messageResponse.setErrorMessage(error.getString("message"));
            messageResponse.setErrorType(error.getString("type"));
            messageResponse.setErrorCode(error.getString("code"));
        }

        //noinspection unchecked
        return (R) messageResponse;
    }


    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> void chatStream(Prompt<M> prompt, StreamResponseListener<R, M> listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        String payload = LlamaLlmUtil.promptToPayload(prompt, config, true);

        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser, null);
        llmClient.start(endpoint + "/api/paas/v4/chat/completions", headers, payload, clientListener, config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
