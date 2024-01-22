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

import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.SseClient;
import com.agentsflex.functions.Function;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.FunctionCalling;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.text.Text;
import com.agentsflex.util.OKHttpUtil;
import com.agentsflex.util.StringUtil;
import com.agentsflex.vector.VectorData;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiLlm extends BaseLlm<OpenAiLlmConfig> implements FunctionCalling {

    private final OKHttpUtil httpUtil = new OKHttpUtil();

    public OpenAiLlm(OpenAiLlmConfig config) {
        super(config);
    }


    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, listener, prompt, OpenAiLLmUtil::parseAiMessage);
        llmClient.start("https://api.openai.com/v1/chat/completions", headers, payload, clientListener);
        return llmClient;
    }


    @Override
    public VectorData embeddings(Text text) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToEmbeddingsPayload(text);

        // https://platform.openai.com/docs/api-reference/embeddings/create
        String response = httpUtil.post("https://api.openai.com/v1/embeddings", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        VectorData vectorData = new VectorData();
        double[] embedding = JSONPath.read(response, "$.data[0].embedding", double[].class);
        vectorData.setVector(embedding);

        return vectorData;
    }

    @Override
    public <R> R call(Prompt prompt, List<Function<R>> functions) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = OpenAiLLmUtil.promptToFunctionCallingPayload(prompt, config, functions);

        // https://platform.openai.com/docs/api-reference/embeddings/create
        String response = httpUtil.post("https://api.openai.com/v1/embeddings", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        JSONObject jsonObject = JSON.parseObject(response);
        String callFunctionName = (String) JSONPath.eval(jsonObject, "$.choices[0].tool_calls[0].function.name");
        String callFunctionArgsString = (String) JSONPath.eval(jsonObject, "$.choices[0].tool_calls[0].function.arguments");
        JSONObject callFunctionArgs = JSON.parseObject(callFunctionArgsString);

        for (Function<R> function : functions) {
            if (function.getName().equals(callFunctionName)) {
                return function.invoke(callFunctionArgs);
            }
        }
        return null;
    }
}
