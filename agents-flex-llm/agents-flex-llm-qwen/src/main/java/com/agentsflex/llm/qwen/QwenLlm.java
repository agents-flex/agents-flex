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

import com.agentsflex.client.BaseLlmClientListener;
import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import com.agentsflex.client.impl.SseClient;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.text.Text;
import com.agentsflex.vector.VectorData;

import java.util.HashMap;
import java.util.Map;

public class QwenLlm extends BaseLlm<QwenLlmConfig> {

    public QwenLlm(QwenLlmConfig config) {
        super(config);
    }


    @Override
    public LlmClient chat(Prompt prompt, ChatListener listener) {
        LlmClient llmClient = new SseClient();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + getConfig().getApiKey());

        String payload = QwenLlmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, listener, prompt, new BaseLlmClientListener.MessageParser() {
            int prevMessageLength = 0;

            @Override
            public AiMessage parseMessage(String response) {
                AiMessage aiMessage = QwenLlmUtil.parseAiMessage(response, prevMessageLength);
                prevMessageLength += aiMessage.getContent().length();
                return aiMessage;
            }
        });
        llmClient.start("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation", headers, payload, clientListener);

        return llmClient;
    }


    @Override
    public VectorData embeddings(Text text) {
        return null;
    }
}
