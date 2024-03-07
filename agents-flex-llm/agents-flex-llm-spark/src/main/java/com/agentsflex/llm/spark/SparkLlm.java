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
package com.agentsflex.llm.spark;

import com.agentsflex.document.Document;
import com.agentsflex.llm.BaseLlm;
import com.agentsflex.llm.ChatContext;
import com.agentsflex.llm.ChatListener;
import com.agentsflex.llm.ChatResponse;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.WebSocketClient;
import com.agentsflex.llm.response.MessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;

import java.util.concurrent.CountDownLatch;

public class SparkLlm extends BaseLlm<SparkLlmConfig> {

    public SparkLlm(SparkLlmConfig config) {
        super(config);
    }

    @Override
    public VectorData embeddings(Document document) {
        return null;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChatResponse<?>> T chat(Prompt<T> prompt) {
        CountDownLatch latch = new CountDownLatch(1);
        AiMessage aiMessage = new AiMessage();
        chatAsync(prompt, new ChatListener() {
            @Override
            public void onMessage(ChatContext context, ChatResponse<?> response) {
                aiMessage.setContent(((AiMessage) response.getMessage()).getFullContent());
            }

            @Override
            public void onStop(ChatContext context) {
                ChatListener.super.onStop(context);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return (T) new MessageResponse(aiMessage);
    }




    @Override
    public void chatAsync(Prompt<?> prompt, ChatListener listener) {
        LlmClient llmClient = new WebSocketClient();
        String url = SparkLlmUtil.createURL(config);

        String payload = SparkLlmUtil.promptToPayload(prompt, config);

        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, SparkLlmUtil::parseAiMessage, null);
        llmClient.start(url, null, payload, clientListener);
    }


}
