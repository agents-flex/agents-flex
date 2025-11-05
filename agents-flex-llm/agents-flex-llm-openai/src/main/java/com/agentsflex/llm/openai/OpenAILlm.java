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
package com.agentsflex.llm.openai;

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
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.core.util.LocalTokenCounter;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAILlm extends BaseLlm<OpenAILlmConfig> {

    private HttpClient httpClient = new HttpClient();
    private AiMessageParser aiMessageParser = OpenAILlmUtil.getAiMessageParser(false);
    private AiMessageParser streamMessageParser = OpenAILlmUtil.getAiMessageParser(true);

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public AiMessageParser getAiMessageParser() {
        return aiMessageParser;
    }

    public void setAiMessageParser(AiMessageParser aiMessageParser) {
        this.aiMessageParser = aiMessageParser;
    }

    public AiMessageParser getStreamMessageParser() {
        return streamMessageParser;
    }

    public void setStreamMessageParser(AiMessageParser streamMessageParser) {
        this.streamMessageParser = streamMessageParser;
    }

    public static OpenAILlm of(String apiKey) {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey(apiKey);
        return new OpenAILlm(config);
    }

    public static OpenAILlm of(String apiKey, String endpoint) {
        OpenAILlmConfig config = new OpenAILlmConfig();
        config.setApiKey(apiKey);
        config.setEndpoint(endpoint);
        return new OpenAILlm(config);
    }

    public OpenAILlm(OpenAILlmConfig config) {
        super(config);
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        Consumer<Map<String, String>> headersConfig = config.getHeadersConfig();
        if (headersConfig != null) {
            headersConfig.accept(headers);
        }

        // 非流式返回，比如 Qwen3 等必须设置 false，否则自动流式返回了
        if (options.getEnableThinking() == null) {
            options.setEnableThinking(false);
        }

        String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, false);
        if (config.isDebug()) {
            LogUtil.println(">>>>send payload:" + payload);
        }
        String url = config.getEndpoint() + config.getChatPath();
        String response = httpClient.post(url, headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return AiMessageResponse.error(prompt, response, "no content for response.");
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AiMessage aiMessage = aiMessageParser.parse(jsonObject);
        LocalTokenCounter.computeAndSetLocalTokens(prompt.toMessages(), aiMessage);
        AiMessageResponse messageResponse = new AiMessageResponse(prompt, response, aiMessage);

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

        String payload = OpenAILlmUtil.promptToPayload(prompt, config, options, true);
        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, streamMessageParser);
        llmClient.start(endpoint + config.getChatPath(), headers, payload, clientListener, config);
    }


    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAILlmUtil.promptToEmbeddingsPayload(document, options, config);
        String endpoint = config.getEndpoint();
        // https://platform.openai.com/docs/api-reference/embeddings/create
        String response = httpClient.post(endpoint + config.getEmbedPath(), headers, payload);

        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONPath.read(response, "$.data[0].embedding", double[].class);
        vectorData.setVector(embedding);

        return vectorData;
    }


}
