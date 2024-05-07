/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.FunctionMessage;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;
import com.agentsflex.util.Maps;
import com.agentsflex.util.StringUtil;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.Map;

public class ChatglmLlm extends BaseLlm<ChatglmLlmConfig> {

    private HttpClient httpClient = new HttpClient();
    public AiMessageParser aiMessageParser = ChatglmLlmUtil.getAiMessageParser();
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
        String responseString = httpClient.post(endpoint + "/api/paas/v4/embeddings", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        vectorData.setVector(JSONPath.read(responseString, "$.data[0].embedding", double[].class));

        return vectorData;
    }


    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> R chat(Prompt<M> prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String endpoint = config.getEndpoint();
        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, false);
        String responseString = httpClient.post(endpoint + "/api/paas/v4/chat/completions", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {
            FunctionMessage functionMessage = functionMessageParser.parse(responseString);
            return (R) new FunctionMessageResponse(((FunctionPrompt) prompt).getFunctions(), functionMessage);
        } else {
            AiMessage aiMessage = aiMessageParser.parse(responseString);
            return (R) new AiMessageResponse(aiMessage);
        }
    }


    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> void chatStream(Prompt<M> prompt, StreamResponseListener<R, M> listener, ChatOptions options) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", ChatglmLlmUtil.createAuthorizationToken(config));

        String payload = ChatglmLlmUtil.promptToPayload(prompt, config, true);

        String endpoint = config.getEndpoint();
        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser, functionMessageParser);
        llmClient.start(endpoint + "/api/paas/v4/chat/completions", headers, payload, clientListener, config);
    }


}
