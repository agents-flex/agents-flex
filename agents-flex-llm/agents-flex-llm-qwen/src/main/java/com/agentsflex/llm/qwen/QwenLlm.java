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
package com.agentsflex.llm.qwen;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.MessageListener;
import com.agentsflex.llm.MessageResponse;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.SseClient;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.Message;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.parser.impl.BaseAiMessageParser;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;
import com.agentsflex.util.StringUtil;

import java.util.HashMap;
import java.util.Map;

public class QwenLlm extends BaseLlm<QwenLlmConfig> {


    HttpClient httpClient = new HttpClient();

    public AiMessageParser aiMessageParser = QwenLlmUtil.getAiMessageParser();
    public FunctionMessageParser functionMessageParser = QwenLlmUtil.getFunctionMessageParser();

    public QwenLlm(QwenLlmConfig config) {
        super(config);
    }


    @Override
    public <R extends MessageResponse<M>, M extends Message> R chat(Prompt<M> prompt) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());


        String payload = QwenLlmUtil.promptToPayload(prompt, config);
        String responseString = httpClient.post("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", headers, payload);
        if (StringUtil.noText(responseString)) {
            return null;
        }

        if (prompt instanceof FunctionPrompt) {
            return (R) new FunctionMessageResponse(((FunctionPrompt) prompt).getFunctions(), functionMessageParser.parse(responseString));
        } else {
            return (R) new AiMessageResponse(aiMessageParser.parse(responseString));
        }
    }


    @Override
    public <R extends MessageResponse<M>, M extends Message> void chatAsync(Prompt<M> prompt, MessageListener<R, M> listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = QwenLlmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, new BaseAiMessageParser() {
            int prevMessageLength = 0;

            @Override
            public AiMessage parse(String content) {
                AiMessage aiMessage = aiMessageParser.parse(content);
                String messageContent = aiMessage.getContent();
                aiMessage.setContent(messageContent.substring(prevMessageLength));
                prevMessageLength = messageContent.length();
                return aiMessage;
            }
        }, functionMessageParser);
        llmClient.start("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", headers, payload, clientListener,config);
    }

    @Override
    public VectorData embeddings(Document document) {
        return null;
    }

}
