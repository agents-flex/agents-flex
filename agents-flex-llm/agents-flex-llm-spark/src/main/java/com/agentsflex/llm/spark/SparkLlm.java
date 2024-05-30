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
package com.agentsflex.llm.spark;

import com.agentsflex.document.Document;
import com.agentsflex.llm.*;
import com.agentsflex.llm.client.BaseLlmClientListener;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import com.agentsflex.llm.client.impl.WebSocketClient;
import com.agentsflex.llm.embedding.EmbeddingOptions;
import com.agentsflex.llm.response.AiMessageResponse;
import com.agentsflex.llm.response.FunctionMessageResponse;
import com.agentsflex.message.AiMessage;
import com.agentsflex.message.FunctionMessage;
import com.agentsflex.message.Message;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.prompt.FunctionPrompt;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.store.VectorData;
import com.agentsflex.util.StringUtil;
import com.alibaba.fastjson.JSONPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

public class SparkLlm extends BaseLlm<SparkLlmConfig> {

    private static final Logger logger = LoggerFactory.getLogger(SparkLlm.class);
    public AiMessageParser aiMessageParser = SparkLlmUtil.getAiMessageParser();
    public FunctionMessageParser functionMessageParser = SparkLlmUtil.getFunctionMessageParser();

    private final HttpClient httpClient = new HttpClient();


    public SparkLlm(SparkLlmConfig config) {
        super(config);
    }

    @Override
    public VectorData embed(Document document, EmbeddingOptions options) {
        String payload = SparkLlmUtil.embedPayload(config, document);
        String resp = httpClient.post(SparkLlmUtil.createEmbedURL(config), null, payload);
        if (StringUtil.noText(resp)) {
            return null;
        }

        Integer code = JSONPath.read(resp, "$.header.code", Integer.class);
        if (code == null || code != 0) {
            logger.error(resp);
            return null;
        }

        String text = JSONPath.read(resp, "$.payload.feature.text", String.class);
        if (StringUtil.noText(text)) {
            return null;
        }

        byte[] buffer = Base64.getDecoder().decode(text);
        double[] vector = new double[buffer.length / 4];
        for (int i = 0; i < vector.length; i++) {
            int n = i * 4;
            vector[i] = ByteBuffer.wrap(buffer, n, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        }

        VectorData vectorData = new VectorData();
        vectorData.setVector(vector);
        return vectorData;
    }


    @SuppressWarnings("unchecked")
    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> R chat(Prompt<M> prompt, ChatOptions options) {
        CountDownLatch latch = new CountDownLatch(1);
        Message[] messages = new Message[1];
        chatStream(prompt, new StreamResponseListener<MessageResponse<M>, M>() {
            @Override
            public void onMessage(ChatContext context, MessageResponse<M> response) {
                if (response.getMessage() instanceof FunctionMessage) {
                    messages[0] = response.getMessage();
                } else {
                    if (messages[0] == null) {
                        messages[0] = response.getMessage();
                    } else {
                        ((AiMessage) messages[0]).setContent(response.getMessage().getFullContent());
                    }
                }
            }

            @Override
            public void onStop(ChatContext context) {
                StreamResponseListener.super.onStop(context);
                latch.countDown();
            }
        }, options);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (prompt instanceof FunctionPrompt) {
            return (R) new FunctionMessageResponse(((FunctionPrompt) prompt).getFunctions(), (FunctionMessage) messages[0]);
        } else {
            return (R) new AiMessageResponse((AiMessage) messages[0]);
        }
    }


    @Override
    public <R extends MessageResponse<M>, M extends AiMessage> void chatStream(Prompt<M> prompt, StreamResponseListener<R, M> listener, ChatOptions options) {
        LlmClient llmClient = new WebSocketClient();
        String url = SparkLlmUtil.createURL(config);

        String payload = SparkLlmUtil.promptToPayload(prompt, config, options);

        LlmClientListener clientListener = new BaseLlmClientListener(this, llmClient, listener, prompt, aiMessageParser, functionMessageParser);
        llmClient.start(url, null, payload, clientListener, config);
    }


}
