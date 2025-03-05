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
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.Map;

public class TencentlmLlm extends BaseLlm<TencentLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = TencentLlmUtil.getAiMessageParser(false);
    public AiMessageParser aiStreamMessageParser = TencentLlmUtil.getAiMessageParser(true);

    public TencentlmLlm(TencentLlmConfig config) {
        super(config);
    }

    /**
     * 文档参考：https://cloud.tencent.com/document/product/1729/101841
     *
     * @param document 文档内容
     * @return 返回向量数据
     */
    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        String payload = Maps.of("Input", document.getContent()).toJSON();
        Map<String, String> headers = TencentLlmUtil.createAuthorizationToken(config, "GetEmbedding", payload);
        String response = httpClient.post(config.getEndpoint(), headers, payload);
        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
        }
        if (StringUtil.noText(response)) {
            return null;
        }
        VectorData vectorData = new VectorData();
        vectorData.setVector(JSONPath.read(response, "$.Response.Data[0].Embedding", double[].class));
        return vectorData;
    }


    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        String payload = TencentLlmUtil.promptToPayload(prompt, config, false, options);
        Map<String, String> headers = TencentLlmUtil.createAuthorizationToken(config, "ChatCompletions", payload);
        String response = httpClient.post(config.getEndpoint(), headers, payload);
        if (config.isDebug()) {
            System.out.println(">>>>receive payload:" + response);
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
        LlmClient llmClient = new SseClient();
        String payload = TencentLlmUtil.promptToPayload(prompt, config, true, options);
        Map<String, String> headers = TencentLlmUtil.createAuthorizationToken(config, "ChatCompletions", payload);
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiStreamMessageParser);
        llmClient.start(config.getEndpoint(), headers, payload, clientListener, config);
    }

}
