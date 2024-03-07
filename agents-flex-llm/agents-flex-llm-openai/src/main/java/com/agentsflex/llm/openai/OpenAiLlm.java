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
package com.agentsflex.llm.openai;

import com.agentsflex.document.Document;
import com.agentsflex.functions.Function;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.ChatResponse;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.SseClient;
import com.agentsflex.llm.response.FunctionResultResponse;
import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.FunctionMessage;
import com.agentsflex.message.Message;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.util.StringUtil;
import com.agentsflex.store.VectorData;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiLlm extends BaseLlm<OpenAiLlmConfig> {

    private final HttpClient httpClient = new HttpClient();

    public OpenAiLlm(OpenAiLlmConfig config) {
        super(config);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChatResponse<M>, M extends Message> T chat(Prompt<M> prompt) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToPayload(prompt, config);
        String responseString = httpClient.post("https://api.openai.com/v1/chat/completions", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {
            List<Function<?>> functions = ((FunctionPrompt) prompt).getFunctions();

            JSONObject jsonObject = JSON.parseObject(responseString);
            String callFunctionName = (String) JSONPath.eval(jsonObject, "$.choices[0].tool_calls[0].function.name");
            String callFunctionArgsString = (String) JSONPath.eval(jsonObject, "$.choices[0].tool_calls[0].function.arguments");
            JSONObject callFunctionArgs = JSON.parseObject(callFunctionArgsString);

            FunctionMessage functionMessage = new FunctionMessage();
            functionMessage.setFunctionName(callFunctionName);
            functionMessage.setArgs(callFunctionArgs);
            return (T) new FunctionResultResponse(functions, functionMessage);
        } else {
            AiMessage aiMessage = OpenAiLLmUtil.parseAiMessage(responseString);
            return (T) new MessageResponse(aiMessage);
        }
    }

    @Override
    public <T extends ChatResponse<M>, M extends Message> void chatAsync(Prompt<M> prompt, ChatListener<T, M> listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, OpenAiLLmUtil::parseAiMessage, null);
        llmClient.start("https://api.openai.com/v1/chat/completions", headers, payload, clientListener);
    }


    @Override
    public VectorData embeddings(Document document) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToEmbeddingsPayload(document);

        // https://platform.openai.com/docs/api-reference/embeddings/create
        String response = httpClient.post("https://api.openai.com/v1/embeddings", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONPath.read(response, "$.data[0].embedding", double[].class);
        vectorData.setVector(embedding);

        return vectorData;
    }


}
