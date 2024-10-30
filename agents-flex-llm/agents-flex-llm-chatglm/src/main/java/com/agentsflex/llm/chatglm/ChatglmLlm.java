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
package com.agentsflex.llm.chatglm;

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

public class ChatglmLlm extends BaseLlm<ChatglmLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = ChatglmLlmUtil.getAiMessageParser(false);
    public AiMessageParser aiStreamMessageParser = ChatglmLlmUtil.getAiMessageParser(true);
    public FunctionMessageParser functionMessageParser = ChatglmLlmUtil.getFunctionMessageParser();


    public ChatglmLlm(ChatglmLlmConfig config) {
        super(config);
    }

    /**
     * 文档参考：https://open.bigmodel.cn/dev/api#text_embedding
     *
     * @param document 文档内容
     * @return 返回向量数据
     */
    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String endpoint = config.getEndpoint();
        String payload = Maps.of("model", "embedding-2").put("input", document.getContent()).toJSON();
        String response = httpClient.post(endpoint + "/api/paas/v4/embeddings", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        vectorData.setVector(JSONPath.read(response, "$.data[0].embedding", double[].class));

        return vectorData;
    }


    @Override
    public <R extends AiMessageResponse> R chat(Prompt<R> prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String endpoint = config.getEndpoint();
        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, false, options);
        String response = httpClient.post(endpoint + "/api/paas/v4/chat/completions", headers, payload);

        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            return null;
        }

        JSONObject jsonObject = JSON.parseObject(response);
        JSONObject error = jsonObject.getJSONObject("error");

        AbstractBaseMessageResponse<?> messageResponse;

        if (prompt instanceof FunctionPrompt) {
            messageResponse = new FunctionMessageResponse(response, ((FunctionPrompt) prompt).getFunctions()
                , functionMessageParser.parse(jsonObject));
        } else {
            messageResponse = new AiMessageResponse(response, aiMessageParser.parse(jsonObject));
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
    public <R extends AiMessageResponse> void chatStream(Prompt<R> prompt, StreamResponseListener<R> listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, true, options);

        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiStreamMessageParser, functionMessageParser);
        llmClient.start(endpoint + "/api/paas/v4/chat/completions", headers, payload, clientListener, config);
    }


}
